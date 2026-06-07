package io.quarkmind.plugin.scouting;

import io.casehub.annotation.CaseType;
import io.casehub.core.CaseFile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.plugin.ScoutingTask;
import io.quarkmind.domain.*;
import io.quarkmind.sc2.IntentQueue;
import io.quarkmind.sc2.intent.MoveIntent;
import org.drools.ruleunits.api.RuleUnit;
import org.drools.ruleunits.api.RuleUnitInstance;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.SettingsScope;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkmind.agent.GameSession;
import io.quarkmind.agent.PluginDecisionEvent;
import io.quarkmind.agent.QuarkMindCapabilityTag;
import io.quarkmind.agent.ScoutingIntelBroker;
import io.quarkmind.agent.plugin.ScoutingIntelPayload;
import io.quarkmind.agent.plugin.ScoutingIntelPreferences;
import io.quarkmind.agent.plugin.ScoutingIntelType;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.event.Event;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Drools-backed {@link ScoutingTask} — fourth R&D integration.
 *
 * <p>Each tick:
 * <ol>
 *   <li>Detects game restarts (frame going backwards) and resets buffers.</li>
 *   <li>Computes passive intel: {@code ENEMY_ARMY_SIZE} and {@code NEAREST_THREAT}.</li>
 *   <li>Updates Java event buffers via {@link ScoutingSessionManager}; evicts expired events.</li>
 *   <li>Fires a fresh {@link RuleUnitInstance} from the current buffer state.</li>
 *   <li>Writes {@code ENEMY_BUILD_ORDER}, {@code TIMING_ATTACK_INCOMING}, {@code ENEMY_POSTURE}.</li>
 *   <li>Dispatches active probe scout (same logic as BasicScoutingTask).</li>
 * </ol>
 *
 * <p>Replaces {@link io.quarkmind.plugin.BasicScoutingTask} as the active CDI bean
 * (BasicScoutingTask is marked {@code @Alternative} to avoid ambiguous-bean conflict).
 */
@ApplicationScoped
@CaseType("starcraft-game")
public class DroolsScoutingTask implements ScoutingTask {

    /** Game-speed constant: SC2 Faster = 22.4 frames per second. */
    static final double FRAMES_PER_SECOND = 22.4;

    /** Delay before sending a scout — let the economy stabilise first. */
    public static final int SCOUT_DELAY_TICKS = 20;

    private static final Logger log = Logger.getLogger(DroolsScoutingTask.class);

    private final RuleUnit<ScoutingRuleUnit> ruleUnit;
    private final ScoutingSessionManager     sessionManager;
    private final IntentQueue                intentQueue;

    @ConfigProperty(name = "scouting.map.width", defaultValue = "256")
    int mapWidth;

    @Inject Event<PluginDecisionEvent> decisionEvents;
    @Inject GameSession gameSession;

    // --- qhorus Layer 3 fields ---
    @Inject ScoutingIntelBroker broker;
    @Inject MessageService messageService;
    @Inject ObjectMapper objectMapper;
    @Inject PreferenceProvider preferenceProvider;

    // Per-type previous values for change detection
    volatile Point2d prevThreatPos   = null;
    volatile int     prevArmySize    = -1;
    volatile String  prevPosture     = null;
    volatile Boolean prevTimingAlert = null;
    volatile String  prevBuildOrder  = null;

    // Threshold values loaded from preferences at @PostConstruct
    volatile double  minThreatDistance;
    volatile int     minArmySizeDelta;
    volatile boolean postureDispatchEnabled;
    volatile boolean timingAlertDispatchEnabled;
    volatile boolean buildOrderDispatchEnabled;

    private volatile int prevEnemyHash = 0;

    /** Tag of the probe currently assigned to scout. Null when no active scout. */
    private volatile String scoutProbeTag;
    // Single scheduler thread — no synchronisation needed
    private long lastFrame = -1;

    @Inject
    public DroolsScoutingTask(RuleUnit<ScoutingRuleUnit> ruleUnit,
                               ScoutingSessionManager sessionManager,
                               IntentQueue intentQueue) {
        this.ruleUnit       = ruleUnit;
        this.sessionManager = sessionManager;
        this.intentQueue    = intentQueue;
    }

    @PostConstruct
    void initThresholds() {
        initThresholds(preferenceProvider.resolve(SettingsScope.root()));
    }

    void initThresholds(io.casehub.platform.api.preferences.Preferences prefs) {
        minThreatDistance          = prefs.getOrDefault(ScoutingIntelPreferences.THREAT_POSITION_MIN_DISTANCE).asDouble();
        minArmySizeDelta           = prefs.getOrDefault(ScoutingIntelPreferences.ARMY_SIZE_MIN_DELTA).asInt();
        postureDispatchEnabled     = prefs.getOrDefault(ScoutingIntelPreferences.POSTURE_DISPATCH_ENABLED).asBoolean();
        timingAlertDispatchEnabled = prefs.getOrDefault(ScoutingIntelPreferences.TIMING_ALERT_DISPATCH_ENABLED).asBoolean();
        buildOrderDispatchEnabled  = prefs.getOrDefault(ScoutingIntelPreferences.BUILD_ORDER_DISPATCH_ENABLED).asBoolean();
    }

    @Override public String getId()   { return "scouting.drools-cep"; }
    @Override public String getName() { return "Drools CEP Scouting"; }
    @Override public Set<String> entryCriteria() { return Set.of(QuarkMindCaseFile.READY); }
    @Override public Set<String> producedKeys()  {
        return Set.of(
            QuarkMindCaseFile.ENEMY_ARMY_SIZE,
            QuarkMindCaseFile.NEAREST_THREAT,
            QuarkMindCaseFile.ENEMY_BUILD_ORDER,
            QuarkMindCaseFile.TIMING_ATTACK_INCOMING,
            QuarkMindCaseFile.ENEMY_POSTURE);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void execute(CaseFile caseFile) {
        List<Unit>     enemies   = (List<Unit>)     caseFile.get(QuarkMindCaseFile.ENEMY_UNITS,  List.class).orElse(List.of());
        List<Building> buildings = (List<Building>) caseFile.get(QuarkMindCaseFile.MY_BUILDINGS, List.class).orElse(List.of());
        List<Unit>     workers   = (List<Unit>)     caseFile.get(QuarkMindCaseFile.WORKERS,      List.class).orElse(List.of());
        long frame = caseFile.get(QuarkMindCaseFile.GAME_FRAME, Long.class).orElse(0L);

        int enemyHash = enemies.stream()
                .map(Unit::tag)
                .sorted()
                .collect(Collectors.joining())
                .hashCode();
        if (enemyHash != prevEnemyHash) {
            prevEnemyHash = enemyHash;
            decisionEvents.fireAsync(new PluginDecisionEvent(
                    getId(), QuarkMindCapabilityTag.SCOUTING,
                    AttestationVerdict.SOUND, gameSession.id(), (int) frame));
        }

        // Detect game restart (mock loop resets frame to 0)
        if (frame < lastFrame) {
            sessionManager.reset();
            scoutProbeTag    = null;
            prevThreatPos    = null;
            prevArmySize     = -1;
            prevPosture      = null;
            prevTimingAlert  = null;
            prevBuildOrder   = null;
        }
        lastFrame = frame;

        long gameTimeMs = (long) (frame * (1000.0 / FRAMES_PER_SECOND));
        Point2d ourNexus      = nexusPosition(buildings);
        Point2d estimatedBase = estimatedEnemyBase(ourNexus, mapWidth);

        // --- Passive intel (plain Java, no rules needed) ---
        int currentArmySize = enemies.size();
        caseFile.put(QuarkMindCaseFile.ENEMY_ARMY_SIZE, currentArmySize);
        Point2d nearest = null;
        if (!enemies.isEmpty()) {
            nearest = enemies.stream()
                .min(Comparator.comparingDouble(e -> e.position().distanceTo(ourNexus)))
                .map(Unit::position)
                .orElse(null);
            if (nearest != null) {
                caseFile.put(QuarkMindCaseFile.NEAREST_THREAT, nearest);
            }
        }

        // --- CEP: gate on broker subscriptions to avoid Drools overhead when not needed ---
        boolean needsCep = broker.isSubscribed(ScoutingIntelType.BUILD_ORDER)
                        || broker.isSubscribed(ScoutingIntelType.TIMING_ALERT)
                        || broker.isSubscribed(ScoutingIntelType.POSTURE);
        ScoutingRuleUnit data = null;
        if (needsCep) {
            sessionManager.processFrame(enemies, gameTimeMs, ourNexus, estimatedBase);
            sessionManager.evict(gameTimeMs);
            data = sessionManager.buildRuleUnit();
            try (RuleUnitInstance<ScoutingRuleUnit> instance = ruleUnit.createInstance(data)) {
                instance.fire();
            }
        }

        // --- Write CEP intel to CaseFile ---
        String build = data != null && !data.getDetectedBuilds().isEmpty()
            ? data.getDetectedBuilds().get(0) : "UNKNOWN";
        caseFile.put(QuarkMindCaseFile.ENEMY_BUILD_ORDER, build);
        boolean timing = data != null && !data.getTimingAlerts().isEmpty();
        caseFile.put(QuarkMindCaseFile.TIMING_ATTACK_INCOMING, timing);
        String posture = data != null && !data.getPostureDecisions().isEmpty()
            ? data.getPostureDecisions().get(0) : "UNKNOWN";
        caseFile.put(QuarkMindCaseFile.ENEMY_POSTURE, posture);

        log.debugf("[SCOUTING] enemies=%d | build=%s | timing=%b | posture=%s",
            currentArmySize, build, timing, posture);

        // --- Layer 3: dispatch changed intel to qhorus subscribers ---
        if (nearest != null && broker.isSubscribed(ScoutingIntelType.THREAT_POSITION)
                && shouldDispatchThreatPosition(prevThreatPos, nearest, minThreatDistance)) {
            prevThreatPos = nearest;
            dispatch(new ScoutingIntelPayload.ThreatPosition(nearest));
        }

        if (broker.isSubscribed(ScoutingIntelType.ARMY_SIZE)
                && shouldDispatchArmySize(prevArmySize, currentArmySize, minArmySizeDelta)) {
            prevArmySize = currentArmySize;
            dispatch(new ScoutingIntelPayload.ArmySize(currentArmySize));
        }

        if (data != null) {
            if (postureDispatchEnabled && broker.isSubscribed(ScoutingIntelType.POSTURE)
                    && !posture.equals(prevPosture)) {
                prevPosture = posture;
                dispatch(new ScoutingIntelPayload.PostureUpdate(posture));
            }

            if (timingAlertDispatchEnabled && broker.isSubscribed(ScoutingIntelType.TIMING_ALERT)
                    && !Boolean.valueOf(timing).equals(prevTimingAlert)) {
                prevTimingAlert = timing;
                dispatch(new ScoutingIntelPayload.TimingAlert(timing));
            }

            if (buildOrderDispatchEnabled && broker.isSubscribed(ScoutingIntelType.BUILD_ORDER)
                    && !build.equals(prevBuildOrder)) {
                prevBuildOrder = build;
                dispatch(new ScoutingIntelPayload.BuildOrder(build));
            }
        }

        // --- Active scouting (same as BasicScoutingTask) ---
        if (enemies.isEmpty()) {
            maybeSendScout(frame, workers, estimatedBase);
        } else {
            scoutProbeTag = null; // enemies found — release scout
        }
    }

    private void maybeSendScout(long frame, List<Unit> workers, Point2d target) {
        if (frame < SCOUT_DELAY_TICKS) return;
        if (workers.isEmpty()) return;

        if (scoutProbeTag != null) {
            boolean alive = workers.stream().anyMatch(w -> w.tag().equals(scoutProbeTag));
            if (alive) return;
            scoutProbeTag = null; // probe died — assign a new one
        }

        Unit scout = workers.get(workers.size() - 1);
        scoutProbeTag = scout.tag();
        intentQueue.add(new MoveIntent(scout.tag(), target));
        log.infof("[SCOUTING] Scout probe %s dispatched toward %s", scoutProbeTag, target);
    }

    static Point2d estimatedEnemyBase(Point2d ourBase, int mapWidth) {
        int margin    = mapWidth / 8;
        int farCoord  = mapWidth - margin;
        int nearCoord = margin;
        float threshold = mapWidth / 4f;
        float targetX = ourBase.x() < threshold ? farCoord : nearCoord;
        float targetY = ourBase.y() < threshold ? farCoord : nearCoord;
        return new Point2d(targetX, targetY);
    }

    private void dispatch(ScoutingIntelPayload payload) {
        try {
            String content = objectMapper.writeValueAsString(
                java.util.Map.of("type", payload.getClass().getSimpleName(), "data", payload));
            messageService.dispatch(MessageDispatch.builder()
                .channelId(broker.channelId())
                .sender(getId())
                .actorType(ActorType.AGENT)   // GE-20260529-e32a4d: required — omitting throws IAE
                .type(MessageType.STATUS)     // STATUS carries content; EVENT forces null (GE-20260607-d051f2)
                .content(content)
                .build());
        } catch (JsonProcessingException e) {
            log.warnf("Failed to serialise scouting intel payload: %s", e.getMessage());
        }
    }

    static boolean shouldDispatchThreatPosition(Point2d prev, Point2d curr, double threshold) {
        if (prev == null) return true;
        if (prev.equals(curr)) return false;
        double dx = curr.x() - prev.x();
        double dy = curr.y() - prev.y();
        return Math.sqrt(dx * dx + dy * dy) > threshold;
    }

    static boolean shouldDispatchArmySize(int prev, int curr, int minDelta) {
        return Math.abs(curr - prev) >= minDelta;
    }

    private static Point2d nexusPosition(List<Building> buildings) {
        return buildings.stream()
            .filter(b -> b.type() == BuildingType.NEXUS)
            .findFirst()
            .map(Building::position)
            .orElse(new Point2d(0, 0));
    }
}

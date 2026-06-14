package io.quarkmind.plugin.scouting;

import io.casehub.annotation.CaseType;
import io.casehub.api.context.CaseContext;
import io.casehub.core.CaseFile;
import io.quarkmind.agent.CaseFileContext;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.plugin.ScoutingTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
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
import io.quarkmind.agent.EnemyPostureClassifiedEvent;
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
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Drools-backed {@link ScoutingTask} — fourth R&D integration.
 *
 * <p>Each tick:
 * <ol>
 *   <li>Detects game restarts (frame going backwards) and resets buffers.</li>
 *   <li>Computes passive intel: {@code ENEMY_ARMY_SIZE}. Nearest enemy position is computed locally for dual-stack dispatch but no longer written to CaseFile.</li>
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
    @Inject Event<EnemyPostureClassifiedEvent> postureClassified;
    @Inject GameSession gameSession;

    // --- dual-stack delivery fields ---
    @Inject ScoutingIntelBroker broker;
    @Inject MessageService messageService;
    @Inject ObjectMapper objectMapper;
    @Inject PreferenceProvider preferenceProvider;

    @Inject
    @org.eclipse.microprofile.config.inject.ConfigProperty(
        name = "quarkmind.scouting.advisory.enabled", defaultValue = "true")
    boolean advisoryEnabled;

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

    /** Resets per-game dispatch-deduplication state. Called between @QuarkusTest runs to prevent state leakage. */
    public void resetDispatchState() {
        prevThreatPos   = null;
        prevArmySize    = -1;
        prevPosture     = null;
        prevTimingAlert = null;
        prevBuildOrder  = null;
        prevEnemyHash   = 0;
        scoutProbeTag   = null;
        lastFrame       = -1;
    }

    @PostConstruct
    void initThresholds() {
        initThresholds(preferenceProvider.resolve(SettingsScope.root()));
    }

    /** Hot-reload of dispatch thresholds (#178) — called from POST /qa/scouting/thresholds/reload. */
    public void refreshThresholds() {
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

    // ── New engine API ───────────────────────────────────────────────────────

    @Override
    public Set<String> requires() { return Set.of(QuarkMindCaseFile.READY); }

    @Override
    public Predicate<CaseContext> activateIf() {
        return ctx -> ctx.contains(QuarkMindCaseFile.READY);
    }

    @Override
    public void execute(final CaseContext ctx) {
        List<Unit>     enemies   = ctx.getList(QuarkMindCaseFile.ENEMY_UNITS,  Unit.class);
        List<Building> buildings = ctx.getList(QuarkMindCaseFile.MY_BUILDINGS, Building.class);
        List<Unit>     workers   = ctx.getList(QuarkMindCaseFile.WORKERS,      Unit.class);
        Long frameL = ctx.getAs(QuarkMindCaseFile.GAME_FRAME, Long.class);
        long frame = frameL != null ? frameL : 0L;

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
            prevEnemyHash    = 0;
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
        ctx.set(QuarkMindCaseFile.ENEMY_ARMY_SIZE, currentArmySize);
        // Nearest enemy position used for threat intel dispatch — no longer written to CaseFile
        Point2d nearest = null;
        if (!enemies.isEmpty()) {
            nearest = enemies.stream()
                .min(Comparator.comparingDouble(e -> e.position().distanceTo(ourNexus)))
                .map(Unit::position)
                .orElse(null);
        }

        // --- CEP gate: run Drools when any plugin subscribes or advisory channel is active ---
        boolean needsCep = broker.isSubscribed(ScoutingIntelType.BUILD_ORDER)
                        || broker.isSubscribed(ScoutingIntelType.TIMING_ALERT)
                        || broker.isSubscribed(ScoutingIntelType.POSTURE)
                        || advisoryEnabled;
        ScoutingRuleUnit data = null;
        if (needsCep) {
            sessionManager.processFrame(enemies, gameTimeMs, ourNexus, estimatedBase);
            sessionManager.evict(gameTimeMs);
            data = sessionManager.buildRuleUnit();
            try (RuleUnitInstance<ScoutingRuleUnit> instance = ruleUnit.createInstance(data)) {
                instance.fire();
            }
        }

        // --- Write CEP intel to context ---
        String build = data != null && !data.getDetectedBuilds().isEmpty()
            ? data.getDetectedBuilds().get(0) : "UNKNOWN";
        ctx.set(QuarkMindCaseFile.ENEMY_BUILD_ORDER, build);
        boolean timing = data != null && !data.getTimingAlerts().isEmpty();
        ctx.set(QuarkMindCaseFile.TIMING_ATTACK_INCOMING, timing);
        String posture = data != null && !data.getPostureDecisions().isEmpty()
            ? data.getPostureDecisions().get(0) : "UNKNOWN";
        ctx.set(QuarkMindCaseFile.ENEMY_POSTURE, posture);

        log.debugf("[SCOUTING] enemies=%d | build=%s | timing=%b | posture=%s",
            currentArmySize, build, timing, posture);

        // --- Dual-stack intel delivery ---
        // Stack 1: broker.update() (in-process, for plugins)
        // Stack 2: dispatchToAdvisory() (Qhorus, for LLM advisors — always when gate fires)
        if (nearest != null
                && (broker.isSubscribed(ScoutingIntelType.THREAT_POSITION) || advisoryEnabled)
                && shouldDispatchThreatPosition(prevThreatPos, nearest, minThreatDistance)) {
            prevThreatPos = nearest;
            publishIntel(new ScoutingIntelPayload.ThreatPosition(nearest));
        }

        if ((broker.isSubscribed(ScoutingIntelType.ARMY_SIZE) || advisoryEnabled)
                && shouldDispatchArmySize(prevArmySize, currentArmySize, minArmySizeDelta)) {
            prevArmySize = currentArmySize;
            publishIntel(new ScoutingIntelPayload.ArmySize(currentArmySize));
        }

        if (data != null) {
            if (!posture.equals(prevPosture)) {
                prevPosture = posture;
                // Broker/advisory dispatch — gated by postureDispatchEnabled preference
                if (postureDispatchEnabled
                        && (broker.isSubscribed(ScoutingIntelType.POSTURE) || advisoryEnabled)) {
                    publishIntel(new ScoutingIntelPayload.PostureUpdate(posture));
                }
                // Trust routing checkpoint — always fires, independent of dispatch preference.
                // Synchronous fire: StrategyTrustObserver.onPostureClassified() runs inline
                // within this execute() call, so the pivot is effective in the same tick.
                if (!"UNKNOWN".equals(posture)) {
                    postureClassified.fire(new EnemyPostureClassifiedEvent(posture));
                }
            }

            if (timingAlertDispatchEnabled
                    && (broker.isSubscribed(ScoutingIntelType.TIMING_ALERT) || advisoryEnabled)
                    && !Boolean.valueOf(timing).equals(prevTimingAlert)) {
                prevTimingAlert = timing;
                publishIntel(new ScoutingIntelPayload.TimingAlert(timing));
            }

            if (buildOrderDispatchEnabled
                    && (broker.isSubscribed(ScoutingIntelType.BUILD_ORDER) || advisoryEnabled)
                    && !build.equals(prevBuildOrder)) {
                prevBuildOrder = build;
                publishIntel(new ScoutingIntelPayload.BuildOrder(build));
            }
        }

        // --- Active scouting (same as BasicScoutingTask) ---
        if (enemies.isEmpty()) {
            maybeSendScout(frame, workers, estimatedBase);
        } else {
            scoutProbeTag = null; // enemies found — release scout
        }
    }

    @Override
    public Set<String> produces() {
        return Set.of(
            QuarkMindCaseFile.ENEMY_ARMY_SIZE,
            QuarkMindCaseFile.ENEMY_BUILD_ORDER,
            QuarkMindCaseFile.TIMING_ATTACK_INCOMING,
            QuarkMindCaseFile.ENEMY_POSTURE);
    }

    // ── Phase 1 bridges — removed when poc CaseFile is dropped in Phase 2 ──

    @Override public Set<String> entryCriteria() { return requires(); }
    @Override public Set<String> producedKeys()  { return produces(); }

    @Override
    public boolean canActivate(final CaseFile caseFile) {
        return activateIf().test(new CaseFileContext(caseFile));
    }

    @Override
    public void execute(final CaseFile caseFile) {
        var ctx = new CaseFileContext(caseFile);
        execute(ctx);
        produces().forEach(k -> { Object v = ctx.get(k); if (v != null) caseFile.put(k, v); });
    }

    // ── Private helpers ──────────────────────────────────────────────────────

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

    private void publishIntel(ScoutingIntelPayload payload) {
        if (broker.isSubscribed(payload.type())) {
            broker.update(payload);             // Stack 1: in-memory, for plugin consumers
        }
        dispatchToAdvisory(payload);            // Stack 2: Qhorus, for LLM advisors (always)
    }

    private void dispatchToAdvisory(ScoutingIntelPayload payload) {
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

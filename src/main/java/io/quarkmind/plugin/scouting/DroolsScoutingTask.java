package io.quarkmind.plugin.scouting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.annotation.CaseType;
import io.casehub.api.context.CaseContext;
import io.casehub.blocks.summarisation.EventLevel;
import io.casehub.blocks.summarisation.LevelEvent;
import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.SettingsScope;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkmind.agent.EnemyPostureClassifiedEvent;
import io.quarkmind.agent.GameSession;
import io.quarkmind.agent.PluginDecisionEvent;
import io.quarkmind.agent.QuarkMindCapabilityTag;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.ScoutingIntelBroker;
import io.quarkmind.agent.StrategyTaxonomy;
import io.quarkmind.agent.plugin.ScoutingIntelPayload;
import io.quarkmind.agent.plugin.ScoutingIntelPayload.PatternAssessmentPayload;
import io.quarkmind.agent.plugin.ScoutingIntelPreferences;
import io.quarkmind.agent.plugin.ScoutingIntelType;
import io.quarkmind.agent.plugin.ScoutingTask;
import io.quarkmind.domain.Building;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.PatternAssessment;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.PhaseResolver;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.StrategyArchetype;
import io.quarkmind.domain.Unit;
import io.quarkmind.sc2.IntentQueue;
import io.quarkmind.sc2.intent.MoveIntent;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.drools.ruleunits.api.RuleUnit;
import org.drools.ruleunits.api.RuleUnitInstance;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@ApplicationScoped
@CaseType("starcraft-game")
public class DroolsScoutingTask implements ScoutingTask {

    static final double FRAMES_PER_SECOND = SC2Data.GAME_LOOPS_PER_SECOND;
    public static final int SCOUT_DELAY_TICKS = 20;
    static final EventLevel LEVEL_1 = new EventLevel("intel", 1);

    private static final Logger log = Logger.getLogger(DroolsScoutingTask.class);

    private final RuleUnit<ScoutingRuleUnit> ruleUnit;
    private final RuleUnit<PatternClassificationRuleUnit> patternRuleUnit;
    private final ScoutingSessionManager     sessionManager;
    private final IntentQueue                intentQueue;

    @ConfigProperty(name = "scouting.map.width", defaultValue = "256")
    int mapWidth;

    @Inject Event<PluginDecisionEvent> decisionEvents;
    @Inject Event<EnemyPostureClassifiedEvent> postureClassified;
    @Inject GameSession gameSession;

    @Inject ScoutingIntelBroker broker;
    @Inject MessageService messageService;
    @Inject ObjectMapper objectMapper;
    @Inject PreferenceProvider preferenceProvider;
    @Inject StrategyTaxonomy taxonomy;
    @Inject PhaseResolver phaseResolver;


    @Inject
    @org.eclipse.microprofile.config.inject.ConfigProperty(
        name = "quarkmind.scouting.advisory.enabled", defaultValue = "true")
    boolean advisoryEnabled;

    volatile Point2d prevThreatPos   = null;
    volatile int     prevArmySize    = -1;
    volatile String  prevPosture     = null;
    volatile Boolean prevTimingAlert = null;
    volatile String  prevBuildOrder  = null;

    volatile double  minThreatDistance;
    volatile int     minArmySizeDelta;
    volatile boolean postureDispatchEnabled;
    volatile boolean timingAlertDispatchEnabled;
    volatile boolean buildOrderDispatchEnabled;
    volatile boolean patternAssessmentDispatchEnabled;

    private volatile int prevEnemyHash = 0;
    private volatile String scoutProbeTag;
    private long lastFrame = -1;

    private final EnumMap<StrategyArchetype, Double> cumulativeConfidence =
        new EnumMap<>(StrategyArchetype.class);
    volatile List<PatternAssessment> prevAssessments = List.of();

    @Inject
    public DroolsScoutingTask(RuleUnit<ScoutingRuleUnit> ruleUnit,
                               RuleUnit<PatternClassificationRuleUnit> patternRuleUnit,
                               ScoutingSessionManager sessionManager,
                               IntentQueue intentQueue) {
        this.ruleUnit        = ruleUnit;
        this.patternRuleUnit = patternRuleUnit;
        this.sessionManager  = sessionManager;
        this.intentQueue     = intentQueue;
    }

    public void resetDispatchState() {
        prevThreatPos   = null;
        prevArmySize    = -1;
        prevPosture     = null;
        prevTimingAlert = null;
        prevBuildOrder  = null;
        prevEnemyHash   = 0;
        scoutProbeTag   = null;
        lastFrame       = -1;
        cumulativeConfidence.clear();
        prevAssessments = List.of();}

    @PostConstruct
    void initThresholds() {
        initThresholds(preferenceProvider.resolve(SettingsScope.root(TenancyConstants.DEFAULT_TENANT_ID)));
    }

    public void refreshThresholds() {
        initThresholds(preferenceProvider.resolve(SettingsScope.root(TenancyConstants.DEFAULT_TENANT_ID)));
    }

    void initThresholds(io.casehub.platform.api.preferences.Preferences prefs) {
        minThreatDistance                = prefs.getOrDefault(ScoutingIntelPreferences.THREAT_POSITION_MIN_DISTANCE).asDouble();
        minArmySizeDelta                 = prefs.getOrDefault(ScoutingIntelPreferences.ARMY_SIZE_MIN_DELTA).asInt();
        postureDispatchEnabled           = prefs.getOrDefault(ScoutingIntelPreferences.POSTURE_DISPATCH_ENABLED).asBoolean();
        timingAlertDispatchEnabled       = prefs.getOrDefault(ScoutingIntelPreferences.TIMING_ALERT_DISPATCH_ENABLED).asBoolean();
        buildOrderDispatchEnabled        = prefs.getOrDefault(ScoutingIntelPreferences.BUILD_ORDER_DISPATCH_ENABLED).asBoolean();
        patternAssessmentDispatchEnabled = prefs.getOrDefault(ScoutingIntelPreferences.PATTERN_ASSESSMENT_DISPATCH_ENABLED).asBoolean();
    }

    @Override public String getId()   { return "scouting.drools-cep"; }
    @Override public String getName() { return "Drools CEP Scouting"; }

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

        if (frame < lastFrame) {
            sessionManager.reset();
            scoutProbeTag    = null;
            prevEnemyHash    = 0;
            prevThreatPos    = null;
            prevArmySize     = -1;
            prevPosture      = null;
            prevTimingAlert  = null;
            prevBuildOrder   = null;
            cumulativeConfidence.clear();
            prevAssessments  = List.of();
        }
        long prevFrame = lastFrame;
        lastFrame = frame;

        long gameTimeMs = (long) (frame * (1000.0 / FRAMES_PER_SECOND));
        Point2d ourNexus      = nexusPosition(buildings);
        Point2d estimatedBase = estimatedEnemyBase(ourNexus, mapWidth);

        int currentArmySize = enemies.size();
        ctx.set(QuarkMindCaseFile.ENEMY_ARMY_SIZE, currentArmySize);
        Point2d nearest = null;
        if (!enemies.isEmpty()) {
            nearest = enemies.stream()
                .min(Comparator.comparingDouble(e -> e.position().distanceTo(ourNexus)))
                .map(Unit::position)
                .orElse(null);
        }

        boolean needsCep = broker.isSubscribed(ScoutingIntelType.BUILD_ORDER)
                        || broker.isSubscribed(ScoutingIntelType.TIMING_ALERT)
                        || broker.isSubscribed(ScoutingIntelType.POSTURE)
                        || broker.isSubscribed(ScoutingIntelType.PATTERN_ASSESSMENT)
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
                if (postureDispatchEnabled
                        && (broker.isSubscribed(ScoutingIntelType.POSTURE) || advisoryEnabled)) {
                    publishIntel(new ScoutingIntelPayload.PostureUpdate(posture));
                }
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

        // --- Pattern classification ---
        if (needsCep) {
            GameState gameState = ctx.getAs(QuarkMindCaseFile.GAME_STATE, GameState.class);
            double gameTimeMin = gameState.gameTimeMinutes();
            ctx.set(QuarkMindCaseFile.GAME_PHASE, phaseResolver.resolve(gameState).name());
            PatternClassificationRuleUnit patternData = sessionManager.buildPatternRuleUnit(gameTimeMin);
            taxonomy.activeSignatures(gameTimeMin).forEach(patternData.getSignatureStore()::add);
            try (RuleUnitInstance<PatternClassificationRuleUnit> pInstance =
                    patternRuleUnit.createInstance(patternData)) {
                pInstance.fire();
            }
            var allConf = PatternClassifier.computeAllConfidences(patternData.getEvidence());
            PatternClassifier.mergeCumulative(cumulativeConfidence, allConf, frame, prevFrame);
            long framesElapsed = prevFrame >= 0 ? frame - prevFrame : 0;
            PatternClassifier.applyRevisions(cumulativeConfidence, patternData.getRevisions(), framesElapsed);

            var assessments = PatternClassifier.allAssessments(cumulativeConfidence, frame);
            if (!assessments.isEmpty()) {
                boolean changed = assessmentsChanged(prevAssessments, assessments);
                if (changed && patternAssessmentDispatchEnabled
                        && (broker.isSubscribed(ScoutingIntelType.PATTERN_ASSESSMENT) || advisoryEnabled)) {
                    prevAssessments = assessments;
                    publishIntel(new PatternAssessmentPayload(assessments));
                }
            } else if (!prevAssessments.isEmpty()) {
                prevAssessments = List.of();
            }
        }

        if (enemies.isEmpty()) {
            maybeSendScout(frame, workers, estimatedBase);
        } else {
            scoutProbeTag = null;
        }
    }

    @Override
    public Set<String> produces() {
        return Set.of(
            QuarkMindCaseFile.ENEMY_ARMY_SIZE,
            QuarkMindCaseFile.ENEMY_BUILD_ORDER,
            QuarkMindCaseFile.TIMING_ATTACK_INCOMING,
            QuarkMindCaseFile.ENEMY_POSTURE,
            QuarkMindCaseFile.GAME_PHASE);
    }

    private void maybeSendScout(long frame, List<Unit> workers, Point2d target) {
        if (frame < SCOUT_DELAY_TICKS) return;
        if (workers.isEmpty()) return;

        if (scoutProbeTag != null) {
            boolean alive = workers.stream().anyMatch(w -> w.tag().equals(scoutProbeTag));
            if (alive) return;
            scoutProbeTag = null;
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
            broker.update(payload);
        }
        broker.level1Bus().publish(new LevelEvent<>(payload, lastFrame, LEVEL_1));
        dispatchToAdvisory(payload);
    }

    private void dispatchToAdvisory(ScoutingIntelPayload payload) {
        try {
            String content = objectMapper.writeValueAsString(
                java.util.Map.of("type", payload.getClass().getSimpleName(), "data", payload));
            messageService.dispatch(MessageDispatch.builder()
                .channelId(broker.channelId())
                .sender(getId())
                .actorType(ActorType.AGENT)
                .type(MessageType.STATUS)
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

    private static boolean crossedThreshold(double prev, double curr) {
        double[] thresholds = {0.3, 0.5, 0.7, 0.9};
        for (double t : thresholds) {
            if (prev < t && curr >= t) return true;
        }
        return false;
    }

    private static final double[] THRESHOLDS = {0.3, 0.5, 0.7, 0.9};

    static boolean assessmentsChanged(List<PatternAssessment> prev,
                                      List<PatternAssessment> curr) {
        if (prev.size() != curr.size()) {return true;}
        for (int i = 0; i < curr.size(); i++) {
            if (curr.get(i).archetype() != prev.get(i).archetype()) {return true;}
            if (crossedAnyThreshold(prev.get(i).confidence(), curr.get(i).confidence())) {return true;}
        }
        return false;
    }

    private static boolean crossedAnyThreshold(double prev, double curr) {
        for (double t : THRESHOLDS) {
            if ((prev < t) != (curr < t)) {return true;}
        }
        return false;
    }


    private static Point2d nexusPosition(List<Building> buildings) {
        return buildings.stream()
            .filter(b -> b.type() == BuildingType.NEXUS)
            .findFirst()
            .map(Building::position)
            .orElse(new Point2d(0, 0));
    }
}

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
import io.quarkmind.agent.GameSession;
import io.quarkmind.agent.PluginDecisionEvent;
import io.quarkmind.agent.QuarkMindCapabilityTag;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.ScoutingIntelBroker;
import io.quarkmind.agent.StrategyTaxonomy;
import io.quarkmind.agent.plugin.PatternAssessmentPublished;
import io.quarkmind.agent.plugin.ScoutingIntelPayload;
import io.quarkmind.agent.plugin.ScoutingIntelPayload.PatternAssessmentPayload;
import io.quarkmind.agent.plugin.ScoutingIntelPreferences;
import io.quarkmind.agent.plugin.ScoutingIntelType;
import io.quarkmind.agent.plugin.ScoutingTask;
import io.quarkmind.agent.plugin.StrategyTransitionPublished;
import io.quarkmind.domain.Building;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.MapInfo;
import io.quarkmind.domain.PatternAssessment;
import io.quarkmind.domain.PlayerEconomyStats;
import io.quarkmind.domain.PhaseResolver;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.StrategyTransition;
import io.quarkmind.domain.TransitionPath;
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
    @Inject
            Event<PatternAssessmentPublished>  patternAssessmentPublished;
    @Inject Event<StrategyTransitionPublished> strategyTransitionPublished;

    @Inject GameSession gameSession;

    @Inject ScoutingIntelBroker broker;
    @Inject CascadingPatternClassifier cascadingClassifier;
    @Inject MessageService messageService;
    @Inject ObjectMapper objectMapper;
    @Inject PreferenceProvider preferenceProvider;
    @Inject StrategyTaxonomy taxonomy;
    @Inject PhaseResolver phaseResolver;
    private final StrategyFeatureExtractor featureExtractor = new StrategyFeatureExtractor();
    private final TemporalWindowAccumulator windowAccumulator = new TemporalWindowAccumulator();


    @Inject
    @org.eclipse.microprofile.config.inject.ConfigProperty(
        name = "quarkmind.scouting.advisory.enabled", defaultValue = "true")
    boolean advisoryEnabled;

    volatile Point2d prevThreatPos   = null;
    volatile int     prevArmySize    = -1;
    volatile String  prevPosture     = null;
    volatile Boolean prevTimingAlert = null;
    volatile String  prevBuildOrder  = null;
    volatile String  cachedPosture   = "UNKNOWN";


    volatile double  minThreatDistance;
    volatile int     minArmySizeDelta;
    volatile boolean postureDispatchEnabled;
    volatile boolean timingAlertDispatchEnabled;
    volatile boolean buildOrderDispatchEnabled;
    volatile boolean patternAssessmentDispatchEnabled;
    volatile boolean llmFallbackEnabled;

    private volatile int prevEnemyHash = 0;
    private volatile String scoutProbeTag;
    private long lastFrame = -1;
    private long scoutFirstDispatchFrame = -1;


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
        prevThreatPos           = null;
        prevArmySize            = -1;
        prevPosture             = null;
        prevTimingAlert         = null;
        prevBuildOrder          = null;
        prevEnemyHash           = 0;
        scoutProbeTag           = null;
        lastFrame               = -1;
        scoutFirstDispatchFrame = -1;
        cascadingClassifier.reset();
        windowAccumulator.reset();
        prevAssessments           = List.of();
        cachedPosture             = "UNKNOWN";
    }

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
        llmFallbackEnabled               = prefs.getOrDefault(ScoutingIntelPreferences.LLM_FALLBACK_ENABLED).asBoolean();
        cascadingClassifier.setLlmFallbackConfig(
                llmFallbackEnabled,
                prefs.getOrDefault(ScoutingIntelPreferences.LLM_FALLBACK_CONFIDENCE_THRESHOLD).asDouble(),
                prefs.getOrDefault(ScoutingIntelPreferences.LLM_FALLBACK_MIN_GAME_TIME_FRAMES).asInt(),
                prefs.getOrDefault(ScoutingIntelPreferences.LLM_FALLBACK_COOLDOWN_FRAMES).asInt());
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
            cachedPosture    = "UNKNOWN";
            cascadingClassifier.reset();
            windowAccumulator.reset();
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
            GameState gameState = ctx.getAs(QuarkMindCaseFile.GAME_STATE, GameState.class);
            if (gameState != null) {
                sessionManager.processBuildings(gameState.enemyBuildings(), estimatedBase,
                                                gameState.myUnits(), buildings);
            }
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
        if (sessionManager.hasEverConfirmed()) {
            cachedPosture = sessionManager.confirmedExpansionCount() > 0 ? "MACRO" : "ALL_IN";
        } else if (data != null && !data.getPostureDecisions().isEmpty()) {
            cachedPosture = data.getPostureDecisions().get(0);
        }
        String posture = cachedPosture;
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

        // --- Pattern classification (delegated to cascade) ---
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

            windowAccumulator.addSnapshot(buildSnapshot(gameState));
            var features = featureExtractor.extract(
                    windowAccumulator.getWindowedFeatures(), MapCharacteristics.DEFAULT);
            io.quarkmind.domain.Race enemyRace = resolveEnemyRace(ctx);
            CascadeResult cascadeResult = cascadingClassifier.classify(
                    patternData.getEvidence(), patternData.getRevisions(),
                    features, enemyRace, frame, prevFrame, ctx, enemies.size());

            var assessments = cascadeResult.assessments();
            ctx.set(QuarkMindCaseFile.SCOUTING_FINAL_ASSESSMENT, assessments);
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

            if (cascadeResult.transition() != null) {
                StrategyTransition raw = cascadeResult.transition();
                TransitionPath path = taxonomy.transitionPath(raw.from(), raw.to()).orElse(null);
                StrategyTransition enriched = new StrategyTransition(
                    raw.from(), raw.to(), raw.fromConfidence(), raw.toConfidence(),
                    raw.detectedAtFrame(), path);
                ctx.set(QuarkMindCaseFile.STRATEGY_TRANSITION, enriched);
                publishIntel(new ScoutingIntelPayload.TransitionDetected(enriched));
            }
        }

        if (enemies.isEmpty()) {
            maybeSendScout(frame, workers, estimatedBase);
        } else {
            scoutProbeTag = null;
        }

        if (scoutFirstDispatchFrame >= 0
                && ctx.getAs(QuarkMindCaseFile.SCOUTING_DISPATCH_FRAME, Long.class) == null) {
            ctx.set(QuarkMindCaseFile.SCOUTING_DISPATCH_FRAME, scoutFirstDispatchFrame);
        }
    }

    @Override
    public Set<String> produces() {
        return Set.of(
            QuarkMindCaseFile.ENEMY_ARMY_SIZE,
            QuarkMindCaseFile.ENEMY_BUILD_ORDER,
            QuarkMindCaseFile.TIMING_ATTACK_INCOMING,
            QuarkMindCaseFile.ENEMY_POSTURE,
            QuarkMindCaseFile.GAME_PHASE,
            QuarkMindCaseFile.SCOUTING_FINAL_ASSESSMENT,
            QuarkMindCaseFile.STRATEGY_TRANSITION);
    }

    private void maybeSendScout(long frame, List<Unit> workers, Point2d target) {
        if (frame < SCOUT_DELAY_TICKS) {return;}
        if (workers.isEmpty()) {return;}

        if (scoutProbeTag != null) {
            boolean alive = workers.stream().anyMatch(w -> w.tag().equals(scoutProbeTag));
            if (alive) {return;}
            scoutProbeTag = null;
        }

        Unit scout = workers.get(workers.size() - 1);
        scoutProbeTag = scout.tag();
        if (scoutFirstDispatchFrame < 0) {
            scoutFirstDispatchFrame = frame;
        }
        intentQueue.add(new MoveIntent(scout.tag(), target));
        log.infof("[SCOUTING] Scout probe %s dispatched toward %s", scoutProbeTag, target);}

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
        broker.level1Bus().publish(new LevelEvent<>(payload, lastFrame, LEVEL_1, "default"));
        dispatchToAdvisory(payload);
        if (payload instanceof PatternAssessmentPayload pa && patternAssessmentPublished != null) {
            patternAssessmentPublished.fire(new PatternAssessmentPublished(pa.assessments()));
        }
        if (payload instanceof ScoutingIntelPayload.TransitionDetected td
                && strategyTransitionPublished != null) {
            strategyTransitionPublished.fire(new StrategyTransitionPublished(td.transition()));
        }
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

    static WindowSnapshot buildSnapshot(GameState gs) {
        float[] player   = new float[FeatureIndexMaps.N_TICK_FEATURES_PER_PLAYER];
        float[] opponent = new float[FeatureIndexMaps.N_TICK_FEATURES_PER_PLAYER];

        for (var b : gs.myBuildings()) {
            Integer idx = FeatureIndexMaps.BUILDING_INDEX.get(b.type());
            if (idx != null) {player[idx]++;}
        }
        for (var u : gs.myUnits()) {
            Integer idx = FeatureIndexMaps.UNIT_INDEX.get(u.type());
            if (idx != null) {player[FeatureIndexMaps.N_BUILDINGS + idx]++;}
        }
        float[] ecoArray = gs.playerEconomy().toFeatureVector();
        System.arraycopy(ecoArray, 0, player,
                         FeatureIndexMaps.N_BUILDINGS + FeatureIndexMaps.N_UNITS, ecoArray.length);
        for (int i = 0; i < FeatureIndexMaps.UPGRADE_NAMES.size(); i++) {
            if (gs.playerUpgrades().contains(FeatureIndexMaps.UPGRADE_NAMES.get(i))) {
                player[FeatureIndexMaps.N_BUILDINGS + FeatureIndexMaps.N_UNITS
                       + FeatureIndexMaps.N_STATS + i] = 1.0f;
            }
        }

        for (var b : gs.enemyBuildings()) {
            Integer idx = FeatureIndexMaps.BUILDING_INDEX.get(b.type());
            if (idx != null) {opponent[idx]++;}
        }
        for (var u : gs.enemyUnits()) {
            Integer idx = FeatureIndexMaps.UNIT_INDEX.get(u.type());
            if (idx != null) {opponent[FeatureIndexMaps.N_BUILDINGS + idx]++;}
        }
        float[] enemyEcoArray = gs.enemyEconomy().toFeatureVector();
        System.arraycopy(enemyEcoArray, 0, opponent,
                         FeatureIndexMaps.N_BUILDINGS + FeatureIndexMaps.N_UNITS, enemyEcoArray.length);
        for (int i = 0; i < FeatureIndexMaps.UPGRADE_NAMES.size(); i++) {
            if (gs.enemyUpgrades().contains(FeatureIndexMaps.UPGRADE_NAMES.get(i))) {
                opponent[FeatureIndexMaps.N_BUILDINGS + FeatureIndexMaps.N_UNITS
                         + FeatureIndexMaps.N_STATS + i] = 1.0f;
            }
        }

        // Spatial + ratio features require mapInfo
        if (gs.mapInfo() != null) {
            MapInfo map = gs.mapInfo();
            float mapDiag = (float) Math.sqrt(
                    (double) map.mapWidth() * map.mapWidth() + (double) map.mapHeight() * map.mapHeight());

            computeSpatialFeatures(player, FeatureIndexMaps.SPATIAL_OFFSET,
                                   gs.myUnits(), gs.myBuildings(), map.playerStart(), map.enemyStart(),
                                   map.mapWidth(), map.mapHeight(), mapDiag);
            computeSpatialFeatures(opponent, FeatureIndexMaps.SPATIAL_OFFSET,
                                   gs.enemyUnits(), gs.enemyBuildings(), map.enemyStart(), map.playerStart(),
                                   map.mapWidth(), map.mapHeight(), mapDiag);
        }

        computeRatioFeatures(player, FeatureIndexMaps.RATIO_OFFSET,
                             gs.myUnits(), gs.myBuildings(), gs.playerEconomy());
        computeRatioFeatures(opponent, FeatureIndexMaps.RATIO_OFFSET,
                             gs.enemyUnits(), gs.enemyBuildings(), gs.enemyEconomy());

        int uniqueEnemyTypes = (int) gs.enemyUnits().stream()
                                       .map(Unit::type).distinct().count();
        float visibility = Math.min(1.0f, uniqueEnemyTypes / 5.0f);

        return new WindowSnapshot(player, opponent, visibility);
    }

    private static void computeSpatialFeatures(
            float[] features, int offset,
            List<Unit> allUnits, List<Building> buildings,
            Point2d ownBase, Point2d enemyBase,
            int mapWidth, int mapHeight, float mapDiag) {
        List<Unit> armyUnits = allUnits.stream()
                                       .filter(u -> !SC2Data.isWorker(u.type())).toList();
        if (armyUnits.isEmpty()) {return;}
        Point2d centroid = Point2d.centroidOf(armyUnits);
        if (centroid == null) {return;}
        features[offset]     = centroid.x() / mapWidth;
        features[offset + 1] = centroid.y() / mapHeight;
        features[offset + 2] = (float) centroid.distanceTo(ownBase) / mapDiag;
        features[offset + 3] = (float) centroid.distanceTo(enemyBase) / mapDiag;
        double varX = 0, varY = 0;
        for (Unit u : armyUnits) {
            float dx = u.position().x() - centroid.x();
            float dy = u.position().y() - centroid.y();
            varX += dx * dx;
            varY += dy * dy;
        }
        varX /= armyUnits.size();
        varY /= armyUnits.size();
        features[offset + 4] = (float) Math.sqrt(varX + varY) / mapDiag;
        double maxForward = 0;
        double baseDist   = ownBase.distanceTo(enemyBase);
        for (Unit u : armyUnits) {
            double forward = baseDist - u.position().distanceTo(enemyBase);
            if (forward > maxForward) {maxForward = forward;}
        }
        features[offset + 5] = (float) maxForward / mapDiag;
        if (!buildings.isEmpty()) {
            int proxied = 0;
            for (Building b : buildings) {
                if (b.position().distanceTo(enemyBase) < b.position().distanceTo(ownBase)) {
                    proxied++;
                }
            }
            features[offset + 6] = (float) proxied / buildings.size();
        }
    }

    private static void computeRatioFeatures(
            float[] features, int offset,
            List<Unit> allUnits, List<Building> buildings,
            PlayerEconomyStats eco) {
        float armySupply = 0;
        for (Unit u : allUnits) {
            if (!SC2Data.isWorker(u.type())) {
                armySupply += SC2Data.supplyCost(u.type());
            }
        }
        float foodUsed = Math.max(eco.foodUsed() / 1000.0f, 1e-6f);
        features[offset] = armySupply / foodUsed;
        int baseCount = 0;
        for (Building b : buildings) {
            if (SC2Data.isBase(b.type())) {baseCount++;}
        }
        float satDenom = Math.max(baseCount * 16.0f, 1.0f);
        features[offset + 1] = (eco.workersActiveCount() / 1000.0f) / satDenom;
        float gasSpent = (eco.vespeneUsedCurrentArmy() + eco.vespeneUsedCurrentEconomy()
                          + eco.vespeneUsedCurrentTechnology()) / 1000.0f;
        float minSpent = (eco.mineralsUsedCurrentArmy() + eco.mineralsUsedCurrentEconomy()
                          + eco.mineralsUsedCurrentTechnology()) / 1000.0f;
        float totalSpent = Math.max(gasSpent + minSpent, 1e-6f);
        features[offset + 2] = gasSpent / totalSpent;
    }


    static io.quarkmind.domain.Race resolveEnemyRace(CaseContext ctx) {
        String raceName = ctx.getAs(QuarkMindCaseFile.ENEMY_RACE, String.class);
        if (raceName == null) return null;
        try {
            return io.quarkmind.domain.Race.valueOf(raceName);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

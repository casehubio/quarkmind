package io.quarkmind.plugin.scouting;

import io.casehub.blocks.summarisation.EventStreamBus;
import io.casehub.blocks.summarisation.LevelEvent;
import io.quarkmind.agency.context.MutableMapCaseContext;
import io.quarkmind.agent.GameSession;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.ScoutingIntelBroker;
import io.quarkmind.agent.plugin.ScoutingIntelPayload;
import io.quarkmind.agent.plugin.ScoutingIntelType;
import io.quarkmind.domain.AssessmentSource;
import io.quarkmind.domain.Building;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.PatternAssessment;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.StrategyArchetype;
import io.quarkmind.domain.Unit;
import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.IntentQueue;
import jakarta.enterprise.inject.Vetoed;
import org.drools.ruleunits.api.RuleUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for DroolsScoutingTask pure-logic methods.
 * Same package as production class to access package-private static helpers.
 */
class DroolsScoutingTaskTest {

    private DroolsScoutingTask task;
    private TestBroker broker;
    private IntentQueue intentQueue;
    private GameSession gameSession;

    @BeforeEach
    void setup() {
        // Construct dependencies manually (no CDI)
        RuleUnit<ScoutingRuleUnit> ruleUnit = mock(RuleUnit.class);
        ScoutingSessionManager sessionManager = new ScoutingSessionManager();
        intentQueue = new IntentQueue();
        gameSession = mock(GameSession.class);
        when(gameSession.id()).thenReturn(UUID.randomUUID());

        // Test broker with real EventStreamBus
        broker = new TestBroker();

        RuleUnit<PatternClassificationRuleUnit> patternRuleUnit = mock(RuleUnit.class);
        task = new DroolsScoutingTask(ruleUnit, patternRuleUnit, sessionManager, intentQueue);
        task.gameSession = gameSession;
        task.broker = broker;
        task.decisionEvents = mock(jakarta.enterprise.event.Event.class);
        task.objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        task.messageService = mock(io.casehub.qhorus.runtime.message.MessageService.class);
        task.advisoryEnabled = false; // Disable advisory to avoid CEP gate
    }

    /** Test-friendly broker that has a real EventStreamBus but minimal other state. Not a CDI bean. */
    @Vetoed
    private static class TestBroker extends ScoutingIntelBroker {
        private final EventStreamBus<ScoutingIntelPayload> testBus = new EventStreamBus<>();

        @Override
        public EventStreamBus<ScoutingIntelPayload> level1Bus() {
            return testBus;
        }

        @Override
        public boolean isSubscribed(ScoutingIntelType t) {
            // Subscribe only to passive intel (not CEP) to avoid triggering Drools rules
            return t == ScoutingIntelType.THREAT_POSITION || t == ScoutingIntelType.ARMY_SIZE;
        }

        @Override
        public UUID channelId() {
            return UUID.randomUUID();
        }
    }

    // ---- estimatedEnemyBase: SC2 map (256x256) ----

    @Test
    void estimatedEnemyBase_sc2Map_lowerLeftBase_returnsUpperRightCorner() {
        assertThat(DroolsScoutingTask.estimatedEnemyBase(new Point2d(8, 8), 256))
                .isEqualTo(new Point2d(224, 224));
    }

    @Test
    void estimatedEnemyBase_sc2Map_midBase_returnsUpperRightCorner() {
        // Threshold is mapWidth/4 = 64; base at (50,50) is in lower-left zone
        assertThat(DroolsScoutingTask.estimatedEnemyBase(new Point2d(50, 50), 256))
                .isEqualTo(new Point2d(224, 224));
    }

    @Test
    void estimatedEnemyBase_sc2Map_upperRightBase_returnsLowerLeftCorner() {
        assertThat(DroolsScoutingTask.estimatedEnemyBase(new Point2d(200, 200), 256))
                .isEqualTo(new Point2d(32, 32));
    }

    @Test
    void estimatedEnemyBase_sc2Map_aboveThresholdBase_returnsLowerLeftCorner() {
        // Equivalent to BasicScoutingTask's (100,100) → (32,32) case; threshold is 64
        assertThat(DroolsScoutingTask.estimatedEnemyBase(new Point2d(100, 100), 256))
                .isEqualTo(new Point2d(32, 32));
    }

    // ---- estimatedEnemyBase: emulated map (64x64) ----

    @Test
    void estimatedEnemyBase_emulatedMap_lowerLeftBase_returnsUpperRightCorner() {
        assertThat(DroolsScoutingTask.estimatedEnemyBase(new Point2d(8, 8), 64))
                .isEqualTo(new Point2d(56, 56));
    }

    @Test
    void estimatedEnemyBase_emulatedMap_result_isWithinMapBounds() {
        // The bug: old code returned (224,224) which the engine clamped to (63,63)
        Point2d result = DroolsScoutingTask.estimatedEnemyBase(new Point2d(8, 8), 64);
        assertThat(result.x()).isLessThan(64).isGreaterThan(0);
        assertThat(result.y()).isLessThan(64).isGreaterThan(0);
    }

    @Test
    void estimatedEnemyBase_emulatedMap_result_isNotOldClampedValue() {
        // Explicit regression against the old wrong value
        Point2d result = DroolsScoutingTask.estimatedEnemyBase(new Point2d(8, 8), 64);
        assertThat(result).isNotEqualTo(new Point2d(63, 63));
        assertThat(result).isNotEqualTo(new Point2d(224, 224));
    }

    // ---- shouldDispatchThreatPosition ----

    @Test
    void shouldDispatchThreatPosition_newPosition_exceedsZeroThreshold() {
        Point2d prev = new Point2d(10f, 10f);
        Point2d curr = new Point2d(10.1f, 10f);
        assertThat(DroolsScoutingTask.shouldDispatchThreatPosition(prev, curr, 0.0)).isTrue();
    }

    @Test
    void shouldDispatchThreatPosition_samePosition_returnsFalse() {
        Point2d pos = new Point2d(10f, 10f);
        assertThat(DroolsScoutingTask.shouldDispatchThreatPosition(pos, pos, 0.0)).isFalse();
    }

    @Test
    void shouldDispatchThreatPosition_movesBelowThreshold_returnsFalse() {
        Point2d prev = new Point2d(10f, 10f);
        Point2d curr = new Point2d(10.5f, 10f); // distance 0.5
        assertThat(DroolsScoutingTask.shouldDispatchThreatPosition(prev, curr, 1.0)).isFalse();
    }

    @Test
    void shouldDispatchThreatPosition_movesAboveThreshold_returnsTrue() {
        Point2d prev = new Point2d(10f, 10f);
        Point2d curr = new Point2d(12f, 10f); // distance 2.0
        assertThat(DroolsScoutingTask.shouldDispatchThreatPosition(prev, curr, 1.0)).isTrue();
    }

    @Test
    void shouldDispatchThreatPosition_firstSighting_prevNull_returnsTrue() {
        assertThat(DroolsScoutingTask.shouldDispatchThreatPosition(null, new Point2d(5f, 5f), 0.0))
            .isTrue();
    }

    // ---- shouldDispatchArmySize ----

    @Test
    void shouldDispatchArmySize_deltaExceedsThreshold_returnsTrue() {
        assertThat(DroolsScoutingTask.shouldDispatchArmySize(5, 10, 1)).isTrue();
    }

    @Test
    void shouldDispatchArmySize_deltaBelowThreshold_returnsFalse() {
        assertThat(DroolsScoutingTask.shouldDispatchArmySize(5, 5, 1)).isFalse();
    }

    @Test
    void shouldDispatchArmySize_deltaEqualsThreshold_returnsTrue() {
        // >= semantics: delta of exactly 1 with minDelta=1 should dispatch
        assertThat(DroolsScoutingTask.shouldDispatchArmySize(5, 6, 1)).isTrue();
    }

    // ---- L1 event stream publishing ----

    @Test
    void publishIntel_publishesToLevel1Bus() {
        List<LevelEvent<ScoutingIntelPayload>> received = new ArrayList<>();
        broker.level1Bus().subscribe(p -> true, received::add);

        // Create a game state with enemy units to trigger threat position intel
        var ctx = caseContext(List.of(enemy(10, 10)), List.of(), 100L);
        task.execute(ctx);

        // Verify L1 events were published
        assertThat(received).isNotEmpty();
        assertThat(received.get(0).payload()).isInstanceOf(ScoutingIntelPayload.class);
        assertThat(received.get(0).level().name()).isEqualTo("intel");
        assertThat(received.get(0).level().ordinal()).isEqualTo(1);
        assertThat(received.get(0).timestamp()).isEqualTo(100L);
    }

    @Test
    void publishIntel_publishesMultipleTransitions() {
        List<LevelEvent<ScoutingIntelPayload>> received = new ArrayList<>();
        broker.level1Bus().subscribe(p -> true, received::add);

        // First tick: 2 enemies
        var ctx1 = caseContext(List.of(enemy(10, 10), enemy(20, 20)), List.of(), 100L);
        task.execute(ctx1);

        int firstBatch = received.size();
        assertThat(firstBatch).isGreaterThan(0);

        // Second tick: 5 enemies (army size change)
        var ctx2 = caseContext(
            List.of(enemy(10, 10), enemy(20, 20), enemy(30, 30), enemy(40, 40), enemy(50, 50)),
            List.of(),
            200L);
        task.execute(ctx2);

        // Should have received additional events
        assertThat(received.size()).isGreaterThan(firstBatch);

        // All events should have level "intel" level 1
        assertThat(received).allMatch(e -> e.level().name().equals("intel") && e.level().ordinal() == 1);
    }

    @Test
    void assessmentsChanged_differentSize_returnsTrue() {
        var prev = List.of(new PatternAssessment(StrategyArchetype.TERRAN_MARINE_RUSH, 0.6, 100, "test", AssessmentSource.DROOLS));
        var curr = List.<PatternAssessment>of();
        assertThat(DroolsScoutingTask.assessmentsChanged(prev, curr)).isTrue();
    }

    @Test
    void assessmentsChanged_differentArchetype_returnsTrue() {
        var prev = List.of(new PatternAssessment(StrategyArchetype.TERRAN_MARINE_RUSH, 0.6, 100, "test", AssessmentSource.DROOLS));
        var curr = List.of(new PatternAssessment(StrategyArchetype.ZERG_ZERGLING_RUSH, 0.6, 200, "test", AssessmentSource.DROOLS));
        assertThat(DroolsScoutingTask.assessmentsChanged(prev, curr)).isTrue();
    }

    @Test
    void assessmentsChanged_crossesThreshold_returnsTrue() {
        var prev = List.of(new PatternAssessment(StrategyArchetype.TERRAN_MARINE_RUSH, 0.49, 100, "test", AssessmentSource.DROOLS));
        var curr = List.of(new PatternAssessment(StrategyArchetype.TERRAN_MARINE_RUSH, 0.51, 200, "test", AssessmentSource.DROOLS));
        assertThat(DroolsScoutingTask.assessmentsChanged(prev, curr)).isTrue();
    }

    @Test
    void assessmentsChanged_sameArchetypeSameBand_returnsFalse() {
        var prev = List.of(new PatternAssessment(StrategyArchetype.TERRAN_MARINE_RUSH, 0.55, 100, "test", AssessmentSource.DROOLS));
        var curr = List.of(new PatternAssessment(StrategyArchetype.TERRAN_MARINE_RUSH, 0.59, 200, "test", AssessmentSource.DROOLS));
        assertThat(DroolsScoutingTask.assessmentsChanged(prev, curr)).isFalse();
    }

    @Test
    void assessmentsChanged_bothEmpty_returnsFalse() {
        assertThat(DroolsScoutingTask.assessmentsChanged(List.of(), List.of())).isFalse();
    }


    // ---- Test helpers ----

    private MutableMapCaseContext caseContext(List<Unit> enemies, List<Unit> workers, long frame) {
        return new MutableMapCaseContext(Map.of(
            QuarkMindCaseFile.ENEMY_UNITS,  enemies,
            QuarkMindCaseFile.WORKERS,      workers,
            QuarkMindCaseFile.MY_BUILDINGS, List.of(nexus()),
            QuarkMindCaseFile.GAME_FRAME,   frame,
            QuarkMindCaseFile.READY,        Boolean.TRUE));
    }

    private Unit enemy(float x, float y) {
        return new Unit("e-" + System.nanoTime(), UnitType.ZEALOT, new Point2d(x, y), 100, 100, 50, 50, 0, 0);
    }

    private Building nexus() {
        return new Building("n-0", BuildingType.NEXUS, new Point2d(8, 8), 1500, 1500, true);
    }

// ---- LLM fallback trigger detection ----

    @Test
    void llmFallback_firesWhenAllConfidencesBelowThreshold() {
        var confidences = new java.util.EnumMap<>(Map.of(
                StrategyArchetype.PROTOSS_GATEWAY_RUSH, 0.3,
                StrategyArchetype.PROTOSS_MACRO, 0.2));

        assertThat(CascadingPatternClassifier.shouldFireLlmFallback(
                confidences, 0.5, 200, 100, -1, 50)).isTrue();
    }

    @Test
    void llmFallback_doesNotFireWhenAnyConfidenceAboveThreshold() {
        var confidences = new java.util.EnumMap<>(Map.of(
                StrategyArchetype.PROTOSS_GATEWAY_RUSH, 0.6,
                StrategyArchetype.PROTOSS_MACRO, 0.2));

        assertThat(CascadingPatternClassifier.shouldFireLlmFallback(
                confidences, 0.5, 200, 100, -1, 50)).isFalse();
    }

    @Test
    void llmFallback_doesNotFireBeforeMinGameTime() {
        var confidences = new java.util.EnumMap<>(Map.of(
                StrategyArchetype.PROTOSS_MACRO, 0.2));

        assertThat(CascadingPatternClassifier.shouldFireLlmFallback(
                confidences, 0.5, 50, 100, -1, 50)).isFalse();
    }

    @Test
    void llmFallback_doesNotFireDuringCooldown() {
        var confidences = new java.util.EnumMap<>(Map.of(
                StrategyArchetype.PROTOSS_MACRO, 0.2));

        assertThat(CascadingPatternClassifier.shouldFireLlmFallback(
                confidences, 0.5, 200, 100, 180, 50)).isFalse();
    }

    @Test
    void llmFallback_firesWhenCumulativeConfidenceMapIsEmpty() {
        var confidences = new java.util.EnumMap<StrategyArchetype, Double>(StrategyArchetype.class);

        assertThat(CascadingPatternClassifier.shouldFireLlmFallback(
                confidences, 0.5, 200, 100, -1, 50)).isTrue();
    }

    @Test
    void llmFallback_firesAfterCooldownExpires() {
        var confidences = new java.util.EnumMap<>(Map.of(
                StrategyArchetype.PROTOSS_MACRO, 0.2));

        assertThat(CascadingPatternClassifier.shouldFireLlmFallback(
                confidences, 0.5, 300, 100, 200, 50)).isTrue();
    }

// ---- LLM fallback result integration ----

    @Test
    void llmFallback_readsResultAndOverridesCumulativeConfidence() {
        MutableMapCaseContext ctx = new MutableMapCaseContext(new java.util.HashMap<>());
        ctx.set(QuarkMindCaseFile.LLM_FALLBACK_ARCHETYPE, "PROTOSS_GATEWAY_RUSH");
        ctx.set(QuarkMindCaseFile.LLM_FALLBACK_CONFIDENCE, "0.8");
        ctx.set(QuarkMindCaseFile.LLM_FALLBACK_RATIONALE, "Early zealot pressure detected");

        var cumulative = new java.util.EnumMap<StrategyArchetype, Double>(StrategyArchetype.class);
        cumulative.put(StrategyArchetype.PROTOSS_MACRO, 0.2);

        CascadingPatternClassifier.processLlmFallbackResult(ctx, cumulative, null);

        assertThat(cumulative.get(StrategyArchetype.PROTOSS_GATEWAY_RUSH)).isEqualTo(0.8);
        assertThat(ctx.get(QuarkMindCaseFile.LLM_FALLBACK_ARCHETYPE)).isNull();
    }

    @Test
    void llmFallback_doesNotReprocessSameArchetype() {
        MutableMapCaseContext ctx = new MutableMapCaseContext(new java.util.HashMap<>());
        ctx.set(QuarkMindCaseFile.LLM_FALLBACK_ARCHETYPE, "PROTOSS_GATEWAY_RUSH");
        ctx.set(QuarkMindCaseFile.LLM_FALLBACK_CONFIDENCE, "0.8");
        ctx.set(QuarkMindCaseFile.LLM_FALLBACK_RATIONALE, "test");

        var cumulative = new java.util.EnumMap<StrategyArchetype, Double>(StrategyArchetype.class);

        String result = CascadingPatternClassifier.processLlmFallbackResult(
                ctx, cumulative, "PROTOSS_GATEWAY_RUSH");

        assertThat(result).isEqualTo("PROTOSS_GATEWAY_RUSH");
        assertThat(cumulative).doesNotContainKey(StrategyArchetype.PROTOSS_GATEWAY_RUSH);
    }

    @Test
    void llmFallback_invalidArchetype_clearsKeysWithoutOverride() {
        MutableMapCaseContext ctx = new MutableMapCaseContext(new java.util.HashMap<>());
        ctx.set(QuarkMindCaseFile.LLM_FALLBACK_ARCHETYPE, "INVALID_ARCHETYPE");
        ctx.set(QuarkMindCaseFile.LLM_FALLBACK_CONFIDENCE, "0.8");
        ctx.set(QuarkMindCaseFile.LLM_FALLBACK_RATIONALE, "test");

        var cumulative = new java.util.EnumMap<StrategyArchetype, Double>(StrategyArchetype.class);

        CascadingPatternClassifier.processLlmFallbackResult(ctx, cumulative, null);

        assertThat(cumulative).isEmpty();
        assertThat(ctx.get(QuarkMindCaseFile.LLM_FALLBACK_ARCHETYPE)).isNull();
    }

    @Test
    void llmFallback_noResult_returnsLastProcessed() {
        MutableMapCaseContext ctx = new MutableMapCaseContext(new java.util.HashMap<>());

        var cumulative = new java.util.EnumMap<StrategyArchetype, Double>(StrategyArchetype.class);

        String result = CascadingPatternClassifier.processLlmFallbackResult(
                ctx, cumulative, "PREVIOUS");

        assertThat(result).isEqualTo("PREVIOUS");
    }

    // ---- buildSnapshot ----

    @Test
    void buildSnapshot_populatesPlayerBuildingFeatures() {
        var gs = gameState(
            List.of(),
            List.of(nexus(), new Building("f-1", BuildingType.FORGE, new Point2d(10, 10), 400, 400, true)),
            List.of(),
            List.of());
        var snap = DroolsScoutingTask.buildSnapshot(gs);
        assertThat(snap.playerFeatures()[FeatureIndexMaps.BUILDING_INDEX.get(BuildingType.NEXUS)])
            .isEqualTo(1.0f);
        assertThat(snap.playerFeatures()[FeatureIndexMaps.BUILDING_INDEX.get(BuildingType.FORGE)])
            .isEqualTo(1.0f);
    }

    @Test
    void buildSnapshot_populatesPlayerUnitFeatures() {
        var gs = gameState(
            List.of(new Unit("z-1", UnitType.ZEALOT, new Point2d(5, 5), 100, 100, 0, 0, 0, 0),
                    new Unit("z-2", UnitType.ZEALOT, new Point2d(6, 6), 100, 100, 0, 0, 0, 0)),
            List.of(),
            List.of(),
            List.of());
        var snap = DroolsScoutingTask.buildSnapshot(gs);
        int zealotIdx = FeatureIndexMaps.N_BUILDINGS + FeatureIndexMaps.UNIT_INDEX.get(UnitType.ZEALOT);
        assertThat(snap.playerFeatures()[zealotIdx]).isEqualTo(2.0f);
    }

    @Test
    void buildSnapshot_populatesOpponentFeatures() {
        var gs = gameState(
            List.of(),
            List.of(),
            List.of(enemy(10, 10)),
            List.of());
        var snap = DroolsScoutingTask.buildSnapshot(gs);
        int zealotIdx = FeatureIndexMaps.N_BUILDINGS + FeatureIndexMaps.UNIT_INDEX.get(UnitType.ZEALOT);
        assertThat(snap.opponentFeatures()[zealotIdx]).isEqualTo(1.0f);
    }

    @Test
    void buildSnapshot_scoutingVisibility_scalesWithUniqueTypes() {
        var gs = gameState(
            List.of(),
            List.of(),
            List.of(enemy(10, 10)),
            List.of());
        var snap = DroolsScoutingTask.buildSnapshot(gs);
        assertThat(snap.scoutingVisibility()).isCloseTo(0.2f, org.assertj.core.data.Offset.offset(0.01f));
    }

    @Test
    void buildSnapshot_featureVectorLength() {
        var gs = gameState(List.of(), List.of(), List.of(), List.of());
        var snap = DroolsScoutingTask.buildSnapshot(gs);
        assertThat(snap.playerFeatures()).hasSize(FeatureIndexMaps.N_TICK_FEATURES_PER_PLAYER);
        assertThat(snap.opponentFeatures()).hasSize(FeatureIndexMaps.N_TICK_FEATURES_PER_PLAYER);
    }

    @Test
    void buildSnapshot_featureVectorLength_144perPlayer() {
        var gs   = gameStateWithMap(List.of(), List.of(), List.of(), List.of());
        var snap = DroolsScoutingTask.buildSnapshot(gs);
        assertThat(snap.playerFeatures()).hasSize(FeatureIndexMaps.N_TICK_FEATURES_PER_PLAYER);
        assertThat(snap.opponentFeatures()).hasSize(FeatureIndexMaps.N_TICK_FEATURES_PER_PLAYER);
    }

    @Test
    void buildSnapshot_spatialFeatures_armyCentroid() {
        var myUnits = List.of(
                new Unit("u1", UnitType.ZEALOT, new Point2d(50, 50), 100, 100, 50, 50, 0, 0),
                new Unit("u2", UnitType.STALKER, new Point2d(70, 70), 80, 80, 80, 80, 0, 0)
                             );
        var gs   = gameStateWithMap(myUnits, List.of(), List.of(), List.of());
        var snap = DroolsScoutingTask.buildSnapshot(gs);
        int off  = FeatureIndexMaps.SPATIAL_OFFSET;
        // centroid = (60, 60), map = 150x150
        assertThat(snap.playerFeatures()[off]).as("centroid_x").isCloseTo(60f / 150f, org.assertj.core.data.Offset.offset(1e-5f));
        assertThat(snap.playerFeatures()[off + 1]).as("centroid_y").isCloseTo(60f / 150f, org.assertj.core.data.Offset.offset(1e-5f));
    }

    @Test
    void buildSnapshot_spatialFeatures_distancesToBases() {
        var myUnits = List.of(
                new Unit("u1", UnitType.ZEALOT, new Point2d(75, 75), 100, 100, 50, 50, 0, 0)
                             );
        var   gs        = gameStateWithMap(myUnits, List.of(), List.of(), List.of());
        var   snap      = DroolsScoutingTask.buildSnapshot(gs);
        int   off       = FeatureIndexMaps.SPATIAL_OFFSET;
        float mapDiag   = (float) Math.sqrt(150.0 * 150 + 150.0 * 150);
        float distOwn   = (float) new Point2d(75, 75).distanceTo(new Point2d(30, 30)) / mapDiag;
        float distEnemy = (float) new Point2d(75, 75).distanceTo(new Point2d(120, 120)) / mapDiag;
        assertThat(snap.playerFeatures()[off + 2]).as("dist_own_base").isCloseTo(distOwn, org.assertj.core.data.Offset.offset(1e-5f));
        assertThat(snap.playerFeatures()[off + 3]).as("dist_enemy_base").isCloseTo(distEnemy, org.assertj.core.data.Offset.offset(1e-5f));
    }

    @Test
    void buildSnapshot_spatialFeatures_noArmyUnits_allZeros() {
        // Only workers — no army units, spatial should be all zeros
        var myUnits = List.of(
                new Unit("w1", UnitType.PROBE, new Point2d(50, 50), 20, 20, 20, 20, 0, 0)
                             );
        var gs   = gameStateWithMap(myUnits, List.of(), List.of(), List.of());
        var snap = DroolsScoutingTask.buildSnapshot(gs);
        int off  = FeatureIndexMaps.SPATIAL_OFFSET;
        for (int i = 0; i < FeatureIndexMaps.N_SPATIAL; i++) {
            assertThat(snap.playerFeatures()[off + i]).as("spatial[%d]", i).isEqualTo(0f);
        }
    }

    @Test
    void buildSnapshot_spatialFeatures_proxyBuildingScore() {
        // Building at (110, 110) — closer to enemy (120,120) than own (30,30)
        var myBuildings = List.of(
                new Building("b1", BuildingType.PYLON, new Point2d(110, 110), 200, 200, true),
                new Building("b2", BuildingType.GATEWAY, new Point2d(35, 35), 500, 500, true)
                                 );
        var gs = gameStateWithMap(List.of(new Unit("u1", UnitType.ZEALOT, new Point2d(50, 50), 100, 100, 50, 50, 0, 0)),
                                  myBuildings, List.of(), List.of());
        var snap = DroolsScoutingTask.buildSnapshot(gs);
        int off  = FeatureIndexMaps.SPATIAL_OFFSET;
        // 1 of 2 buildings proxied = 0.5
        assertThat(snap.playerFeatures()[off + 6]).as("proxy_building_score").isCloseTo(0.5f, org.assertj.core.data.Offset.offset(1e-5f));
    }

    @Test
    void buildSnapshot_ratioFeatures_armySupplyRatio() {
        // 2 zealots (2 supply each) + 1 probe (worker, excluded) = army supply 4
        // foodUsed in economy = 6000 (pre-scaled /1000 = 6)
        var eco = new io.quarkmind.domain.PlayerEconomyStats(400, 100, 1000, 300, 8000, 6000, 3000, 0, 0, 0, 0, 0, 0);
        var myUnits = List.of(
                new Unit("u1", UnitType.ZEALOT, new Point2d(50, 50), 100, 100, 50, 50, 0, 0),
                new Unit("u2", UnitType.ZEALOT, new Point2d(60, 60), 100, 100, 50, 50, 0, 0),
                new Unit("w1", UnitType.PROBE, new Point2d(30, 30), 20, 20, 20, 20, 0, 0)
                             );
        var gs = new io.quarkmind.domain.GameState(400, 100, 46, 38, myUnits, List.of(), List.of(), List.of(),
                                                   List.of(), List.of(), List.of(), 5000,
                                                   new io.quarkmind.domain.MapInfo(new Point2d(30, 30), new Point2d(120, 120), 150, 150, List.of(), List.of(), List.of()),
                                                   eco, io.quarkmind.domain.PlayerEconomyStats.EMPTY, java.util.Set.of(), java.util.Set.of());
        var snap = DroolsScoutingTask.buildSnapshot(gs);
        int off  = FeatureIndexMaps.RATIO_OFFSET;
        // army supply = 4, foodUsed = 6000/1000 = 6.0 → ratio = 4/6 ≈ 0.667
        assertThat(snap.playerFeatures()[off]).as("army_supply_ratio").isCloseTo(4f / 6f, org.assertj.core.data.Offset.offset(1e-3f));
    }

    @Test
    void buildSnapshot_nullMapInfo_spatialAllZeros() {
        // Backward compatibility: null mapInfo should produce zero spatial features
        var myUnits = List.of(new Unit("u1", UnitType.ZEALOT, new Point2d(50, 50), 100, 100, 50, 50, 0, 0));
        var gs      = gameState(myUnits, List.of(), List.of(), List.of());
        var snap    = DroolsScoutingTask.buildSnapshot(gs);
        int off     = FeatureIndexMaps.SPATIAL_OFFSET;
        for (int i = 0; i < FeatureIndexMaps.N_SPATIAL; i++) {
            assertThat(snap.playerFeatures()[off + i]).as("spatial[%d] with null mapInfo", i).isEqualTo(0f);
        }
    }


    // ---- resolveEnemyRace ----

    @Test
    void resolveEnemyRace_validRace_returnsRace() {
        var ctx = new MutableMapCaseContext(Map.of(QuarkMindCaseFile.ENEMY_RACE, "PROTOSS"));
        assertThat(DroolsScoutingTask.resolveEnemyRace(ctx))
            .isEqualTo(io.quarkmind.domain.Race.PROTOSS);
    }

    @Test
    void resolveEnemyRace_noKey_returnsNull() {
        var ctx = new MutableMapCaseContext(Map.of());
        assertThat(DroolsScoutingTask.resolveEnemyRace(ctx)).isNull();
    }

    @Test
    void resolveEnemyRace_invalidValue_returnsNull() {
        var ctx = new MutableMapCaseContext(Map.of(QuarkMindCaseFile.ENEMY_RACE, "INVALID"));
        assertThat(DroolsScoutingTask.resolveEnemyRace(ctx)).isNull();
    }

    // ---- Helpers for buildSnapshot tests ----

    private io.quarkmind.domain.GameState gameState(
            List<Unit> myUnits, List<Building> myBuildings,
            List<Unit> enemyUnits, List<Building> enemyBuildings) {
        return new io.quarkmind.domain.GameState(
            400, 200, 46, 38,
            myUnits, myBuildings, enemyUnits, enemyBuildings,
            List.of(), List.of(), List.of(), 5000, null,
            io.quarkmind.domain.PlayerEconomyStats.EMPTY,
            io.quarkmind.domain.PlayerEconomyStats.EMPTY,
            java.util.Set.of(), java.util.Set.of());
    }

    private io.quarkmind.domain.GameState gameStateWithMap(
            List<Unit> myUnits, List<Building> myBuildings,
            List<Unit> enemyUnits, List<Building> enemyBuildings) {
        return new io.quarkmind.domain.GameState(
                400, 200, 46, 38,
                myUnits, myBuildings, enemyUnits, enemyBuildings,
                List.of(), List.of(), List.of(), 5000,
                new io.quarkmind.domain.MapInfo(new Point2d(30, 30), new Point2d(120, 120), 150, 150, List.of(), List.of(), List.of()),
                io.quarkmind.domain.PlayerEconomyStats.EMPTY,
                io.quarkmind.domain.PlayerEconomyStats.EMPTY,
                java.util.Set.of(), java.util.Set.of());
    }

}

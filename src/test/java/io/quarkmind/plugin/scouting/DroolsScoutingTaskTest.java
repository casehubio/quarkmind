package io.quarkmind.plugin.scouting;

import io.casehub.blocks.summarisation.EventStreamBus;
import io.casehub.blocks.summarisation.LevelEvent;
import io.quarkmind.agent.GameSession;
import io.quarkmind.agent.MutableMapCaseContext;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.ScoutingIntelBroker;
import io.quarkmind.agent.plugin.ScoutingIntelPayload;
import io.quarkmind.agent.plugin.ScoutingIntelType;
import io.quarkmind.domain.Building;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.Unit;
import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.IntentQueue;
import org.drools.ruleunits.api.RuleUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.enterprise.inject.Vetoed;

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

        task = new DroolsScoutingTask(ruleUnit, sessionManager, intentQueue);
        task.gameSession = gameSession;
        task.broker = broker;
        task.decisionEvents = mock(jakarta.enterprise.event.Event.class);
        task.postureClassified = mock(jakarta.enterprise.event.Event.class);
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
}

package io.quarkmind.plugin;

import io.casehub.qhorus.api.store.MessageStore;
import io.quarkmind.agency.context.MutableMapCaseContext;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.ScoutingIntelBroker;
import io.quarkmind.agent.plugin.ScoutingIntelPayload;
import io.quarkmind.agent.plugin.ScoutingIntelType;
import io.quarkmind.domain.Building;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.MapInfo;
import io.quarkmind.domain.PlayerEconomyStats;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.Unit;
import io.quarkmind.domain.UnitType;
import io.quarkmind.plugin.scouting.DroolsScoutingTask;
import io.quarkmind.plugin.scouting.ScoutingSessionManager;
import io.quarkmind.sc2.IntentQueue;
import io.quarkmind.sc2.intent.MoveIntent;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration tests for DroolsScoutingTask.
 */
@QuarkusTest
class DroolsScoutingTaskIT {

    @Inject DroolsScoutingTask scoutingTask;
    @Inject IntentQueue intentQueue;
    @Inject ScoutingSessionManager sessionManager;
    @Inject ScoutingIntelBroker broker;
    @Inject MessageStore messageStore;

    @BeforeEach @AfterEach
    void reset() {
        scoutingTask.resetDispatchState();
        intentQueue.drainAll();
        sessionManager.reset();
        broker.clearLatest();
    }

    // ---- Passive intel ----

    @Test
    void writesArmySizeEachTick() {
        var ctx = caseContext(List.of(enemy(10, 10), enemy(20, 20)), List.of(), 100L);
        scoutingTask.execute(ctx);
        assertThat(ctx.getAs(QuarkMindCaseFile.ENEMY_ARMY_SIZE, Integer.class)).isEqualTo(2);
    }

    @Test
    void doesNotWriteNearestThreatToCaseFile() {
        // NEAREST_THREAT removed (#179) — intel now flows via broker (Stack 1)
        var ctx = caseContext(List.of(enemy(10, 10), enemy(100, 100)), List.of(), 100L);
        scoutingTask.execute(ctx);
        assertThat(broker.current(io.quarkmind.agent.plugin.ScoutingIntelType.THREAT_POSITION)).isPresent();
    }

    // ---- CEP keys written each tick ----

    @Test
    void timingAttackFalseWhenNoArmyNearBase() {
        var ctx = caseContext(List.of(enemy(200, 200)), List.of(), 100L);
        scoutingTask.execute(ctx);
        assertThat(ctx.getAs(QuarkMindCaseFile.TIMING_ATTACK_INCOMING, Boolean.class))
            .isEqualTo(Boolean.FALSE);
    }

    @Test
    void postureUnknownWhenNoEnemiesEverSeen() {
        var ctx = caseContext(List.of(), List.of(), 100L);
        scoutingTask.execute(ctx);
        assertThat(ctx.getAs(QuarkMindCaseFile.ENEMY_POSTURE, String.class))
            .isEqualTo("UNKNOWN");
    }

    @Test
    void buildOrderUnknownWhenNoEnemiesEverSeen() {
        var ctx = caseContext(List.of(), List.of(), 100L);
        scoutingTask.execute(ctx);
        assertThat(ctx.getAs(QuarkMindCaseFile.ENEMY_BUILD_ORDER, String.class))
            .isEqualTo("UNKNOWN");
    }

    @Test
    void buildOrderDetectedAfterEnoughSightings() {
        // Execute 6 ticks with unique ROACH tags — accumulates in buffer
        for (int i = 0; i < 6; i++) {
            var ctx = caseContext(
                List.of(new Unit("r-" + i, UnitType.ROACH, new Point2d(200, 200), 100, 100, 0, 0, 0, 0)),
                List.of(),
                (long)(i + 1) * 500);
            scoutingTask.execute(ctx);
        }
        var finalCtx = caseContext(List.of(), List.of(), 6 * 500L);
        scoutingTask.execute(finalCtx);
        assertThat(finalCtx.getAs(QuarkMindCaseFile.ENEMY_BUILD_ORDER, String.class))
            .isEqualTo("ZERG_ROACH_RUSH");
    }

    @Test
    void scoutProbeDispatchedAfterDelay() {
        var ctx = caseContext(List.of(), List.of(probe("p-0")),
            (long) DroolsScoutingTask.SCOUT_DELAY_TICKS);
        scoutingTask.execute(ctx);
        assertThat(intentQueue.pending())
            .hasSize(1)
            .first().isInstanceOf(MoveIntent.class);
    }

    @Test
    void scoutDispatchedToEstimatedSC2Base() {
        // Regression guard: default map width (256) must still target the SC2 far corner.
        // nexus at (8,8) → estimated enemy base = (224,224).
        // Uses a distinct probe tag to avoid scoutProbeTag state from other tests.
        var ctx = caseContext(List.of(), List.of(probe("sc-guard-probe")),
            (long) DroolsScoutingTask.SCOUT_DELAY_TICKS);
        scoutingTask.execute(ctx);
        assertThat(intentQueue.pending()).hasSize(1);
        MoveIntent move = (MoveIntent) intentQueue.pending().get(0);
        assertThat(move.targetLocation()).isEqualTo(new Point2d(224, 224));
    }

    // ---- Stack 1: broker population ----

    @Test
    void execute_populatesBrokerThreatPosition_whenEnemiesPresent() {
        var ctx = caseContext(List.of(enemy(10, 10)), List.of(), 100L);
        scoutingTask.execute(ctx);
        assertThat(broker.current(ScoutingIntelType.THREAT_POSITION,
                ScoutingIntelPayload.ThreatPosition.class))
            .isPresent();
    }

    @Test
    void execute_brokerThreatPositionEmpty_whenNoEnemies() {
        var ctx = caseContext(List.of(), List.of(), 100L);
        scoutingTask.execute(ctx);
        assertThat(broker.current(ScoutingIntelType.THREAT_POSITION)).isEmpty();
    }

    // ---- Stack 2: Qhorus advisory channel ----

    @Test
    void execute_publishesBothBrokerAndAdvisoryChannel_whenThreatsPresent() {
        // Verify Stack 1 (broker) AND Stack 2 (Qhorus advisory) both receive the intel
        int messagesBefore = messageStore.countByChannel(broker.channelId());
        var ctx = caseContext(List.of(enemy(10, 10)), List.of(), 100L);
        scoutingTask.execute(ctx);

        // Stack 1: broker has the threat position
        assertThat(broker.current(ScoutingIntelType.THREAT_POSITION,
                ScoutingIntelPayload.ThreatPosition.class))
            .isPresent();

        // Stack 2: Qhorus advisory channel received at least one STATUS message
        assertThat(messageStore.countByChannel(broker.channelId()))
            .isGreaterThan(messagesBefore);
    }

    // ---- Helpers ----


    @Test
    void posturePersistsAfterBufferEviction() {
        // Tick 1: see enemy near enemy base (no expansion) → ALL_IN
        var ctx1 = caseContext(List.of(enemy(200, 200)), List.of(), 100L);
        scoutingTask.execute(ctx1);
        assertThat(ctx1.getAs(QuarkMindCaseFile.ENEMY_POSTURE, String.class))
                .isEqualTo("ALL_IN");

        // Tick 2: 4 minutes later, no enemies visible → buffer evicted
        long fourMinFrames = (long) (4L * 60 * SC2Data.GAME_LOOPS_PER_SECOND);
        var  ctx2          = caseContext(List.of(), List.of(), fourMinFrames);
        scoutingTask.execute(ctx2);

        // Posture should persist as ALL_IN, not revert to UNKNOWN
        assertThat(ctx2.getAs(QuarkMindCaseFile.ENEMY_POSTURE, String.class))
                .isEqualTo("ALL_IN");
    }

    @Test
    void postureTransitionsToMacroAfterEviction() {
        // Tick 1: enemy near enemy base, no expansion → ALL_IN
        var ctx1 = caseContext(List.of(enemy(200, 200)), List.of(), 100L);
        scoutingTask.execute(ctx1);
        assertThat(ctx1.getAs(QuarkMindCaseFile.ENEMY_POSTURE, String.class))
                .isEqualTo("ALL_IN");

        // Tick 2: 4 minutes later, enemy far from their base (expansion signal)
        long fourMinFrames = (long) (4L * 60 * SC2Data.GAME_LOOPS_PER_SECOND);
        var ctx2 = caseContext(
                List.of(new Unit("e-exp", UnitType.ZEALOT, new Point2d(100, 100), 100, 100, 50, 50, 0, 0)),
                List.of(), fourMinFrames);
        scoutingTask.execute(ctx2);

        // Expansion detected → MACRO overrides cached ALL_IN
        assertThat(ctx2.getAs(QuarkMindCaseFile.ENEMY_POSTURE, String.class))
                .isEqualTo("MACRO");
    }

    @Test
    void postureResetsOnGameRestart() {
        // Tick 1: classify as ALL_IN
        var ctx1 = caseContext(List.of(enemy(200, 200)), List.of(), 100L);
        scoutingTask.execute(ctx1);
        assertThat(ctx1.getAs(QuarkMindCaseFile.ENEMY_POSTURE, String.class))
                .isEqualTo("ALL_IN");

        // Reset (simulates game restart)
        scoutingTask.resetDispatchState();
        sessionManager.reset();

        // Tick 2: no enemies → should be UNKNOWN (fresh game)
        var ctx2 = caseContext(List.of(), List.of(), 100L);
        scoutingTask.execute(ctx2);
        assertThat(ctx2.getAs(QuarkMindCaseFile.ENEMY_POSTURE, String.class))
                .isEqualTo("UNKNOWN");
    }


    private MutableMapCaseContext caseContext(List<Unit> enemies, List<Unit> workers, long frame) {
        var map = new java.util.HashMap<String, Object>();
        map.put(QuarkMindCaseFile.ENEMY_UNITS, enemies);
        map.put(QuarkMindCaseFile.WORKERS, workers);
        map.put(QuarkMindCaseFile.MY_BUILDINGS, List.of(nexus()));
        map.put(QuarkMindCaseFile.GAME_FRAME, frame);
        map.put(QuarkMindCaseFile.READY, Boolean.TRUE);
        map.put(QuarkMindCaseFile.GAME_STATE, gameState(enemies, frame));
        return new MutableMapCaseContext(map);
    }

    private Unit enemy(float x, float y) {
        return new Unit("e-" + System.nanoTime(), UnitType.ZEALOT, new Point2d(x, y), 100, 100, 50, 50, 0, 0);
    }

    private Unit probe(String tag) {
        return new Unit(tag, UnitType.PROBE, new Point2d(9, 9), 45, 45, 20, 20, 0, 0);
    }

    private Building nexus() {
        return new Building("n-0", BuildingType.NEXUS, new Point2d(8, 8), 1500, 1500, true);
    }

    private GameState gameState(List<Unit> enemies, long frame) {
        return new GameState(
                200, 0, 15, 6,
                List.of(), List.of(nexus()),
                enemies, List.of(), List.of(),
                List.of(), List.of(),
                frame,
                new MapInfo(new Point2d(8, 8), new Point2d(224, 224), 256, 256, List.of(), List.of(), List.of()),
                PlayerEconomyStats.EMPTY, PlayerEconomyStats.EMPTY,
                java.util.Set.of(), java.util.Set.of()
        );
    }

}

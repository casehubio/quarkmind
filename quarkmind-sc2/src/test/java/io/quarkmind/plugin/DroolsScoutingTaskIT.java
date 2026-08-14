package io.quarkmind.plugin;

import io.casehub.qhorus.api.store.MessageStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import io.quarkmind.agent.MutableMapCaseContext;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.ScoutingIntelBroker;
import io.quarkmind.agent.plugin.ScoutingIntelPayload;
import io.quarkmind.agent.plugin.ScoutingIntelType;
import io.quarkmind.domain.*;
import io.quarkmind.plugin.scouting.DroolsScoutingTask;
import io.quarkmind.plugin.scouting.ScoutingSessionManager;
import io.quarkmind.sc2.IntentQueue;
import io.quarkmind.sc2.intent.MoveIntent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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

    private MutableMapCaseContext caseContext(List<Unit> enemies, List<Unit> workers, long frame) {
        return new MutableMapCaseContext(Map.of(
            QuarkMindCaseFile.ENEMY_UNITS, enemies,
            QuarkMindCaseFile.WORKERS, workers,
            QuarkMindCaseFile.MY_BUILDINGS, List.of(nexus()),
            QuarkMindCaseFile.GAME_FRAME, frame,
            QuarkMindCaseFile.READY, Boolean.TRUE
        ));
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
}

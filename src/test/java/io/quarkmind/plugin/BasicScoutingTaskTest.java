package io.quarkmind.plugin;

import io.quarkmind.agent.MutableMapCaseContext;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.domain.*;
import io.quarkmind.sc2.IntentQueue;
import io.quarkmind.sc2.intent.MoveIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BasicScoutingTaskTest {

    IntentQueue intentQueue;
    BasicScoutingTask task;

    @BeforeEach
    void setUp() {
        intentQueue = new IntentQueue();
        task = new BasicScoutingTask(intentQueue);
    }

    // --- Passive intel ---

    @Test
    void writesZeroArmySizeWhenNoEnemiesVisible() {
        var ctx = caseContext(List.of(), List.of(nexus()), List.of(probe("p-0")), 0L);
        task.execute(ctx);
        assertThat(ctx.getAs(QuarkMindCaseFile.ENEMY_ARMY_SIZE, Integer.class)).isEqualTo(0);
    }

    @Test
    void writesCorrectArmySizeWhenEnemiesPresent() {
        var ctx = caseContext(List.of(enemy(10, 10), enemy(20, 20)), List.of(nexus()), List.of(), 0L);
        task.execute(ctx);
        assertThat(ctx.getAs(QuarkMindCaseFile.ENEMY_ARMY_SIZE, Integer.class)).isEqualTo(2);
    }

    @Test
    void writesArmySizeEvenWithEnemiesPresent() {
        // NEAREST_THREAT removed (#179); verify army size is still written
        var ctx = caseContext(List.of(enemy(10, 10), enemy(20, 20)), List.of(nexus()), List.of(), 0L);
        task.execute(ctx);
        assertThat(ctx.getAs(QuarkMindCaseFile.ENEMY_ARMY_SIZE, Integer.class)).isEqualTo(2);
    }

    // --- Active scouting ---

    @Test
    void doesNotSendScoutBeforeDelay() {
        var ctx = caseContext(List.of(), List.of(nexus()), List.of(probe("p-0")), 0L);
        task.execute(ctx);
        assertThat(intentQueue.pending()).isEmpty();
    }

    @Test
    void sendsScoutAfterDelayWhenNoEnemiesVisible() {
        var ctx = caseContext(List.of(), List.of(nexus()), List.of(probe("p-0")),
            (long) BasicScoutingTask.SCOUT_DELAY_TICKS);
        task.execute(ctx);
        assertThat(intentQueue.pending())
            .hasSize(1)
            .first().isInstanceOf(MoveIntent.class);
    }

    @Test
    void scoutTargetsEstimatedEnemyBase() {
        var ctx = caseContext(List.of(), List.of(nexus()), List.of(probe("p-0")),
            (long) BasicScoutingTask.SCOUT_DELAY_TICKS);
        task.execute(ctx);
        MoveIntent move = (MoveIntent) intentQueue.pending().get(0);
        // Nexus at (8,8) → enemy estimated at (224,224)
        assertThat(move.targetLocation()).isEqualTo(new Point2d(224, 224));
    }

    @Test
    void doesNotSendSecondScoutIfFirstStillAlive() {
        var ctx = caseContext(List.of(), List.of(nexus()), List.of(probe("p-0")),
            (long) BasicScoutingTask.SCOUT_DELAY_TICKS);
        task.execute(ctx); // assigns scout
        intentQueue.drainAll();

        task.execute(ctx); // same probe still alive — no new intent
        assertThat(intentQueue.pending()).isEmpty();
    }

    @Test
    void assignsNewScoutIfPreviousDied() {
        var ctx = caseContext(List.of(), List.of(nexus()), List.of(probe("p-0")),
            (long) BasicScoutingTask.SCOUT_DELAY_TICKS);
        task.execute(ctx); // assigns p-0 as scout
        intentQueue.drainAll();

        // p-0 is gone — only p-1 remains
        var ctx2 = caseContext(List.of(), List.of(nexus()), List.of(probe("p-1")),
            (long) BasicScoutingTask.SCOUT_DELAY_TICKS + 1);
        task.execute(ctx2);
        assertThat(intentQueue.pending()).hasSize(1);
        assertThat(((MoveIntent) intentQueue.pending().get(0)).unitTag()).isEqualTo("p-1");
    }

    @Test
    void releasesScoutWhenEnemiesFound() {
        var ctx = caseContext(List.of(), List.of(nexus()), List.of(probe("p-0")),
            (long) BasicScoutingTask.SCOUT_DELAY_TICKS);
        task.execute(ctx); // assigns scout
        intentQueue.drainAll();

        // Enemies appear → scout released
        var ctx2 = caseContext(List.of(enemy(20, 20)), List.of(nexus()), List.of(probe("p-0")),
            (long) BasicScoutingTask.SCOUT_DELAY_TICKS + 1);
        task.execute(ctx2);
        intentQueue.drainAll();

        // No enemies again → should assign a fresh scout
        task.execute(ctx);
        assertThat(intentQueue.pending()).hasSize(1);
    }

    // --- estimatedEnemyBase ---

    @Test
    void estimatesLowerLeftEnemyFromUpperRightBase() {
        assertThat(BasicScoutingTask.estimatedEnemyBase(new Point2d(100, 100)))
            .isEqualTo(new Point2d(32, 32));
    }

    @Test
    void estimatesUpperRightEnemyFromLowerLeftBase() {
        assertThat(BasicScoutingTask.estimatedEnemyBase(new Point2d(8, 8)))
            .isEqualTo(new Point2d(224, 224));
    }

    // --- Helpers ---

    private MutableMapCaseContext caseContext(List<Unit> enemies, List<Building> buildings,
                                     List<Unit> workers, long frame) {
        return new MutableMapCaseContext(Map.of(
            QuarkMindCaseFile.ENEMY_UNITS,  enemies,
            QuarkMindCaseFile.MY_BUILDINGS, buildings,
            QuarkMindCaseFile.WORKERS,      workers,
            QuarkMindCaseFile.GAME_FRAME,   frame,
            QuarkMindCaseFile.READY,        Boolean.TRUE));
    }

    private Building nexus() {
        return new Building("n-0", BuildingType.NEXUS, new Point2d(8, 8), 1500, 1500, true);
    }

    private Unit enemy(float x, float y) {
        return new Unit("e-" + (int) x, UnitType.ZEALOT, new Point2d(x, y), 100, 100, 50, 50, 0, 0);
    }

    private Unit probe(String tag) {
        return new Unit(tag, UnitType.PROBE, new Point2d(9, 9), 45, 45, 20, 20, 0, 0);
    }
}

package io.quarkmind.plugin;

import io.quarkmind.agent.MapCaseContext;
import io.quarkmind.agent.MutableMapCaseContext;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.domain.*;
import io.quarkmind.sc2.IntentQueue;
import io.quarkmind.sc2.intent.AttackIntent;
import io.quarkmind.sc2.intent.MoveIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BasicTacticsTaskTest {

    IntentQueue intentQueue;
    BasicTacticsTask task;

    @BeforeEach
    void setUp() {
        intentQueue = new IntentQueue();
        task = new BasicTacticsTask(intentQueue);
    }

    // --- ATTACK ---

    @Test
    void attackQueuesAttackIntentForEachArmyUnit() {
        var ctx = caseContext("ATTACK", List.of(stalker("s-0"), stalker("s-1")), List.of(nexus()));
        task.execute(ctx);
        assertThat(intentQueue.pending())
            .hasSize(2)
            .allMatch(i -> i instanceof AttackIntent);
    }

    @Test
    void attackTargetsMapCenterAlways() {
        // BasicTacticsTask no longer reads NEAREST_THREAT — always uses MAP_CENTER
        var ctx = caseContext("ATTACK", List.of(stalker("s-0")), List.of(nexus()));
        task.execute(ctx);
        assertThat(((AttackIntent) intentQueue.pending().get(0)).targetLocation())
            .isEqualTo(BasicTacticsTask.MAP_CENTER);
    }

    @Test
    void attackTargetsMapCenterWhenNoThreatKnown() {
        var ctx = caseContext("ATTACK", List.of(stalker("s-0")), List.of(nexus()));
        task.execute(ctx);
        assertThat(((AttackIntent) intentQueue.pending().get(0)).targetLocation())
            .isEqualTo(BasicTacticsTask.MAP_CENTER);
    }

    // --- DEFEND ---

    @Test
    void defendQueuesMoveIntentForEachArmyUnit() {
        var ctx = caseContext("DEFEND", List.of(stalker("s-0"), stalker("s-1")), List.of(nexus()));
        task.execute(ctx);
        assertThat(intentQueue.pending())
            .hasSize(2)
            .allMatch(i -> i instanceof MoveIntent);
    }

    @Test
    void defendRalliesToNexusPosition() {
        Point2d nexusPos = new Point2d(8, 8);
        var ctx = caseContext("DEFEND", List.of(stalker("s-0")), List.of(nexus()));
        task.execute(ctx);
        assertThat(((MoveIntent) intentQueue.pending().get(0)).targetLocation()).isEqualTo(nexusPos);
    }

    @Test
    void defendFallsBackToMapCenterWhenNoNexus() {
        var ctx = caseContext("DEFEND", List.of(stalker("s-0")), List.of());
        task.execute(ctx);
        assertThat(((MoveIntent) intentQueue.pending().get(0)).targetLocation())
            .isEqualTo(BasicTacticsTask.MAP_CENTER);
    }

    // --- MACRO ---

    @Test
    void macroProducesNoIntents() {
        var ctx = caseContext("MACRO", List.of(stalker("s-0")), List.of(nexus()));
        task.execute(ctx);
        assertThat(intentQueue.pending()).isEmpty();
    }

    // --- Edge cases ---

    @Test
    void emptyArmyProducesNoIntents() {
        var ctx = caseContext("ATTACK", List.of(), List.of(nexus()));
        task.execute(ctx);
        assertThat(intentQueue.pending()).isEmpty();
    }

    @Test
    void noStrategyDefaultsToMacro() {
        var ctx = new MutableMapCaseContext(Map.of(
            QuarkMindCaseFile.ARMY,         List.of(stalker("s-0")),
            QuarkMindCaseFile.MY_BUILDINGS, List.of(nexus()),
            QuarkMindCaseFile.READY,        Boolean.TRUE));
        task.execute(ctx);
        assertThat(intentQueue.pending()).isEmpty();
    }

    // --- Entry criteria — {READY, STRATEGY} ---

    @Test
    void testActivation_false_withoutStrategy() {
        var ctx = new MapCaseContext(Map.of(
            QuarkMindCaseFile.READY, Boolean.TRUE));
        // STRATEGY absent → testActivation must return false
        assertThat(task.testActivation(ctx)).isFalse();
    }

    @Test
    void testActivation_true_withReadyAndStrategy() {
        var ctx = new MapCaseContext(Map.of(
            QuarkMindCaseFile.READY,    Boolean.TRUE,
            QuarkMindCaseFile.STRATEGY, "DEFEND"));
        assertThat(task.testActivation(ctx)).isTrue();
    }

    // --- Helpers ---

    private MutableMapCaseContext caseContext(String strategy, List<Unit> army, List<Building> buildings) {
        return new MutableMapCaseContext(Map.of(
            QuarkMindCaseFile.STRATEGY,     strategy,
            QuarkMindCaseFile.ARMY,         army,
            QuarkMindCaseFile.MY_BUILDINGS, buildings,
            QuarkMindCaseFile.READY,        Boolean.TRUE));
    }

    private Unit stalker(String tag) {
        return new Unit(tag, UnitType.STALKER, new Point2d(10, 10), 80, 80, 80, 80, 0, 0);
    }

    private Building nexus() {
        return new Building("n-0", BuildingType.NEXUS, new Point2d(8, 8), 1500, 1500, true);
    }
}

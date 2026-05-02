package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.*;
import io.quarkmind.sc2.IntentQueue;
import io.quarkmind.sc2.intent.AttackIntent;
import io.quarkmind.sc2.intent.BuildIntent;
import io.quarkmind.sc2.intent.MoveIntent;
import io.quarkmind.sc2.intent.TrainIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class EnemyBehaviorTest {

    PlayerState   enemy;
    IntentQueue   queue;
    EnemyBehavior behavior;

    static EnemyStrategy zealotSpam() {
        return new FixedBuildOrderStrategy("TEST_SPAM", Race.PROTOSS,
            List.of(UnitType.ZEALOT), 10, new EnemyAttackConfig(3, 200, 30, 50));
    }

    static GameState emptyState() {
        return new GameState(50, 0, 15, 12,
            List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), 0L);
    }

    static GameState stateWithFrame(long frame) {
        return new GameState(50, 0, 15, 12,
            List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), frame);
    }

    /** Permissive TechTree — always allows training, never requires a prereq building. */
    static TechTree permissive() {
        return new TechTree() {
            @Override public boolean canTrain(UnitType u, Set<BuildingType> b) { return true; }
            @Override public Optional<BuildingType> nextRequired(UnitType u, Set<BuildingType> b) { return Optional.empty(); }
        };
    }

    @BeforeEach
    void setUp() {
        enemy    = new PlayerState();
        queue    = new IntentQueue();
        behavior = new EnemyBehavior(zealotSpam(), enemy, permissive());
    }

    // ---- mineral accumulation ----

    @Test
    void tick_accumulatesMinerals() {
        double before = enemy.minerals;
        behavior.tick(emptyState(), queue);
        assertThat(enemy.minerals).isGreaterThan(before);
    }

    // ---- unit training ----

    @Test
    void tick_queuesTrainIntent_whenMineralsAvailable() {
        enemy.minerals = 200;
        behavior.tick(emptyState(), queue);
        var intents = queue.drainAll();
        assertThat(intents).anyMatch(i -> i instanceof TrainIntent t
            && t.unitType() == UnitType.ZEALOT);
    }

    @Test
    void tick_doesNotQueueTrain_whenInsufficientMinerals() {
        enemy.minerals = 0;
        behavior.tick(emptyState(), queue);
        var intents = queue.drainAll();
        assertThat(intents).noneMatch(i -> i instanceof TrainIntent);
    }

    @Test
    void tick_doesNotQueueTrainTwiceBeforePreviousIsTrained() {
        enemy.minerals = 500;
        behavior.tick(emptyState(), queue);
        queue.drainAll(); // consume intent but don't "train" the unit
        behavior.tick(emptyState(), queue);
        var second = queue.drainAll();
        // Should not queue ANOTHER train — still waiting for first to complete
        assertThat(second).noneMatch(i -> i instanceof TrainIntent);
    }

    // ---- attack launch ----

    @Test
    void tick_launchesAttack_whenArmyThresholdMet() {
        for (int i = 0; i < 3; i++) {
            enemy.stagingArea.add(new Unit("s" + i, UnitType.ZEALOT,
                new Point2d(26,26), 100,100,50,50,0,0));
        }
        behavior.tick(emptyState(), queue);
        var intents = queue.drainAll();
        assertThat(intents.stream().filter(i -> i instanceof AttackIntent).count()).isEqualTo(3);
        assertThat(enemy.stagingArea).isEmpty();
    }

    @Test
    void tick_doesNotAttack_belowThreshold_andTimerNotFired() {
        // Seed timer so it hasn't elapsed (interval=200, set to 50)
        behavior.setFramesSinceLastAttackForTesting(50L);
        enemy.stagingArea.add(new Unit("s0", UnitType.ZEALOT,
            new Point2d(26,26), 100,100,50,50,0,0));
        behavior.tick(emptyState(), queue);
        assertThat(queue.drainAll()).noneMatch(i -> i instanceof AttackIntent);
    }

    @Test
    void tick_launchesAttack_whenTimerFires_evenBelowThreshold() {
        // threshold=3, only 1 unit in staging — threshold alone wouldn't fire
        enemy.stagingArea.add(new Unit("s0", UnitType.ZEALOT,
            new Point2d(26,26), 100,100,50,50,0,0));
        // attackIntervalFrames=200 — set counter to exactly the threshold
        behavior.setInitialAttackSizeForTesting(1);
        behavior.setFramesSinceLastAttackForTesting(200L);
        behavior.tick(emptyState(), queue);
        var intents = queue.drainAll();
        assertThat(intents).anyMatch(i -> i instanceof AttackIntent);
        assertThat(enemy.stagingArea).isEmpty();
    }

    // ---- retreat ----

    @Test
    void lowHealthUnit_isMarkedRetreating() {
        Unit lowHp = new Unit("u0", UnitType.ZEALOT, new Point2d(8,8), 10, 100, 0, 50, 0, 0);
        enemy.units.add(lowHp);
        behavior.setInitialAttackSizeForTesting(4);
        behavior.tick(emptyState(), queue);
        assertThat(behavior.retreatingUnits()).contains("u0");
    }

    @Test
    void retreatingUnit_isMoved_backToStaging() {
        Unit lowHp = new Unit("u0", UnitType.ZEALOT, new Point2d(8,8), 10, 100, 0, 50, 0, 0);
        enemy.units.add(lowHp);
        behavior.setInitialAttackSizeForTesting(4);
        behavior.tick(emptyState(), queue);
        // A MoveIntent toward staging should be in queue
        var intents = queue.drainAll();
        assertThat(intents).anyMatch(i -> i instanceof MoveIntent);
    }

    // ---- tech-tree gating ----

    @Test
    void whenTargetNeedsPrereq_queuesBuildIntent() {
        // Stalker needs GATEWAY + CYBERNETICS_CORE; enemy has neither
        EnemyStrategy stalker = new FixedBuildOrderStrategy("STALK", Race.PROTOSS,
            List.of(UnitType.STALKER), 50, new EnemyAttackConfig(3, 200, 0, 0));
        enemy.minerals = 500;
        var b = new EnemyBehavior(stalker, enemy, new TechTree());
        b.tick(emptyState(), queue);
        var intents = queue.drainAll();
        assertThat(intents).anyMatch(i -> i instanceof BuildIntent bi
            && bi.buildingType() == BuildingType.GATEWAY);
        assertThat(intents).noneMatch(i -> i instanceof TrainIntent);
    }

    @Test
    void whenPrereqBuilt_trainsProceedsNormally() {
        EnemyStrategy stalker = new FixedBuildOrderStrategy("STALK", Race.PROTOSS,
            List.of(UnitType.STALKER), 50, new EnemyAttackConfig(3, 200, 0, 0));
        enemy.minerals = 500;
        // Pre-place required buildings
        enemy.buildings.add(new Building("gw", BuildingType.GATEWAY,
            new Point2d(50, 50), 500, 500, true));
        enemy.buildings.add(new Building("cc", BuildingType.CYBERNETICS_CORE,
            new Point2d(51, 50), 500, 500, true));
        var b = new EnemyBehavior(stalker, enemy, new TechTree());
        b.tick(emptyState(), queue);
        var intents = queue.drainAll();
        assertThat(intents).anyMatch(i -> i instanceof TrainIntent t
            && t.unitType() == UnitType.STALKER);
    }

    @Test
    void doesNotDoubleQueuePendingBuilding() {
        EnemyStrategy stalker = new FixedBuildOrderStrategy("STALK", Race.PROTOSS,
            List.of(UnitType.STALKER), 50, new EnemyAttackConfig(3, 200, 0, 0));
        enemy.minerals = 500;
        var b = new EnemyBehavior(stalker, enemy, new TechTree());
        b.tick(emptyState(), queue);
        queue.drainAll(); // first tick queues GATEWAY
        b.tick(emptyState(), queue);
        var second = queue.drainAll();
        long buildCount = second.stream().filter(i -> i instanceof BuildIntent bi
            && bi.buildingType() == BuildingType.GATEWAY).count();
        assertThat(buildCount).isZero();
    }

    @Test
    void pendingBuilding_prunedOnceBuilt_allowsRetry() {
        EnemyStrategy stalker = new FixedBuildOrderStrategy("STALK", Race.PROTOSS,
            List.of(UnitType.STALKER), 50, new EnemyAttackConfig(3, 200, 0, 0));
        enemy.minerals = 500;
        var b = new EnemyBehavior(stalker, enemy, new TechTree());
        // First tick — queues GATEWAY (pending)
        b.tick(emptyState(), queue);
        queue.drainAll();
        // Simulate building completing: add it to enemy.buildings
        enemy.buildings.add(new Building("gw", BuildingType.GATEWAY, new Point2d(50, 50), 500, 500, true));
        // Second tick — GATEWAY is built, should prune and move to CYBERNETICS_CORE
        b.tick(emptyState(), queue);
        var intents = queue.drainAll();
        assertThat(intents).anyMatch(i -> i instanceof BuildIntent bi
            && bi.buildingType() == BuildingType.CYBERNETICS_CORE);
    }

    // ---- ReactiveStrategy wiring ----

    @Test
    void reactiveStrategy_remainsActiveAfterShouldSwitch() {
        // ReactiveStrategy with 10-frame re-eval interval
        ReactiveStrategy reactive = new ReactiveStrategy(10);
        enemy.minerals = 0;
        var b = new EnemyBehavior(reactive, enemy, permissive());

        // Tick frames 1–9 — shouldSwitch returns false (not a multiple of 10)
        for (int i = 1; i < 10; i++) {
            b.tick(stateWithFrame(i), queue);
            queue.drainAll();
        }
        // Outer strategy unchanged
        assertThat(b.currentStrategy()).isSameAs(reactive);

        // Tick frame 10 — shouldSwitch fires, resolveCounter() runs internally
        b.tick(stateWithFrame(10), queue);
        queue.drainAll();

        // Outer wrapper must still be the same ReactiveStrategy instance
        assertThat(b.currentStrategy()).isSameAs(reactive);
    }
}

package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.*;
import io.quarkmind.sc2.IntentQueue;
import io.quarkmind.sc2.intent.AttackIntent;
import io.quarkmind.sc2.intent.MoveIntent;
import io.quarkmind.sc2.intent.TrainIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
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

    @BeforeEach
    void setUp() {
        enemy    = new PlayerState();
        queue    = new IntentQueue();
        behavior = new EnemyBehavior(zealotSpam(), enemy);
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
    void tick_doesNotAttack_belowThreshold() {
        enemy.stagingArea.add(new Unit("s0", UnitType.ZEALOT,
            new Point2d(26,26), 100,100,50,50,0,0));
        behavior.tick(emptyState(), queue);
        assertThat(queue.drainAll()).noneMatch(i -> i instanceof AttackIntent);
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
}

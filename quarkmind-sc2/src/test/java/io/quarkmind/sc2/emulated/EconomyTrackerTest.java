package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EconomyTrackerTest {

    @Test
    void tracksTrainSpendingAsArmyCost() {
        var tracker = new EconomyTracker();
        tracker.recordTrainSpending(UnitType.MARINE, 50, 0);
        tracker.recordTrainSpending(UnitType.MARINE, 50, 0);

        var stats = tracker.currentStats(100, 0, 23, 14, 12);
        assertThat(stats.mineralsUsedCurrentArmy()).isEqualTo(100);
        assertThat(stats.vespeneUsedCurrentArmy()).isEqualTo(0);
    }

    @Test
    void tracksWorkerTrainSpendingAsEconomy() {
        var tracker = new EconomyTracker();
        tracker.recordTrainSpending(UnitType.PROBE, 50, 0);

        var stats = tracker.currentStats(200, 0, 15, 7, 13);
        assertThat(stats.mineralsUsedCurrentEconomy()).isEqualTo(50);
        assertThat(stats.mineralsUsedCurrentArmy()).isEqualTo(0);
    }

    @Test
    void tracksBuildSpendingByCategory() {
        var tracker = new EconomyTracker();
        tracker.recordBuildSpending(BuildingType.NEXUS, 400);
        tracker.recordBuildSpending(BuildingType.GATEWAY, 150);
        tracker.recordBuildSpending(BuildingType.CYBERNETICS_CORE, 150);

        var stats = tracker.currentStats(500, 200, 31, 14, 12);
        assertThat(stats.mineralsUsedCurrentEconomy()).isEqualTo(550);
        assertThat(stats.mineralsUsedCurrentTechnology()).isEqualTo(150);
    }

    @Test
    void computesCollectionRateFromDelta() {
        var tracker = new EconomyTracker();
        for (int i = 0; i < 8; i++) {
            tracker.tickUpdate(50.0 + i * 60, i * 10, 23, 14, 12);
        }
        var stats = tracker.currentStats(530, 70, 23, 14, 12);
        assertThat(stats.mineralsCollectionRate()).isGreaterThan(0);
        assertThat(stats.vespeneCollectionRate()).isGreaterThan(0);
    }

    @Test
    void reportsSupplyAndWorkers() {
        var tracker = new EconomyTracker();
        var stats = tracker.currentStats(200, 100, 31, 18, 16);
        assertThat(stats.mineralsCurrent()).isEqualTo(200);
        assertThat(stats.vespeneCurrent()).isEqualTo(100);
        assertThat(stats.foodMade()).isEqualTo(31);
        assertThat(stats.foodUsed()).isEqualTo(18);
        assertThat(stats.workersActiveCount()).isEqualTo(16);
    }

    @Test
    void resetClearsAllTracking() {
        var tracker = new EconomyTracker();
        tracker.recordTrainSpending(UnitType.ZEALOT, 100, 0);
        tracker.tickUpdate(100, 50, 15, 6, 12);
        tracker.reset();
        var stats = tracker.currentStats(50, 0, 15, 6, 12);
        assertThat(stats.mineralsUsedCurrentArmy()).isEqualTo(0);
        assertThat(stats.mineralsCollectionRate()).isEqualTo(0);
    }
}

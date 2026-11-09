package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.UnitType;
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
        assertThat(stats.mineralsUsedCurrentEconomy()).isEqualTo(400);
        assertThat(stats.mineralsUsedCurrentTechnology()).isEqualTo(300);
    }

    @Test
    void computesCollectionRateFromDelta() {
        var tracker = new EconomyTracker();
        for (int i = 0; i < 8; i++) {
            tracker.recordIncome(60.0, 10.0);
            tracker.tickUpdate(50.0 + i * 60, i * 10, 23, 14, 12);
        }
        var stats = tracker.currentStats(530, 70, 23, 14, 12);
        assertThat(stats.mineralsCollectionRate()).isGreaterThan(0);
        assertThat(stats.vespeneCollectionRate()).isGreaterThan(0);
    }

    @Test
    void collectionRateIsConvertedToPerMinute() {
        var tracker = new EconomyTracker();
        // 8 ticks = 176 loops >= 160 (default). Income: 10.0/tick * 8 = 80.0 total
        for (int i = 0; i < 8; i++) {
            tracker.recordIncome(10.0, 2.0);
            tracker.tickUpdate(0, 0, 15, 6, 12);
        }
        var    stats       = tracker.currentStats(0, 0, 15, 6, 12);
        double loopsPerMin = 60.0 * 22.4;
        assertThat(stats.mineralsCollectionRate()).isEqualTo((int) (80.0 / 176.0 * loopsPerMin));
        assertThat(stats.vespeneCollectionRate()).isEqualTo((int) (16.0 / 176.0 * loopsPerMin));
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
    void collectionRateReflectsGrossIncomeNotNetDelta() {
        var tracker = new EconomyTracker();
        for (int i = 0; i < 8; i++) {
            tracker.recordIncome(60.0, 10.0);
            // Balance drops due to spending — pass decreasing balance
            tracker.tickUpdate(50.0 - i * 5, Math.max(0, 80 - i * 10), 23, 14, 12);
        }
        var stats = tracker.currentStats(10, 10, 23, 14, 12);
        // Rate should reflect gross income (~60/tick over 7 ticks), not net delta (negative)
        assertThat(stats.mineralsCollectionRate()).isGreaterThanOrEqualTo(50);
        assertThat(stats.vespeneCollectionRate()).isGreaterThanOrEqualTo(5);
    }


    @Test
    void tracksBuildingMorphSpendingByCategory() {
        var tracker = new EconomyTracker();
        tracker.recordMorphSpending(BuildingType.GREATER_SPIRE, 100, 150);

        var stats = tracker.currentStats(200, 100, 23, 14, 12);
        assertThat(stats.mineralsUsedCurrentTechnology()).isEqualTo(100);
        assertThat(stats.vespeneUsedCurrentTechnology()).isEqualTo(150);
        assertThat(stats.mineralsUsedCurrentEconomy()).isEqualTo(0);
    }

    @Test
    void overlordTrainSpendingGoesToEconomy() {
        var tracker = new EconomyTracker();
        tracker.recordTrainSpending(UnitType.OVERLORD, 100, 0);

        var stats = tracker.currentStats(0, 0, 22, 14, 12);
        assertThat(stats.mineralsUsedCurrentEconomy()).isEqualTo(100);
        assertThat(stats.mineralsUsedCurrentArmy()).isEqualTo(0);
    }

    @Test
    void overseerDeathSubtractsFromEconomy() {
        var tracker = new EconomyTracker();
        tracker.recordTrainSpending(UnitType.OVERLORD, 100, 0);
        tracker.recordMorphSpending(UnitType.OVERSEER, 50, 50);

        tracker.recordUnitDeath(UnitType.OVERSEER);

        var stats = tracker.currentStats(0, 0, 22, 14, 12);
        assertThat(stats.mineralsUsedCurrentEconomy()).isEqualTo(0);
        assertThat(stats.vespeneUsedCurrentEconomy()).isEqualTo(0);
    }

    @Test
    void tracksUnitMorphSpendingAsArmy() {
        var tracker = new EconomyTracker();
        tracker.recordMorphSpending(UnitType.BANELING, 25, 25);

        var stats = tracker.currentStats(200, 100, 23, 14, 12);
        assertThat(stats.mineralsUsedCurrentArmy()).isEqualTo(25);
        assertThat(stats.vespeneUsedCurrentArmy()).isEqualTo(25);
    }

    @Test
    void unitDeathOfMorphedUnitSubtractsTotalInvestment() {
        var tracker = new EconomyTracker();
        tracker.recordTrainSpending(UnitType.ZERGLING, 25, 0);
        tracker.recordMorphSpending(UnitType.BANELING, 25, 25);

        var before = tracker.currentStats(0, 0, 15, 6, 12);
        assertThat(before.mineralsUsedCurrentArmy()).isEqualTo(50);
        assertThat(before.vespeneUsedCurrentArmy()).isEqualTo(25);

        tracker.recordUnitDeath(UnitType.BANELING);

        var after = tracker.currentStats(0, 0, 15, 6, 12);
        assertThat(after.mineralsUsedCurrentArmy()).isEqualTo(0);
        assertThat(after.vespeneUsedCurrentArmy()).isEqualTo(0);
    }

    @Test
    void unitDeathOfNonMorphedUnitSubtractsOnlyBaseCost() {
        var tracker = new EconomyTracker();
        tracker.recordTrainSpending(UnitType.MARINE, 50, 0);
        tracker.recordTrainSpending(UnitType.MARINE, 50, 0);

        tracker.recordUnitDeath(UnitType.MARINE);

        var stats = tracker.currentStats(0, 0, 15, 6, 12);
        assertThat(stats.mineralsUsedCurrentArmy()).isEqualTo(50);
    }

    @Test
    void morphDeathNeverGoesNegative() {
        var tracker = new EconomyTracker();
        tracker.recordUnitDeath(UnitType.BANELING);

        var stats = tracker.currentStats(0, 0, 15, 6, 12);
        assertThat(stats.mineralsUsedCurrentArmy()).isEqualTo(0);
        assertThat(stats.vespeneUsedCurrentArmy()).isEqualTo(0);
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

    // --- InProgress spending tests (#555) ---

    @Test
    void inProgressTrainSpending_excludedFromSnapshot() {
        var tracker = new EconomyTracker();
        tracker.addInProgressArmy(150, 25);

        var stats = tracker.currentStats(200, 100, 23, 14, 12);
        assertThat(stats.mineralsUsedCurrentArmy()).isEqualTo(0);
        assertThat(stats.vespeneUsedCurrentArmy()).isEqualTo(0);
    }

    @Test
    void inProgressCompleted_movesToCurrent() {
        var tracker = new EconomyTracker();
        tracker.addInProgressArmy(150, 25);
        tracker.completeInProgressArmy(150, 25);
        tracker.recordTrainSpending(UnitType.STALKER, 125, 50);

        var stats = tracker.currentStats(200, 100, 23, 14, 12);
        assertThat(stats.mineralsUsedCurrentArmy()).isEqualTo(125);
        assertThat(stats.vespeneUsedCurrentArmy()).isEqualTo(50);
    }

    @Test
    void inProgressCancelled_removedFromInProgress() {
        var tracker = new EconomyTracker();
        tracker.addInProgressArmy(150, 25);
        tracker.cancelInProgressArmy(150, 25);

        var stats = tracker.currentStats(200, 100, 23, 14, 12);
        assertThat(stats.mineralsUsedCurrentArmy()).isEqualTo(0);
        assertThat(stats.vespeneUsedCurrentArmy()).isEqualTo(0);
    }

    @Test
    void inProgressBuildSpending_excludedFromSnapshot() {
        var tracker = new EconomyTracker();
        tracker.addInProgressTechnology(150, 0);

        var stats = tracker.currentStats(200, 100, 23, 14, 12);
        assertThat(stats.mineralsUsedCurrentTechnology()).isEqualTo(0);
    }

    @Test
    void inProgressEconomySpending_excludedFromSnapshot() {
        var tracker = new EconomyTracker();
        tracker.addInProgressEconomy(400, 0);

        var stats = tracker.currentStats(200, 100, 23, 14, 12);
        assertThat(stats.mineralsUsedCurrentEconomy()).isEqualTo(0);
    }

    @Test
    void currentStats_reportsCompletedOnly() {
        var tracker = new EconomyTracker();
        tracker.addInProgressArmy(100, 0);
        tracker.recordTrainSpending(UnitType.MARINE, 50, 0);

        var stats = tracker.currentStats(200, 100, 23, 14, 12);
        assertThat(stats.mineralsUsedCurrentArmy()).isEqualTo(50);
    }

    @Test
    void inProgressNeverGoesNegative() {
        var tracker = new EconomyTracker();
        tracker.cancelInProgressArmy(100, 50);

        var stats = tracker.currentStats(200, 100, 23, 14, 12);
        assertThat(stats.mineralsUsedCurrentArmy()).isEqualTo(0);
        assertThat(stats.vespeneUsedCurrentArmy()).isEqualTo(0);
    }

    @Test
    void resetClearsInProgress() {
        var tracker = new EconomyTracker();
        tracker.addInProgressArmy(100, 50);
        tracker.reset();

        var stats = tracker.currentStats(200, 100, 23, 14, 12);
        assertThat(stats.mineralsUsedCurrentArmy()).isEqualTo(0);
    }

    @Test
    void rateRecalculationFiresAtLoopThreshold() {
        var tracker = new EconomyTracker(160);
        // 160 loops / 22 loops per tick = 7.27 ticks — rate should NOT fire after 7 ticks
        for (int i = 0; i < 7; i++) {
            tracker.recordIncome(10.0, 2.0);
            tracker.tickUpdate(0, 0, 15, 6, 12);
        }
        var after7 = tracker.currentStats(0, 0, 15, 6, 12);
        assertThat(after7.mineralsCollectionRate()).as("rate should not update after 7 ticks (154 loops < 160)").isEqualTo(0);

        // 8th tick pushes to 176 loops >= 160 — rate should fire
        tracker.recordIncome(10.0, 2.0);
        tracker.tickUpdate(0, 0, 15, 6, 12);
        var after8 = tracker.currentStats(0, 0, 15, 6, 12);
        assertThat(after8.mineralsCollectionRate()).as("rate should update after 8 ticks (176 loops >= 160)").isGreaterThan(0);
    }

    @Test
    void loopBasedRateUsesActualElapsedLoops() {
        var tracker = new EconomyTracker(160);
        // 8 ticks = 176 loops. Income: 10.0 minerals/tick over 8 ticks = 80.0 total
        // Rate = 80.0 / 176 loops * (60 * 22.4) loops/min = 80.0 / 176 * 1344 = 610.9 → 610
        for (int i = 0; i < 8; i++) {
            tracker.recordIncome(10.0, 2.0);
            tracker.tickUpdate(0, 0, 15, 6, 12);
        }
        var    stats               = tracker.currentStats(0, 0, 15, 6, 12);
        double loopsPerMinute      = 60.0 * 22.4;
        int    expectedMineralRate = (int) (80.0 / 176.0 * loopsPerMinute);
        int    expectedVespeneRate = (int) (16.0 / 176.0 * loopsPerMinute);
        assertThat(stats.mineralsCollectionRate()).isEqualTo(expectedMineralRate);
        assertThat(stats.vespeneCollectionRate()).isEqualTo(expectedVespeneRate);
    }


}

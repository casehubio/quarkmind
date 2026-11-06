package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerSimulationTest {

    private WorkerSimulation sim;
    private static final Point2d BASE_POS = new Point2d(50, 50);
    private static final String BASE_TAG = "base-1";

    @BeforeEach
    void setUp() {
        sim = new WorkerSimulation();
        sim.onTownHallCompleted(BASE_TAG, BASE_POS);
    }

    @Test
    void singleWorker_completesFullMiningCycle() {
        sim.onInitialWorkers(List.of("w-1"), BASE_POS, BASE_TAG);
        double totalIncome = 0;
        for (int t = 0; t < 20; t++) {
            totalIncome += sim.tick().mineralIncome();
        }
        assertTrue(totalIncome > 0, "Worker should deposit minerals within 20 ticks");
    }

    @Test
    void eightWorkers_eachGetsUniquePatch_noWaiting() {
        List<String> tags = IntStream.rangeClosed(1, 8)
                .mapToObj(i -> "w-" + i).toList();
        sim.onInitialWorkers(tags, BASE_POS, BASE_TAG);
        double totalIncome = 0;
        for (int t = 0; t < 40; t++) {
            totalIncome += sim.tick().mineralIncome();
        }
        assertTrue(totalIncome > 0);
    }

    @Test
    void sixteenWorkers_showsSaturationPenalty() {
        List<String> tags8 = IntStream.rangeClosed(1, 8)
                .mapToObj(i -> "w-" + i).toList();
        sim.onInitialWorkers(tags8, BASE_POS, BASE_TAG);
        double income8workers = 0;
        for (int t = 0; t < 100; t++) {
            income8workers += sim.tick().mineralIncome();
        }

        sim.reset();
        sim.onTownHallCompleted(BASE_TAG, BASE_POS);
        List<String> tags16 = IntStream.rangeClosed(1, 16)
                .mapToObj(i -> "w-" + i).toList();
        sim.onInitialWorkers(tags16, BASE_POS, BASE_TAG);
        double income16workers = 0;
        for (int t = 0; t < 100; t++) {
            income16workers += sim.tick().mineralIncome();
        }

        assertTrue(income16workers > income8workers,
                "16 workers should earn more than 8");
        assertTrue(income16workers < income8workers * 2,
                "16 workers should earn less than double of 8 (saturation)");
    }

    @Test
    void mineralIncomeRate_16workers_matchesSC2() {
        List<String> tags = IntStream.rangeClosed(1, 16)
                                     .mapToObj(i -> "w-" + i).toList();
        sim.onInitialWorkers(tags, BASE_POS, BASE_TAG);
        int warmupTicks = 20;
        for (int t = 0; t < warmupTicks; t++) {sim.tick();}
        double income       = 0;
        int    measureTicks = 200;
        for (int t = 0; t < measureTicks; t++) {
            income += sim.tick().mineralIncome();
        }
        double ticksPerMinute = 60.0 * io.quarkmind.domain.SC2Data.GAME_LOOPS_PER_SECOND / io.quarkmind.domain.SC2Data.LOOPS_PER_TICK;
        double perMinute      = income / measureTicks * ticksPerMinute;
        System.out.printf("16 workers: %.1f minerals/min (SC2 target: ~930 at optimal 16-worker saturation)%n", perMinute);
        System.out.printf("Per worker: %.1f minerals/min%n", perMinute / 16);
        System.out.printf("Total in %d ticks: %.1f minerals%n", measureTicks, income);
    }


    @Test
    void workerDied_freesPatchSlot() {
        sim.onInitialWorkers(List.of("w-1", "w-2"), BASE_POS, BASE_TAG);
        for (int t = 0; t < 5; t++) sim.tick();
        sim.onWorkerDied("w-1");
        double income = 0;
        for (int t = 0; t < 20; t++) {
            income += sim.tick().mineralIncome();
        }
        assertTrue(income > 0);
    }

    @Test
    void allPatchesDepleted_workersIdle_zeroIncome() {
        sim.onInitialWorkers(List.of("w-1"), BASE_POS, BASE_TAG);
        double totalIncome = 0;
        for (int t = 0; t < 100_000; t++) {
            totalIncome += sim.tick().mineralIncome();
        }
        assertTrue(totalIncome > 0);
        double incomeAfterDepletion = 0;
        for (int t = 0; t < 100; t++) {
            incomeAfterDepletion += sim.tick().mineralIncome();
        }
        assertEquals(0.0, incomeAfterDepletion, 0.001);
    }

    @Test
    void newWorkerProduced_assignedToNearestBase() {
        sim.onInitialWorkers(List.of("w-1"), BASE_POS, BASE_TAG);
        sim.onWorkerProduced("w-2", new Point2d(52, 50));
        assertArrayEquals(new int[]{2}, sim.mineralWorkersPerBase());
    }

    @Test
    void reset_clearsAllState() {
        sim.onInitialWorkers(List.of("w-1"), BASE_POS, BASE_TAG);
        sim.reset();
        assertArrayEquals(new int[0], sim.mineralWorkersPerBase());
    }

    @Test
    void multiBase_workersAssignedToNearestBase() {
        Point2d base2Pos = new Point2d(100, 50);
        sim.onTownHallCompleted("base-2", base2Pos);
        sim.onInitialWorkers(List.of("w-1", "w-2"), BASE_POS, BASE_TAG);
        sim.onWorkerProduced("w-3", new Point2d(98, 50));
        int[] counts = sim.mineralWorkersPerBase();
        assertEquals(2, counts[0]);
        assertEquals(1, counts[1]);
    }

    // --- Gas harvesting tests ---

    @Test
    void gasBuildingCompleted_transfers3MineralWorkersToGas() {
        List<String> tags = IntStream.rangeClosed(1, 12)
                .mapToObj(i -> "w-" + i).toList();
        sim.onInitialWorkers(tags, BASE_POS, BASE_TAG);
        sim.onGasBuildingCompleted("gas-1", new Point2d(45, 50));
        assertEquals(9, sim.mineralWorkersPerBase()[0]);
    }

    @Test
    void gasWorkers_produceVespeneIncome() {
        List<String> tags = IntStream.rangeClosed(1, 12)
                .mapToObj(i -> "w-" + i).toList();
        sim.onInitialWorkers(tags, BASE_POS, BASE_TAG);
        sim.onGasBuildingCompleted("gas-1", new Point2d(45, 50));
        double totalGas = 0;
        for (int t = 0; t < 40; t++) {
            totalGas += sim.tick().vespeneIncome();
        }
        assertTrue(totalGas > 0, "Gas workers should deposit vespene");
    }

    @Test
    void threeGasWorkers_totalThroughputExceedsTwoWorkers() {
        List<String> tags3 = IntStream.rangeClosed(1, 12)
                .mapToObj(i -> "w-" + i).toList();
        sim.onInitialWorkers(tags3, BASE_POS, BASE_TAG);
        sim.onGasBuildingCompleted("gas-3w", new Point2d(45, 50));
        double totalGas3 = 0;
        for (int t = 0; t < 200; t++) {
            totalGas3 += sim.tick().vespeneIncome();
        }

        sim.reset();
        sim.onTownHallCompleted(BASE_TAG, BASE_POS);
        List<String> tags2 = IntStream.rangeClosed(1, 12)
                .mapToObj(i -> "w-" + i).toList();
        sim.onInitialWorkers(tags2, BASE_POS, BASE_TAG);
        sim.onGasBuildingCompleted("gas-2w", new Point2d(45, 50));
        sim.onWorkerDied("w-1");
        double totalGas2 = 0;
        for (int t = 0; t < 200; t++) {
            totalGas2 += sim.tick().vespeneIncome();
        }

        assertTrue(totalGas3 > totalGas2,
                "Total gas from 3 workers (" + totalGas3
                        + ") should exceed 2 workers (" + totalGas2 + ")");
        double ratio = totalGas3 / totalGas2;
        assertTrue(ratio > 1.2 && ratio < 1.7,
                "3-worker/2-worker gas ratio should be ~1.5 (concurrent), got " + ratio);
    }

    @Test
    void gasWorkerDied_removedFromSlot() {
        List<String> tags = IntStream.rangeClosed(1, 12)
                .mapToObj(i -> "w-" + i).toList();
        sim.onInitialWorkers(tags, BASE_POS, BASE_TAG);
        sim.onGasBuildingCompleted("gas-1", new Point2d(45, 50));
        for (int t = 0; t < 5; t++) sim.tick();
        sim.onWorkerDied("w-1");
        double totalGas = 0;
        for (int t = 0; t < 40; t++) {
            totalGas += sim.tick().vespeneIncome();
        }
        assertTrue(totalGas > 0);
    }

    // --- Formula validator ---

    @Test
    void formulaValidator_simulationAndFormulaWithinTolerance() {
        List<String> tags = IntStream.rangeClosed(1, 16)
                .mapToObj(i -> "w-" + i).toList();
        sim.onInitialWorkers(tags, BASE_POS, BASE_TAG);

        TickIncome lastIncome = null;
        for (int t = 0; t < 100; t++) {
            lastIncome = sim.tick();
        }

        WorkerSimulation.FormulaComparison comparison = sim.compareWithFormula(lastIncome);
        assertTrue(comparison.mineralDivergence() < 0.5,
                "Mineral divergence too high: " + comparison);
    }
// --- Map-specific mineral patch tests ---

    @Test
    void defaultBase_hasMixedCloseAndFarPatches() {
        sim.onInitialWorkers(List.of("w-1"), BASE_POS, BASE_TAG);
        double totalIncome = 0;
        for (int t = 0; t < 200_000; t++) {
            totalIncome += sim.tick().mineralIncome();
        }
        double expectedTotal = MineralPatch.CLOSE_PATCHES_PER_BASE * MineralPatch.CLOSE_PATCH_MINERALS
                               + (MineralPatch.PATCHES_PER_BASE - MineralPatch.CLOSE_PATCHES_PER_BASE) * MineralPatch.FAR_PATCH_MINERALS;
        assertEquals(expectedTotal, totalIncome, 0.001,
                     "Total minerals harvested should equal 4×1500 + 4×750 = 9000");
    }

    @Test
    void explicitPatchAmounts_usedWhenProvided() {
        WorkerSimulation explicitSim = new WorkerSimulation();
        List<Resource> patches = List.of(
                new Resource("m-1", new Point2d(44, 50), 1000),
                new Resource("m-2", new Point2d(46, 50), 500),
                new Resource("m-3", new Point2d(48, 50), 500),
                new Resource("m-4", new Point2d(52, 50), 500)
                                        );
        explicitSim.onTownHallCompleted(BASE_TAG, BASE_POS, patches);
        explicitSim.onInitialWorkers(List.of("w-1"), BASE_POS, BASE_TAG);
        double totalIncome = 0;
        for (int t = 0; t < 200_000; t++) {
            totalIncome += explicitSim.tick().mineralIncome();
        }
        assertEquals(2500.0, totalIncome, 0.001,
                     "Total should equal sum of explicit amounts: 1000 + 500 + 500 + 500");
    }


    @Test
    void richPatch_yields7MineralsPerTrip() {
        WorkerSimulation richSim = new WorkerSimulation();
        List<Resource> patches = List.of(
                new Resource("m-1", new Point2d(44, 50), 1500, true),
                new Resource("m-2", new Point2d(56, 50), 1500, false)
                                        );
        richSim.onTownHallCompleted(BASE_TAG, BASE_POS, patches);
        richSim.onInitialWorkers(List.of("w-rich"), BASE_POS, BASE_TAG);

        WorkerSimulation normalSim = new WorkerSimulation();
        List<Resource> normalPatches = List.of(
                new Resource("m-1", new Point2d(44, 50), 1500, false),
                new Resource("m-2", new Point2d(56, 50), 1500, false)
                                              );
        normalSim.onTownHallCompleted(BASE_TAG, BASE_POS, normalPatches);
        normalSim.onInitialWorkers(List.of("w-normal"), BASE_POS, BASE_TAG);

        double richIncome   = 0;
        double normalIncome = 0;
        for (int t = 0; t < 200; t++) {
            richIncome += richSim.tick().mineralIncome();
            normalIncome += normalSim.tick().mineralIncome();
        }

        assertTrue(richIncome > normalIncome,
                   "Rich patch worker (" + richIncome + ") should earn more than normal (" + normalIncome + ")");
        double ratio = richIncome / normalIncome;
        assertTrue(ratio > 1.3 && ratio < 1.5,
                   "Rich/normal ratio should be ~1.4 (7/5), got " + ratio);
    }

    @Test
    void richPatch_totalMineralsDepleted_sameAsNormal() {
        WorkerSimulation richSim = new WorkerSimulation();
        List<Resource> patches = List.of(
                new Resource("m-1", new Point2d(44, 50), 100, true)
                                        );
        richSim.onTownHallCompleted(BASE_TAG, BASE_POS, patches);
        richSim.onInitialWorkers(List.of("w-1"), BASE_POS, BASE_TAG);
        double totalRich = 0;
        for (int t = 0; t < 100_000; t++) {
            totalRich += richSim.tick().mineralIncome();
        }
        assertEquals(100.0, totalRich, 0.001,
                     "Rich patch still has same total minerals — just harvested faster");
    }

    @Test
    void resourceRecord_richDefaultsFalse() {
        Resource r = new Resource("m-1", new Point2d(10, 10), 1500);
        assertEquals(false, r.rich(), "3-arg constructor should default rich to false");
    }


    @Test
    void mixedPatches_totalMinerals_lessThankUniform() {
        sim.onInitialWorkers(List.of("w-1"), BASE_POS, BASE_TAG);
        double mixedTotal = 0;
        for (int t = 0; t < 200_000; t++) {
            mixedTotal += sim.tick().mineralIncome();
        }

        WorkerSimulation uniformSim     = new WorkerSimulation();
        List<Resource>   uniformPatches = new ArrayList<>();
        for (Point2d pos : MineralPatch.patchPositions(BASE_POS)) {
            uniformPatches.add(new Resource("u-" + uniformPatches.size(), pos, 1500));
        }
        uniformSim.onTownHallCompleted(BASE_TAG, BASE_POS, uniformPatches);
        uniformSim.onInitialWorkers(List.of("w-1"), BASE_POS, BASE_TAG);
        double uniformTotal = 0;
        for (int t = 0; t < 200_000; t++) {
            uniformTotal += uniformSim.tick().mineralIncome();
        }

        double expectedMixed   = 4 * 1500.0 + 4 * 750.0;
        double expectedUniform = 8 * 1500.0;
        assertEquals(expectedMixed, mixedTotal, 0.001);
        assertEquals(expectedUniform, uniformTotal, 0.001);
        assertTrue(mixedTotal < uniformTotal,
                   "Mixed base (9000) should yield less total minerals than uniform (12000)");
    }

}

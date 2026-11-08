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
        tickCount = 0;
    }

    private int tickCount = 0;

    private TickIncome tick() {
        return sim.tick(tickCount++ * (double) io.quarkmind.domain.SC2Data.LOOPS_PER_TICK);
    }


    @Test
    void singleWorker_completesFullMiningCycle() {
        sim.onInitialWorkers(List.of("w-1"), BASE_POS, BASE_TAG);
        double totalIncome = 0;
        for (int t = 0; t < 20; t++) {
            totalIncome += tick().mineralIncome();
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
            totalIncome += tick().mineralIncome();
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
            income8workers += tick().mineralIncome();
        }

        sim.reset();
        sim.onTownHallCompleted(BASE_TAG, BASE_POS);
        List<String> tags16 = IntStream.rangeClosed(1, 16)
                .mapToObj(i -> "w-" + i).toList();
        sim.onInitialWorkers(tags16, BASE_POS, BASE_TAG);
        double income16workers = 0;
        for (int t = 0; t < 100; t++) {
            income16workers += tick().mineralIncome();
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
        for (int t = 0; t < warmupTicks; t++) {tick();}
        double income       = 0;
        int    measureTicks = 200;
        for (int t = 0; t < measureTicks; t++) {
            income += tick().mineralIncome();
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
        for (int t = 0; t < 5; t++) tick();
        sim.onWorkerDied("w-1");
        double income = 0;
        for (int t = 0; t < 20; t++) {
            income += tick().mineralIncome();
        }
        assertTrue(income > 0);
    }

    @Test
    void allPatchesDepleted_workersIdle_zeroIncome() {
        sim.onInitialWorkers(List.of("w-1"), BASE_POS, BASE_TAG);
        double totalIncome = 0;
        for (int t = 0; t < 100_000; t++) {
            totalIncome += tick().mineralIncome();
        }
        assertTrue(totalIncome > 0);
        double incomeAfterDepletion = 0;
        for (int t = 0; t < 100; t++) {
            incomeAfterDepletion += tick().mineralIncome();
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

    // --- Expansion worker transfer tests ---

    @Test
    void newExpansion_transfersWorkersFromOversaturatedBase() {
        List<String> tags = IntStream.rangeClosed(1, 20)
                .mapToObj(i -> "w-" + i).toList();
        sim.onInitialWorkers(tags, BASE_POS, BASE_TAG);
        assertEquals(20, sim.mineralWorkersPerBase()[0]);

        Point2d base2Pos = new Point2d(100, 50);
        sim.onTownHallCompleted("base-2", base2Pos);

        int[] counts = sim.mineralWorkersPerBase();
        assertEquals(16, counts[0], "Main base loses 4 workers (capped transfer)");
        assertEquals(4, counts[1], "Expansion receives 4 workers");
        assertEquals(20, counts[0] + counts[1], "Total preserved");
    }

    @Test
    void newExpansion_doesNotTransferBelowMinimum() {
        List<String> tags = IntStream.rangeClosed(1, 10)
                .mapToObj(i -> "w-" + i).toList();
        sim.onInitialWorkers(tags, BASE_POS, BASE_TAG);

        Point2d base2Pos = new Point2d(100, 50);
        sim.onTownHallCompleted("base-2", base2Pos);

        int[] counts = sim.mineralWorkersPerBase();
        assertTrue(counts[0] >= 8,
                "Should not transfer below MIN_MINERAL_WORKERS_BEFORE_GAS, got " + counts[0]);
        assertEquals(10, counts[0] + counts[1],
                "Total mineral workers should be preserved");
    }

    @Test
    void newExpansion_doesNotTransferGasWorkers() {
        List<String> tags = IntStream.rangeClosed(1, 20)
                .mapToObj(i -> "w-" + i).toList();
        sim.onInitialWorkers(tags, BASE_POS, BASE_TAG);
        sim.onGasBuildingCompleted("gas-1", new Point2d(45, 50));
        int gasWorkersBefore = sim.totalGasWorkers();
        assertEquals(3, gasWorkersBefore);

        Point2d base2Pos = new Point2d(100, 50);
        sim.onTownHallCompleted("base-2", base2Pos);

        assertEquals(gasWorkersBefore, sim.totalGasWorkers(),
                "Gas workers should not be affected by expansion transfer");
    }

    @Test
    void newExpansion_firstBase_noTransfer() {
        sim.reset();
        sim.onTownHallCompleted(BASE_TAG, BASE_POS);
        assertEquals(0, sim.mineralWorkersPerBase()[0]);
    }

    @Test
    void newExpansion_transfersCappedAt4PerExpansion() {
        List<String> tags = IntStream.rangeClosed(1, 30)
                .mapToObj(i -> "w-" + i).toList();
        sim.onInitialWorkers(tags, BASE_POS, BASE_TAG);

        sim.onTownHallCompleted("base-2", new Point2d(100, 50));
        int[] afterSecond = sim.mineralWorkersPerBase();
        assertEquals(26, afterSecond[0], "Base 0 loses 4 workers");
        assertEquals(4, afterSecond[1], "Base 1 receives 4 workers");

        sim.onTownHallCompleted("base-3", new Point2d(150, 50));
        int[] afterThird = sim.mineralWorkersPerBase();
        assertEquals(22, afterThird[0], "Base 0 loses 4 more workers");
        assertEquals(4, afterThird[1], "Base 1 unchanged");
        assertEquals(4, afterThird[2], "Base 2 receives 4 workers");
        assertEquals(30, afterThird[0] + afterThird[1] + afterThird[2],
                "Total workers preserved");
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
            totalGas += tick().vespeneIncome();
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
            totalGas3 += tick().vespeneIncome();
        }

        sim.reset();
        sim.onTownHallCompleted(BASE_TAG, BASE_POS);
        List<String> tags2 = IntStream.rangeClosed(1, 11)
                                      .mapToObj(i -> "w-" + i).toList();
        sim.onInitialWorkers(tags2, BASE_POS, BASE_TAG);
        sim.onGasBuildingCompleted("gas-2w", new Point2d(45, 50));
        sim.onWorkerDied("w-1");
        double totalGas2 = 0;
        for (int t = 0; t < 200; t++) {
            totalGas2 += tick().vespeneIncome();
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
        for (int t = 0; t < 5; t++) tick();
        sim.onWorkerDied("w-1");
        double totalGas = 0;
        for (int t = 0; t < 40; t++) {
            totalGas += tick().vespeneIncome();
        }
        assertTrue(totalGas > 0);
    }

    @Test
    void gasWorkerDied_replacedFromMineralPool() {
        List<String> tags = IntStream.rangeClosed(1, 12)
                                     .mapToObj(i -> "w-" + i).toList();
        sim.onInitialWorkers(tags, BASE_POS, BASE_TAG);
        sim.onGasBuildingCompleted("gas-1", new Point2d(45, 50));

        assertEquals(3, sim.totalGasWorkers());
        assertEquals(9, sim.mineralWorkersPerBase()[0]);

        sim.onWorkerDied("w-1");
        assertEquals(3, sim.totalGasWorkers(), "Dead gas worker should be replaced from mineral pool");
        assertEquals(8, sim.mineralWorkersPerBase()[0], "Mineral pool should lose one worker to gas replacement");
    }

    @Test
    void gasWorkerDied_notReplacedWhenTooFewMineralWorkers() {
        List<String> tags = IntStream.rangeClosed(1, 10)
                                     .mapToObj(i -> "w-" + i).toList();
        sim.onInitialWorkers(tags, BASE_POS, BASE_TAG);
        sim.onGasBuildingCompleted("gas-1", new Point2d(45, 50));

        assertEquals(3, sim.totalGasWorkers());
        assertEquals(7, sim.mineralWorkersPerBase()[0]);

        sim.onWorkerDied("w-1");
        assertEquals(2, sim.totalGasWorkers(), "No replacement when mineral workers below threshold");
        assertEquals(7, sim.mineralWorkersPerBase()[0], "Mineral pool should not lose workers");
    }

    @Test
    void gasIncomeRate_3workers_matchesExpected() {
        List<String> tags = IntStream.rangeClosed(1, 12)
                                     .mapToObj(i -> "w-" + i).toList();
        sim.onInitialWorkers(tags, BASE_POS, BASE_TAG);
        sim.onGasBuildingCompleted("gas-1", new Point2d(45, 50));

        int warmupTicks = 20;
        for (int t = 0; t < warmupTicks; t++) {tick();}

        double gasIncome    = 0;
        int    measureTicks = 200;
        for (int t = 0; t < measureTicks; t++) {
            gasIncome += tick().vespeneIncome();
        }

        double avgPerTick     = gasIncome / measureTicks;
        double ticksPerMinute = 60.0 * io.quarkmind.domain.SC2Data.GAME_LOOPS_PER_SECOND / io.quarkmind.domain.SC2Data.LOOPS_PER_TICK;
        double perMinute      = avgPerTick * ticksPerMinute;
        System.out.printf("3 gas workers: %.1f gas/min, %.3f gas/tick%n", perMinute, avgPerTick);
        assertTrue(avgPerTick >= 2.8,
                   "Gas per tick should be >= 2.8 for 3 workers at 5-tile distance with sub-tick carry-forward, got " + avgPerTick);
    }

    @Test
    void gasHarvest_subTickCarryForward_increasesRate() {
        List<String> tags = IntStream.rangeClosed(1, 12)
                                     .mapToObj(i -> "w-" + i).toList();
        sim.onInitialWorkers(tags, BASE_POS, BASE_TAG);
        sim.onGasBuildingCompleted("gas-1", new Point2d(45, 50));

        int warmupTicks = 20;
        for (int t = 0; t < warmupTicks; t++) {tick();}

        double gasIncome    = 0;
        int    measureTicks = 400;
        for (int t = 0; t < measureTicks; t++) {
            gasIncome += tick().vespeneIncome();
        }

        double avgPerTick = gasIncome / measureTicks;
        assertTrue(avgPerTick >= 2.5,
                   "Sub-tick carry-forward should yield >= 2.5 gas/tick for 3 workers "
                   + "(32-loop effective harvest vs 44-loop quantised), got " + avgPerTick);
    }


    // --- Formula validator ---

    // --- Map-specific mineral patch tests ---

    @Test
    void defaultBase_hasMixedCloseAndFarPatches() {
        sim.onInitialWorkers(List.of("w-1"), BASE_POS, BASE_TAG);
        double totalIncome = 0;
        for (int t = 0; t < 200_000; t++) {
            totalIncome += tick().mineralIncome();
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
            totalIncome += explicitSim.tick(t * (double) io.quarkmind.domain.SC2Data.LOOPS_PER_TICK).mineralIncome();
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
            richIncome += richSim.tick(t * (double) io.quarkmind.domain.SC2Data.LOOPS_PER_TICK).mineralIncome();
            normalIncome += normalSim.tick(t * (double) io.quarkmind.domain.SC2Data.LOOPS_PER_TICK).mineralIncome();
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
            totalRich += richSim.tick(t * (double) io.quarkmind.domain.SC2Data.LOOPS_PER_TICK).mineralIncome();
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
            mixedTotal += tick().mineralIncome();
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
            uniformTotal += uniformSim.tick(t * (double) io.quarkmind.domain.SC2Data.LOOPS_PER_TICK).mineralIncome();
        }

        double expectedMixed   = 4 * 1500.0 + 4 * 750.0;
        double expectedUniform = 8 * 1500.0;
        assertEquals(expectedMixed, mixedTotal, 0.001);
        assertEquals(expectedUniform, uniformTotal, 0.001);
        assertTrue(mixedTotal < uniformTotal,
                   "Mixed base (9000) should yield less total minerals than uniform (12000)");
    }

}

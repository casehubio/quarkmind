package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.Point2d;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    void threeGasWorkers_lowerPerWorkerThroughputThanTwo() {
        List<String> tags3 = IntStream.rangeClosed(1, 12)
                .mapToObj(i -> "w-" + i).toList();
        sim.onInitialWorkers(tags3, BASE_POS, BASE_TAG);
        sim.onGasBuildingCompleted("gas-3w", new Point2d(45, 50));
        double totalGas3 = 0;
        for (int t = 0; t < 200; t++) {
            totalGas3 += sim.tick().vespeneIncome();
        }
        double perWorkerRate3 = totalGas3 / 3.0;

        sim.reset();
        sim.onTownHallCompleted(BASE_TAG, BASE_POS);
        List<String> tags2 = IntStream.rangeClosed(1, 12)
                .mapToObj(i -> "w-" + i).toList();
        sim.onInitialWorkers(tags2, BASE_POS, BASE_TAG);
        sim.onGasBuildingCompleted("gas-2w", new Point2d(45, 50));
        // kill 1 gas worker to get 2 gas workers
        sim.onWorkerDied("w-1");
        double totalGas2 = 0;
        for (int t = 0; t < 200; t++) {
            totalGas2 += sim.tick().vespeneIncome();
        }
        double perWorkerRate2 = totalGas2 / 2.0;

        assertTrue(perWorkerRate2 >= perWorkerRate3,
                "Per-worker gas rate with 2 workers (" + perWorkerRate2
                        + ") should be >= 3 workers (" + perWorkerRate3 + ")");
        assertTrue(totalGas3 > totalGas2,
                "Total gas from 3 workers (" + totalGas3
                        + ") should exceed 2 workers (" + totalGas2 + ")");
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
}

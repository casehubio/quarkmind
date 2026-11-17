package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.SC2Data;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Refs #685 — validate EM gas rates against Liquipedia community-verified values.
 * Map-independent: uses 5u geyser distance (closest match to Liquipedia rates).
 */
class LiquipediaGasRateCalibrationTest {

    private static final Point2d BASE_POS  = new Point2d(50, 50);
    private static final Point2d GAS_POS_1 = new Point2d(55, 50);
    private static final Point2d GAS_POS_2 = new Point2d(50, 55);

    private static final double TICKS_PER_MINUTE =
            60.0 * SC2Data.GAME_LOOPS_PER_SECOND / SC2Data.LOOPS_PER_TICK;

    private static final double LIQUIPEDIA_1W        = 61.0;
    private static final double LIQUIPEDIA_2W        = 123.0;
    private static final double LIQUIPEDIA_3W        = 163.0;
    private static final double LIQUIPEDIA_DUAL_3W   = 325.0;
    private static final double TOLERANCE_WALK_LIMITED = 0.10;
    private static final double TOLERANCE_ROTATION     = 0.05;

    @Test
    void oneWorkerGasRate() {
        double rate = measureGasRate(1, false);
        System.out.printf("[Liquipedia] 1w gas rate: %.1f gas/min (Liquipedia: %.1f, delta: %+.1f%%)%n",
                          rate, LIQUIPEDIA_1W, (rate - LIQUIPEDIA_1W) / LIQUIPEDIA_1W * 100);
        assertWithinTolerance(rate, LIQUIPEDIA_1W, TOLERANCE_WALK_LIMITED, "1w gas rate vs Liquipedia");
    }

    @Test
    void twoWorkerGasRate() {
        double rate = measureGasRate(2, false);
        System.out.printf("[Liquipedia] 2w gas rate: %.1f gas/min (Liquipedia: %.1f, delta: %+.1f%%)%n",
                          rate, LIQUIPEDIA_2W, (rate - LIQUIPEDIA_2W) / LIQUIPEDIA_2W * 100);
        assertWithinTolerance(rate, LIQUIPEDIA_2W, TOLERANCE_WALK_LIMITED, "2w gas rate vs Liquipedia");
    }

    @Test
    void threeWorkerGasRateRotationLimited() {
        double rate = measureGasRate(3, false);
        double liquipediaDelta = (rate - LIQUIPEDIA_3W) / LIQUIPEDIA_3W * 100;
        System.out.printf("[Liquipedia] 3w gas rate: %.1f gas/min (Liquipedia: %.1f, delta: %+.1f%%)%n",
                          rate, LIQUIPEDIA_3W, liquipediaDelta);
        assertWithinTolerance(rate, LIQUIPEDIA_3W, TOLERANCE_ROTATION, "3w gas rate vs Liquipedia");
    }

    @Test
    void dualGeyserNoThroughputLoss() {
        double rate3 = measureGasRate(3, false);
        double rateDual = measureGasRate(6, true);
        double throughputLoss = (1.0 - rateDual / (2.0 * rate3)) * 100;
        System.out.printf("[Liquipedia] 2×3w gas rate: %.1f gas/min (Liquipedia: %.1f, delta: %+.1f%%)%n",
                          rateDual, LIQUIPEDIA_DUAL_3W, (rateDual - LIQUIPEDIA_DUAL_3W) / LIQUIPEDIA_DUAL_3W * 100);
        System.out.printf("[Throughput] Dual-geyser loss vs 2×single: %.1f%% (Liquipedia: 0%%)%n", throughputLoss);
        assertWithinTolerance(rateDual, LIQUIPEDIA_DUAL_3W, TOLERANCE_ROTATION, "2×3w gas rate vs Liquipedia");
        assertTrue(Math.abs(throughputLoss) < 2.0,
                   String.format("Dual-geyser throughput loss %.1f%% exceeds 2%% at 5u distance", throughputLoss));
    }

    @Test
    void printRateTable() {
        double[] distances = {5.0, 5.7, 7.6, 8.5};

        System.out.println("\n=== Liquipedia Gas Rate Calibration — Distance Sweep ===\n");

        for (double dist : distances) {
            Point2d gasPos1 = new Point2d((float) (50 + dist), 50);
            Point2d gasPos2 = new Point2d(50, (float) (50 + dist));

            System.out.printf("Distance: %.1fu (center-to-center)%n", dist);
            System.out.printf("%-20s | %-12s | %-12s | %-8s%n",
                              "Config", "EM gas/min", "Liquipedia", "Delta");
            System.out.println("-".repeat(60));

            double rate1 = measureGasRateAt(1, false, gasPos1, null);
            double rate2 = measureGasRateAt(2, false, gasPos1, null);
            double rate3 = measureGasRateAt(3, false, gasPos1, null);
            double rateDual = measureGasRateAt(6, true, gasPos1, gasPos2);

            printRow("1 worker", rate1, LIQUIPEDIA_1W);
            printRow("2 workers", rate2, LIQUIPEDIA_2W);
            printRow("3 workers", rate3, LIQUIPEDIA_3W);
            printRow("2 geysers × 3w", rateDual, LIQUIPEDIA_DUAL_3W);

            double throughputLoss = (1.0 - rateDual / (2.0 * rate3)) * 100;
            System.out.printf("Dual-geyser throughput loss: %.1f%%%n%n", throughputLoss);
        }
    }

    private void printRow(String config, double emRate, double liquipedia) {
        double delta = (emRate - liquipedia) / liquipedia * 100;
        System.out.printf("%-20s | %-12.1f | %-12.1f | %+.1f%%%n", config, emRate, liquipedia, delta);
    }

    private void assertWithinTolerance(double actual, double expected, double tolerance, String label) {
        double delta = Math.abs(actual - expected) / expected;
        assertTrue(delta <= tolerance,
                   String.format("%s: %.1f gas/min is %.1f%% from %.1f (tolerance ±%.0f%%)",
                                 label, actual, delta * 100, expected, tolerance * 100));
    }

    private double measureGasRate(int gasWorkerCount, boolean dualGeyser) {
        return measureGasRateAt(gasWorkerCount, dualGeyser, GAS_POS_1,
                                dualGeyser ? GAS_POS_2 : null);
    }

    private double measureGasRateAt(int gasWorkerCount, boolean dualGeyser,
                                    Point2d gasPos1, Point2d gasPos2) {
        WorkerSimulation sim = new WorkerSimulation();
        sim.onTownHallCompleted("base-1", BASE_POS);

        List<String> initial = IntStream.rangeClosed(1, 12)
                                        .mapToObj(i -> "w-" + i).toList();
        sim.onInitialWorkers(initial, BASE_POS, "base-1");

        sim.onGasBuildingCompleted("gas-1", gasPos1);
        if (dualGeyser && gasPos2 != null) {
            sim.onGasBuildingCompleted("gas-2", gasPos2);
        }
        sim.syncTotalGasWorkers(gasWorkerCount);

        int tickCount = 0;
        int warmup = 30;
        for (int t = 0; t < warmup; t++) {
            sim.tick(tickCount++ * (double) SC2Data.LOOPS_PER_TICK);
        }

        double income = 0;
        int measure = 400;
        for (int t = 0; t < measure; t++) {
            income += sim.tick(tickCount++ * (double) SC2Data.LOOPS_PER_TICK).vespeneIncome();
        }

        return income / measure * TICKS_PER_MINUTE;
    }
}

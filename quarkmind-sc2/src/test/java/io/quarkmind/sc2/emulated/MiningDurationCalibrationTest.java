package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.SC2Data;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

/**
 * Joint calibration: sweeps mining duration to find the value where both
 * the saturation curve drop matches SC2 (~40-50% at 16→24) AND the
 * per-worker rate at 16 workers is realistic (~55 min/min).
 *
 * Refs #583
 */
@Tag("report")
class MiningDurationCalibrationTest {

    private static final Point2d BASE_POS = new Point2d(50, 50);
    private static final String BASE_TAG = "base-1";
    private static final double TICKS_PER_MINUTE =
        60.0 * SC2Data.GAME_LOOPS_PER_SECOND / SC2Data.LOOPS_PER_TICK;

    @Test
    void jointCalibrationSweep() {
        System.out.printf("ACCELERATION=%.2f, DECELERATION=%.2f, MAX_SPEED=%.2f, LOOPS_PER_TICK=%d%n",
            SimulatedWorker.ACCELERATION, SimulatedWorker.DECELERATION,
            SimulatedWorker.MAX_SPEED, SimulatedWorker.LOOPS_PER_TICK);

        SimulatedWorker tracer = new SimulatedWorker("tracer", new Point2d(50, 50), 0);
        tracer.targetPosition = new Point2d(56, 50);
        tracer.currentSpeed = 0;
        int tripLoops = 0;
        while (!tracer.isAtTarget()) { tracer.moveLoops(1); tripLoops++; }
        System.out.printf("Single 6-unit trip: %d loops (%.2f seconds)%n%n",
            tripLoops, tripLoops / SC2Data.GAME_LOOPS_PER_SECOND);

        double[] durations = {5, 8, 10, 12, 15, 18, 20, 22, 25, 30, 35, 40, 44.576};

        System.out.println("=== Joint Calibration: Mining Duration vs Saturation + Rate ===\n");
        System.out.printf("%-10s | %-12s | %-12s | %-12s | %-12s | %-12s | %-12s | %-7s%n",
            "Duration", "8w total", "16w total", "24w total",
            "16w/worker", "24w/worker", "Drop 16→24", "Seconds");
        System.out.println("-".repeat(105));

        for (double duration : durations) {
            SimulatedWorker.setMiningDurationLoops(duration);
            try {
                double rate8 = measureRate(8);
                double rate16 = measureRate(16);
                double rate24 = measureRate(24);

                double perWorker16 = rate16 / 16;
                double perWorker24 = rate24 / 24;
                double drop = (1.0 - perWorker24 / perWorker16) * 100;
                double seconds = duration / SC2Data.GAME_LOOPS_PER_SECOND;

                System.out.printf("%-10.1f | %-12.1f | %-12.1f | %-12.1f | %-12.1f | %-12.1f | %-11.1f%% | %-7.2f%n",
                    duration, rate8, rate16, rate24,
                    perWorker16, perWorker24, drop, seconds);
            } finally {
                SimulatedWorker.resetMiningDuration();
            }
        }

        System.out.println("\n=== SC2 Reference Values ===");
        System.out.println("16 workers: ~930 total, ~55-58 per worker");
        System.out.println("24 workers: ~1056 total, ~44 per worker (Liquipedia: 'about 70% of second worker')");
        System.out.println("Saturation drop 16→24: ~20-25% (per worker rate decline)");
        System.out.println("Note: 40-50% drop was from earlier analysis. SC2 actual saturation");
        System.out.println("      is gentler because 2 workers per patch alternate efficiently.");

        System.out.println("\n=== Extended Saturation Curve at Each Duration ===\n");
        double[] detailDurations = {5, 15, 22, 30, 44.576};
        int[] workerCounts = {4, 8, 12, 16, 20, 24, 28, 32};

        for (double duration : detailDurations) {
            SimulatedWorker.setMiningDurationLoops(duration);
            try {
                System.out.printf("--- Mining Duration = %.1f loops (%.2fs) ---%n", duration,
                    duration / SC2Data.GAME_LOOPS_PER_SECOND);
                System.out.printf("%-10s | %-12s | %-12s | %-14s%n",
                    "Workers", "Total/min", "Per worker", "Marginal");
                System.out.println("-".repeat(55));

                double prevPerWorker = 0;
                for (int count : workerCounts) {
                    double rate = measureRate(count);
                    double perWorker = rate / count;
                    String marginal = prevPerWorker > 0
                        ? String.format("%.1f%%", (1.0 - perWorker / prevPerWorker) * 100)
                        : "-";
                    System.out.printf("%-10d | %-12.1f | %-12.1f | %-14s%n",
                        count, rate, perWorker, marginal);
                    prevPerWorker = perWorker;
                }
                System.out.println();
            } finally {
                SimulatedWorker.resetMiningDuration();
            }
        }
    }

    private double measureRate(int workerCount) {
        WorkerSimulation sim = new WorkerSimulation();
        sim.onTownHallCompleted(BASE_TAG, BASE_POS);
        int tickCount = 0;

        List<String> initial = IntStream.rangeClosed(1, Math.min(workerCount, 12))
            .mapToObj(i -> "w-" + i).toList();
        sim.onInitialWorkers(initial, BASE_POS, BASE_TAG);
        for (int i = 12; i < workerCount; i++) {
            sim.onWorkerProduced("w-" + (i + 1), BASE_POS);
        }

        int warmup = 30;
        for (int t = 0; t < warmup; t++) {
            sim.tick(tickCount++ * (double) SC2Data.LOOPS_PER_TICK);
        }

        double income = 0;
        int measure = 400;
        for (int t = 0; t < measure; t++) {
            income += sim.tick(tickCount++ * (double) SC2Data.LOOPS_PER_TICK).mineralIncome();
        }

        return income / measure * TICKS_PER_MINUTE;
    }
}

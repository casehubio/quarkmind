package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.SC2Data;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("diagnostic")
class MiningCycleDiagnosticTest {

    private static final Point2d BASE_POS = new Point2d(50, 50);
    private static final String BASE_TAG = "base-1";

    private WorkerSimulation sim;
    private int tickCount;

    @BeforeEach
    void setUp() {
        sim = new WorkerSimulation();
        sim.onTownHallCompleted(BASE_TAG, BASE_POS);
        tickCount = 0;
    }

    private TickIncome tick() {
        return sim.tick(tickCount++ * (double) SC2Data.LOOPS_PER_TICK);
    }

    @Test
    void singleWorkerCycleTrace() {
        sim.onInitialWorkers(List.of("w-1"), BASE_POS, BASE_TAG);

        SimulatedWorker w = sim.worker("w-1");
        System.out.println("=== Single Worker Mining Cycle Trace ===");
        System.out.println("Config: MINING_DURATION=" + SimulatedWorker.MINING_DURATION_LOOPS
                + " DEPOSIT_DURATION=" + SimulatedWorker.DEPOSIT_DURATION_LOOPS
                + " ACCEL=" + SimulatedWorker.ACCELERATION
                + " DECEL=" + SimulatedWorker.DECELERATION
                + " MAX_SPEED=" + SimulatedWorker.MAX_SPEED
                + " ARRIVAL_THRESHOLD=" + SimulatedWorker.ARRIVAL_THRESHOLD);
        System.out.println("Patch distance from base: " + BASE_POS.distanceTo(w.assignedPatch.position));
        System.out.println();
        System.out.printf("%-6s %-8s %-22s %-12s %-10s %-10s %-12s %-10s%n",
                "Tick", "Loop", "State", "Cargo", "DistTgt", "Speed", "PhaseEnd", "Income");
        System.out.println("-".repeat(100));

        double totalIncome = 0;
        WorkerTask prevState = null;
        int cycleCount = 0;
        double cycleStartLoop = 0;
        List<Double> cycleLengths = new ArrayList<>();

        for (int t = 0; t < 200; t++) {
            WorkerTask stateBefore = w.task;
            double posBefore = w.position.distanceTo(w.targetPosition != null ? w.targetPosition : w.position);
            TickIncome income = tick();
            totalIncome += income.mineralIncome();

            double distToTarget = w.targetPosition != null ? w.position.distanceTo(w.targetPosition) : 0;
            boolean stateChanged = prevState != null && w.task != prevState;
            boolean deposited = income.mineralIncome() > 0;

            if (stateChanged || deposited || t < 5 || t % 10 == 0) {
                String marker = stateChanged ? " <--" : (deposited ? " $$$" : "");
                System.out.printf("%-6d %-8.0f %-22s %-12.1f %-10.2f %-10.4f %-12.1f %-10.1f%s%n",
                        t, t * (double) SC2Data.LOOPS_PER_TICK, w.task,
                        w.cargo, distToTarget, w.currentSpeed,
                        w.phaseEndLoop, income.mineralIncome(), marker);
            }

            if (deposited && cycleCount > 0) {
                double currentLoop = t * (double) SC2Data.LOOPS_PER_TICK;
                double cycleLength = currentLoop - cycleStartLoop;
                cycleLengths.add(cycleLength);
            }
            if (deposited) {
                cycleStartLoop = t * (double) SC2Data.LOOPS_PER_TICK;
                cycleCount++;
            }

            prevState = w.task;
        }

        double totalLoops = 200.0 * SC2Data.LOOPS_PER_TICK;
        double totalSeconds = totalLoops / SC2Data.GAME_LOOPS_PER_SECOND;
        double ratePerMin = totalIncome / totalSeconds * 60;

        System.out.println();
        System.out.println("=== Summary ===");
        System.out.printf("Total income: %.0f minerals in %.0f loops (%.1f sec)%n",
                totalIncome, totalLoops, totalSeconds);
        System.out.printf("Rate: %.1f minerals/min%n", ratePerMin);
        System.out.printf("Cycles completed: %d%n", cycleCount);
        if (!cycleLengths.isEmpty()) {
            double avgCycle = cycleLengths.stream().mapToDouble(d -> d).average().orElse(0);
            double minCycle = cycleLengths.stream().mapToDouble(d -> d).min().orElse(0);
            double maxCycle = cycleLengths.stream().mapToDouble(d -> d).max().orElse(0);
            System.out.printf("Cycle lengths: avg=%.1f min=%.1f max=%.1f loops%n",
                    avgCycle, minCycle, maxCycle);
            System.out.printf("Theoretical rate at avg cycle: %.1f minerals/min%n",
                    5.0 / avgCycle * SC2Data.GAME_LOOPS_PER_SECOND * 60);
        }

        assertTrue(totalIncome > 0);
    }

    @Test
    void singleWorkerPerLoopTrace() {
        sim.onInitialWorkers(List.of("w-1"), BASE_POS, BASE_TAG);
        SimulatedWorker w = sim.worker("w-1");

        System.out.println("=== Single Worker Per-Loop Trace (first 3 cycles) ===");
        System.out.println("Patch pos: " + w.assignedPatch.position
                + "  distance: " + BASE_POS.distanceTo(w.assignedPatch.position));
        System.out.printf("%-6s %-22s %-10s %-10s %-10s %-10s%n",
                "Loop", "State", "DistTgt", "Speed", "Cargo", "Income");
        System.out.println("-".repeat(75));

        int deposits = 0;
        for (int loop = 0; deposits < 3 && loop < 1000; loop++) {
            WorkerTask stateBefore = w.task;
            double distBefore = w.targetPosition != null ? w.position.distanceTo(w.targetPosition) : 0;

            TickIncome income = sim.tick(loop);

            double distAfter = w.targetPosition != null ? w.position.distanceTo(w.targetPosition) : 0;
            boolean stateChanged = w.task != stateBefore;
            boolean deposited = income.mineralIncome() > 0;

            if (stateChanged || deposited || loop < 3 || loop % 20 == 0) {
                String marker = deposited ? " $$$" : (stateChanged ? " <--" : "");
                System.out.printf("%-6d %-22s %-10.3f %-10.4f %-10.1f %-10.1f%s%n",
                        loop, w.task, distAfter, w.currentSpeed,
                        w.cargo, income.mineralIncome(), marker);
            }

            if (deposited) deposits++;
        }

        assertTrue(deposits >= 3);
    }

    @Test
    void twelveWorkerContentionAnalysis() {
        List<String> tags = IntStream.rangeClosed(1, 12)
                .mapToObj(i -> "w-" + i).toList();
        sim.onInitialWorkers(tags, BASE_POS, BASE_TAG);

        System.out.println("=== 12-Worker Contention Analysis (8 patches, MAX_MINERS=1) ===");

        int waitingTicks = 0;
        int totalWorkerTicks = 0;
        double totalIncome = 0;

        for (int t = 0; t < 300; t++) {
            for (SimulatedWorker w : sim.allWorkers()) {
                if (w.task == WorkerTask.MINING_MINERALS && w.isAtTarget()
                        && !w.assignedPatch.miners.contains(w)) {
                    waitingTicks++;
                }
                totalWorkerTicks++;
            }
            TickIncome income = tick();
            totalIncome += income.mineralIncome();
        }

        double totalLoops = 300.0 * SC2Data.LOOPS_PER_TICK;
        double totalSeconds = totalLoops / SC2Data.GAME_LOOPS_PER_SECOND;
        double ratePerWorker = totalIncome / 12.0 / totalSeconds * 60;

        System.out.println();
        System.out.println("=== Results ===");
        System.out.printf("Total income: %.0f minerals in %.1f sec%n", totalIncome, totalSeconds);
        System.out.printf("Per-worker rate: %.1f minerals/min%n", ratePerWorker);
        System.out.printf("Waiting worker-ticks: %d / %d (%.1f%%)%n",
                waitingTicks, totalWorkerTicks,
                100.0 * waitingTicks / totalWorkerTicks);
        System.out.printf("SC2 reference: 57.9 minerals/min/worker (12w Protoss)%n");
        System.out.printf("Gap: %.1f minerals/min/worker (%.1f%%)%n",
                57.9 - ratePerWorker, (57.9 - ratePerWorker) / 57.9 * 100);

        assertTrue(totalIncome > 0);
    }

    @Test
    void rateComparison_1v8v12workers() {
        System.out.println("=== Saturation Curve: 1-30 workers on 8 patches (300 ticks each) ===");
        System.out.printf("%-10s %-8s %-15s %-18s %-12s %-10s%n",
                          "Workers", "W/Patch", "Total Income", "Rate/worker/min", "Efficiency", "SC2 ref");
        System.out.println("-".repeat(80));

        double[] sc2Rates = {0, 64.1, 64.1, 64.1, 64.1, 64.1, 64.1, 64.1, 57.9,
                             0, 0, 0, 57.9, 0, 0, 0, 57.9, 0, 0, 0, 0, 0, 0, 48.6};

        for (int workerCount : new int[]{1, 2, 4, 8, 10, 12, 14, 16, 20, 23, 24, 30}) {
            WorkerSimulation s = new WorkerSimulation();
            s.onTownHallCompleted(BASE_TAG, BASE_POS);
            List<String> tags = IntStream.rangeClosed(1, workerCount)
                                         .mapToObj(i -> "w-" + i).toList();
            s.onInitialWorkers(tags, BASE_POS, BASE_TAG);

            double totalIncome = 0;
            for (int t = 0; t < 300; t++) {
                totalIncome += s.tick(t * (double) SC2Data.LOOPS_PER_TICK).mineralIncome();
            }

            double totalSeconds    = 300.0 * SC2Data.LOOPS_PER_TICK / SC2Data.GAME_LOOPS_PER_SECOND;
            double ratePerWorker   = totalIncome / workerCount / totalSeconds * 60;
            double workersPerPatch = workerCount / 8.0;
            String sc2Ref = (workerCount < sc2Rates.length && sc2Rates[workerCount] > 0)
                            ? String.format("%.1f", sc2Rates[workerCount]) : "—";

            System.out.printf("%-10d %-8.1f %-15.0f %-18.1f %-12.1f%% %-10s%n",
                              workerCount, workersPerPatch, totalIncome, ratePerWorker,
                              100.0 * ratePerWorker / 64.1, sc2Ref);
        }

        System.out.println();
        System.out.println("SC2 reference points: 12w=57.9, 23w=48.6 minerals/min/worker (TorchesAIE_v4)");
        System.out.println("Efficiency is relative to 1-worker baseline (64.1/min at 6.0u)");
    }

    @Test
    void patchDistanceSweep() {
        System.out.println("=== Patch Distance Sweep: 12 workers, 300 ticks ===");
        System.out.println("SC2 target: 3460 minerals at tick 304 (57.9/min/worker)");
        System.out.printf("%-10s %-18s %-18s %-10s%n", "Distance", "Total Income", "Rate/worker/min", "vs SC2");
        System.out.println("-".repeat(60));

        for (double dist : new double[]{6.0, 6.2, 6.4, 6.5, 6.55, 6.6, 6.65, 6.7, 6.75, 6.8, 6.9, 7.0}) {
            WorkerSimulation                   s       = new WorkerSimulation();
            Point2d                            basePos = new Point2d(50, 50);
            List<io.quarkmind.domain.Resource> patches = new java.util.ArrayList<>();
            for (int i = 0; i < MineralPatch.PATCHES_PER_BASE; i++) {
                double angle = Math.PI * (0.2 + 0.6 * i / (MineralPatch.PATCHES_PER_BASE - 1));
                float  px    = basePos.x() + (float) (dist * Math.cos(angle));
                float  py    = basePos.y() + (float) (dist * Math.sin(angle));
                patches.add(new io.quarkmind.domain.Resource(
                        "m-" + i, new Point2d(px, py), 1500, false));
            }
            s.onTownHallCompleted(BASE_TAG, basePos, patches);
            List<String> tags = IntStream.rangeClosed(1, 12)
                                         .mapToObj(i -> "w-" + i).toList();
            s.onInitialWorkers(tags, basePos, BASE_TAG);

            double totalIncome = 0;
            for (int t = 0; t < 304; t++) {
                totalIncome += s.tick(t * (double) SC2Data.LOOPS_PER_TICK).mineralIncome();
            }

            double totalSeconds  = 304.0 * SC2Data.LOOPS_PER_TICK / SC2Data.GAME_LOOPS_PER_SECOND;
            double ratePerWorker = totalIncome / 12.0 / totalSeconds * 60;
            double pctDiff       = (totalIncome - 3460) / 3460 * 100;

            System.out.printf("%-10.1f %-18.0f %-18.1f %-10.1f%%%n",
                              dist, totalIncome, ratePerWorker, pctDiff);
        }
    }

}

package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.SC2Data;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("diagnostic")
class GasWorkerCycleDiagnosticTest {

    private static final Point2d BASE_POS = new Point2d(50, 50);
    private static final String BASE_TAG = "base-1";
    private static final Point2d GAS_POS_1 = new Point2d(57.0f, 53.0f);
    private static final Point2d GAS_POS_2 = new Point2d(57.0f, 47.0f);

    private WorkerSimulation sim;
    private int tickCount;

    @BeforeEach
    void setUp() {
        SimulatedWorker.resetToDefaults();
        sim = new WorkerSimulation();
        sim.onTownHallCompleted(BASE_TAG, BASE_POS);
        tickCount = 0;
    }

    @AfterEach
    void tearDown() {
        SimulatedWorker.resetToDefaults();
    }

    private TickIncome tick() {
        return sim.tick(tickCount++ * (double) SC2Data.LOOPS_PER_TICK);
    }

    @Test
    void singleGeyser3wCycleBreakdown() {
        List<String> tags = IntStream.rangeClosed(1, 12)
                .mapToObj(i -> "w-" + i).toList();
        sim.onInitialWorkers(tags, BASE_POS, BASE_TAG);
        sim.onGasBuildingCompleted("gas-1", GAS_POS_1);

        List<SimulatedWorker> gasWorkers = findGasWorkers();
        assertFalse(gasWorkers.isEmpty(), "No gas workers assigned");

        System.out.println("=== Gas Worker Cycle Diagnostic: 3w on 1 geyser ===");
        printConfig(GAS_POS_1);
        runCycleDiagnostic(gasWorkers, 300);
    }

    @Test
    void dualGeyser6wCycleBreakdown() {
        List<String> tags = IntStream.rangeClosed(1, 12)
                .mapToObj(i -> "w-" + i).toList();
        sim.onInitialWorkers(tags, BASE_POS, BASE_TAG);
        sim.onGasBuildingCompleted("gas-1", GAS_POS_1);
        sim.onGasBuildingCompleted("gas-2", GAS_POS_2);

        List<SimulatedWorker> gasWorkers = findGasWorkers();
        assertFalse(gasWorkers.isEmpty(), "No gas workers assigned");

        System.out.println("=== Gas Worker Cycle Diagnostic: 6w on 2 geysers ===");
        printConfig(GAS_POS_1);
        System.out.printf("Geyser 2 distance from TH: %.2f (centre-to-centre)%n",
                BASE_POS.distanceTo(GAS_POS_2));
        runCycleDiagnostic(gasWorkers, 300);
    }

    @Test
    void singleWorkerPerTickTrace() {
        List<String> tags = IntStream.rangeClosed(1, 12)
                .mapToObj(i -> "w-" + i).toList();
        sim.onInitialWorkers(tags, BASE_POS, BASE_TAG);
        sim.onGasBuildingCompleted("gas-1", GAS_POS_1);

        SimulatedWorker w = findGasWorkers().get(0);

        System.out.println("=== Single Gas Worker Per-Tick Trace (first 3 deposits) ===");
        printConfig(GAS_POS_1);
        System.out.printf("%-6s %-8s %-20s %-10s %-10s %-10s %-10s %-8s%n",
                "Tick", "Loop", "State", "DistTgt", "Speed", "Cargo", "Income", "Inside");
        System.out.println("-".repeat(90));

        int deposits = 0;
        for (int t = 0; deposits < 3 && t < 500; t++) {
            WorkerTask stateBefore = w.task;
            boolean insideBefore = w.assignedGasSlot.insideWorkers.contains(w);

            TickIncome income = tick();

            double distAfter = w.targetPosition != null ? w.position.distanceTo(w.targetPosition) : 0;
            boolean stateChanged = w.task != stateBefore;
            boolean insideAfter = w.assignedGasSlot.insideWorkers.contains(w);
            boolean enteredBuilding = !insideBefore && insideAfter;
            boolean exitedBuilding = insideBefore && !insideAfter;
            boolean thisWorkerDeposited = stateBefore == WorkerTask.RETURNING_GAS && w.cargo == 0;

            if (stateChanged || thisWorkerDeposited || enteredBuilding || exitedBuilding || t < 3 || t % 20 == 0) {
                double tickLoop = t * (double) SC2Data.LOOPS_PER_TICK;
                String marker = thisWorkerDeposited ? " $$$" : (enteredBuilding ? " >>>" : (exitedBuilding ? " <<<" : (stateChanged ? " <--" : "")));
                System.out.printf("%-6d %-8.0f %-20s %-10.3f %-10.4f %-10.1f %-10.1f %-8s%s%n",
                        t, tickLoop, w.task, distAfter, w.currentSpeed,
                        w.cargo, income.vespeneIncome(),
                        insideAfter ? "YES" : "no", marker);
            }

            if (thisWorkerDeposited) deposits++;
        }

        assertTrue(deposits >= 3);
    }

    private List<SimulatedWorker> findGasWorkers() {
        List<SimulatedWorker> gasWorkers = new ArrayList<>();
        for (SimulatedWorker w : sim.allWorkers()) {
            if (w.assignedGasSlot != null) gasWorkers.add(w);
        }
        return gasWorkers;
    }

    private void printConfig(Point2d gasPos) {
        System.out.printf("Config: GAS_HARVEST_DURATION=%.1f  GAS_APPROACH_DOCKING=%.1f  DEPOSIT_DURATION=%.1f%n",
                SimulatedWorker.GAS_HARVEST_DURATION_LOOPS,
                SimulatedWorker.GAS_APPROACH_DOCKING_LOOPS,
                SimulatedWorker.DEPOSIT_DURATION_LOOPS);
        System.out.printf("        ACCEL=%.1f  DECEL=%.1f  MAX_SPEED=%.4f  ARRIVAL_THRESHOLD=%.1f%n",
                SimulatedWorker.ACCELERATION, SimulatedWorker.DECELERATION,
                SimulatedWorker.MAX_SPEED, SimulatedWorker.ARRIVAL_THRESHOLD);
        System.out.printf("        GAS_DEPOSIT_LOCK=%.1f  ENTRY_LOCK_DURATION=%.1f  MAX_INSIDE=%d%n",
                WorkerSimulation.GAS_DEPOSIT_LOCK_LOOPS,
                GasSlot.ENTRY_LOCK_DURATION, GasSlot.MAX_INSIDE);
        System.out.printf("        LOOPS_PER_TICK=%d%n", SC2Data.LOOPS_PER_TICK);
        System.out.printf("Geyser 1 distance from TH: %.2f (centre-to-centre)%n",
                BASE_POS.distanceTo(gasPos));
        System.out.println();
    }

    private void runCycleDiagnostic(List<SimulatedWorker> gasWorkers, int ticks) {
        Map<String, List<CycleRecord>> workerCycles = new LinkedHashMap<>();
        Map<String, PhaseAccumulator> activePhases = new LinkedHashMap<>();

        for (SimulatedWorker w : gasWorkers) {
            workerCycles.put(w.tag, new ArrayList<>());
            activePhases.put(w.tag, new PhaseAccumulator());
        }

        double totalGas = 0;
        for (int t = 0; t < ticks; t++) {
            TickIncome income = tick();
            totalGas += income.vespeneIncome();

            for (SimulatedWorker w : gasWorkers) {
                PhaseAccumulator acc = activePhases.get(w.tag);
                CycleRecord cycle = acc.observe(w);
                if (cycle != null) {
                    workerCycles.get(w.tag).add(cycle);
                }
            }
        }

        for (SimulatedWorker w : gasWorkers) {
            List<CycleRecord> cycles = workerCycles.get(w.tag);
            String geyserTag = w.assignedGasSlot != null ? w.assignedGasSlot.buildingTag : "?";
            System.out.printf("%n--- Worker %s (geyser %s) --- %d complete cycles ---%n", w.tag, geyserTag, cycles.size());
            System.out.printf("%-6s %-12s %-10s %-12s %-12s %-10s %-10s%n",
                    "Cycle", "WalkToGas", "WaitEntry", "InsideGas", "WalkToTH", "Deposit", "Total");
            System.out.println("-".repeat(80));

            for (int i = 0; i < cycles.size(); i++) {
                CycleRecord c = cycles.get(i);
                System.out.printf("%-6d %-12d %-10d %-12d %-12d %-10d %-10d%n",
                        i + 1, c.walkToGas, c.waitAtGeyser, c.insideGeyser, c.walkToTH, c.deposit, c.total());
            }

            if (cycles.size() >= 2) {
                List<CycleRecord> steady = cycles.subList(1, cycles.size());
                double avgTotal = steady.stream().mapToInt(CycleRecord::total).average().orElse(0);
                double avgWalkToGas = steady.stream().mapToInt(c -> c.walkToGas).average().orElse(0);
                double avgWait = steady.stream().mapToInt(c -> c.waitAtGeyser).average().orElse(0);
                double avgInside = steady.stream().mapToInt(c -> c.insideGeyser).average().orElse(0);
                double avgWalkToTH = steady.stream().mapToInt(c -> c.walkToTH).average().orElse(0);
                double avgDeposit = steady.stream().mapToInt(c -> c.deposit).average().orElse(0);

                double avgTotalLoops = avgTotal * SC2Data.LOOPS_PER_TICK;
                double avgInsideLoops = avgInside * SC2Data.LOOPS_PER_TICK;

                System.out.printf("%-6s %-12.1f %-10.1f %-12.1f %-12.1f %-10.1f %-10.1f%n",
                        "AVG", avgWalkToGas, avgWait, avgInside, avgWalkToTH, avgDeposit, avgTotal);
                System.out.printf("  (in loops: walk=%.0f wait=%.0f inside=%.0f return=%.0f deposit=%.0f total=%.0f)%n",
                        avgWalkToGas * SC2Data.LOOPS_PER_TICK,
                        avgWait * SC2Data.LOOPS_PER_TICK,
                        avgInsideLoops,
                        avgWalkToTH * SC2Data.LOOPS_PER_TICK,
                        avgDeposit * SC2Data.LOOPS_PER_TICK,
                        avgTotalLoops);

                double theoreticalRotation = 3.0 * avgInsideLoops;
                System.out.printf("  Theoretical rotation-limited cycle (3×inside): %.0f loops%n", theoreticalRotation);
                System.out.printf("  Actual avg cycle: %.0f loops  (%.1f%% of theoretical)%n",
                        avgTotalLoops, avgTotalLoops / theoreticalRotation * 100);
            }
        }

        double totalLoops = ticks * (double) SC2Data.LOOPS_PER_TICK;
        double totalSeconds = totalLoops / SC2Data.GAME_LOOPS_PER_SECOND;
        int gasWorkerCount = gasWorkers.size();
        double ratePerWorker = totalGas / gasWorkerCount / totalSeconds * 60;

        System.out.println();
        System.out.println("=== Summary ===");
        System.out.printf("Total vespene: %.0f in %d ticks (%d workers)%n", totalGas, ticks, gasWorkerCount);
        System.out.printf("Rate: %.1f vespene/min/worker  (SC2 target: 44.3)%n", ratePerWorker);

        assertFalse(workerCycles.values().stream().allMatch(List::isEmpty),
                "Expected at least one complete gas cycle");
    }

    enum GasPhase { WALK_TO_GAS, WAIT_ENTRY, INSIDE_GAS, WALK_TO_TH, DEPOSIT }

    private static class PhaseAccumulator {
        private GasPhase lastPhase;
        private int walkToGasTicks;
        private int waitAtGeyserTicks;
        private int insideGeyserTicks;
        private int walkToTHTicks;
        private int depositTicks;
        private boolean hadFirstCycle;

        CycleRecord observe(SimulatedWorker w) {
            GasPhase phase = classify(w);
            CycleRecord result = null;

            if (phase == GasPhase.WALK_TO_GAS && lastPhase != null
                    && lastPhase != GasPhase.WALK_TO_GAS && lastPhase != GasPhase.WAIT_ENTRY) {
                if (hadFirstCycle) {
                    result = new CycleRecord(walkToGasTicks, waitAtGeyserTicks,
                            insideGeyserTicks, walkToTHTicks, depositTicks);
                }
                hadFirstCycle = true;
                resetPhases();
            }

            switch (phase) {
                case WALK_TO_GAS -> walkToGasTicks++;
                case WAIT_ENTRY -> waitAtGeyserTicks++;
                case INSIDE_GAS -> insideGeyserTicks++;
                case WALK_TO_TH -> walkToTHTicks++;
                case DEPOSIT -> depositTicks++;
            }

            lastPhase = phase;
            return result;
        }

        private void resetPhases() {
            walkToGasTicks = 0;
            waitAtGeyserTicks = 0;
            insideGeyserTicks = 0;
            walkToTHTicks = 0;
            depositTicks = 0;
        }

        static GasPhase classify(SimulatedWorker w) {
            if (w.task == WorkerTask.HARVESTING_GAS) {
                if (w.assignedGasSlot != null && w.assignedGasSlot.insideWorkers.contains(w)) {
                    return GasPhase.INSIDE_GAS;
                }
                if (w.isAtTarget()) {
                    return GasPhase.WAIT_ENTRY;
                }
                return GasPhase.WALK_TO_GAS;
            }
            if (w.task == WorkerTask.RETURNING_GAS) {
                return GasPhase.WALK_TO_TH;
            }
            if (w.task == WorkerTask.DEPOSITING_GAS) {
                return GasPhase.DEPOSIT;
            }
            return GasPhase.WALK_TO_GAS;
        }
    }

    record CycleRecord(int walkToGas, int waitAtGeyser, int insideGeyser, int walkToTH, int deposit) {
        int total() { return walkToGas + waitAtGeyser + insideGeyser + walkToTH + deposit; }
    }
}

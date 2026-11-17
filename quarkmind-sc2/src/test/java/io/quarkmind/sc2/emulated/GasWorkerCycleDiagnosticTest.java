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
    void perGeyserIncomeTracking() {
        List<String> tags = IntStream.rangeClosed(1, 12)
                                     .mapToObj(i -> "w-" + i).toList();
        sim.onInitialWorkers(tags, BASE_POS, BASE_TAG);
        sim.onGasBuildingCompleted("gas-1", GAS_POS_1);
        sim.onGasBuildingCompleted("gas-2", GAS_POS_2);

        List<SimulatedWorker> gasWorkers = findGasWorkers();
        assertFalse(gasWorkers.isEmpty(), "No gas workers assigned");

        double gas1Income        = 0, gas2Income = 0;
        int    gas1Deliveries    = 0, gas2Deliveries = 0;
        int    gas1FirstTick     = -1, gas2FirstTick = -1;
        int    depositLockBlocks = 0;

        System.out.println("=== Per-Geyser Gas Income: 6w on 2 geysers ===");
        System.out.printf("Gas-1 pos=%s, dist=%.2f%n", GAS_POS_1, BASE_POS.distanceTo(GAS_POS_1));
        System.out.printf("Gas-2 pos=%s, dist=%.2f%n", GAS_POS_2, BASE_POS.distanceTo(GAS_POS_2));
        System.out.printf("Workers assigned: %d (gas-1: %d, gas-2: %d)%n",
                          gasWorkers.size(),
                          gasWorkers.stream().filter(w -> "gas-1".equals(w.assignedGasSlot.buildingTag)).count(),
                          gasWorkers.stream().filter(w -> "gas-2".equals(w.assignedGasSlot.buildingTag)).count());
        System.out.println();
        System.out.printf("%-6s | %-8s %-4s | %-8s %-4s | %-8s | %-6s%n",
                          "tick", "gas-1", "#d", "gas-2", "#d", "total", "delta");

        for (int t = 0; t < 305; t++) {
            Map<String, Double>  preCargo    = new LinkedHashMap<>();
            Map<String, Boolean> wasAtTarget = new LinkedHashMap<>();
            for (SimulatedWorker w : gasWorkers) {
                preCargo.put(w.tag, w.cargo);
                wasAtTarget.put(w.tag, w.task == WorkerTask.RETURNING_GAS && w.isAtTarget());
            }

            tick();

            for (SimulatedWorker w : gasWorkers) {
                double prevC = preCargo.get(w.tag);
                if (prevC > 0 && w.cargo == 0 && w.assignedGasSlot != null) {
                    String geyser = w.assignedGasSlot.buildingTag;
                    if ("gas-1".equals(geyser)) {
                        gas1Income += prevC;
                        gas1Deliveries++;
                        if (gas1FirstTick < 0) {gas1FirstTick = t;}
                    } else {
                        gas2Income += prevC;
                        gas2Deliveries++;
                        if (gas2FirstTick < 0) {gas2FirstTick = t;}
                    }
                }
                if (wasAtTarget.get(w.tag) && w.task == WorkerTask.RETURNING_GAS && w.isAtTarget()) {
                    depositLockBlocks++;
                }
            }

            if (t % 30 == 0 || t == 304) {
                System.out.printf("%-6d | %-8.0f %-4d | %-8.0f %-4d | %-8.0f | %-+6.0f%n",
                                  t, gas1Income, gas1Deliveries, gas2Income, gas2Deliveries,
                                  gas1Income + gas2Income, gas1Income - gas2Income);
            }
        }

        double total = gas1Income + gas2Income;
        System.out.printf("%nFirst delivery: gas-1=tick %d, gas-2=tick %d (stagger=%d)%n",
                          gas1FirstTick, gas2FirstTick, Math.abs(gas1FirstTick - gas2FirstTick));
        System.out.printf("Deliveries: gas-1=%d, gas-2=%d (total=%d)%n",
                          gas1Deliveries, gas2Deliveries, gas1Deliveries + gas2Deliveries);
        System.out.printf("Deposit lock blocks detected: %d%n", depositLockBlocks);
        System.out.printf("Per-geyser: gas-1=%.0f (%.1f%%), gas-2=%.0f (%.1f%%)%n",
                          gas1Income, gas1Income / total * 100,
                          gas2Income, gas2Income / total * 100);
        System.out.printf("Per-worker (6w dual): %.1f gas/worker%n", total / 6);
        System.out.printf("Compare: SC2 3w=184, SC2 6w=169 (drop=8.3%%)%n");
    }

    @Test
    void depositLockContentionTrace() {
        List<String> tags = IntStream.rangeClosed(1, 12)
                                     .mapToObj(i -> "w-" + i).toList();
        sim.onInitialWorkers(tags, BASE_POS, BASE_TAG);
        sim.onGasBuildingCompleted("gas-1", GAS_POS_1);
        sim.onGasBuildingCompleted("gas-2", GAS_POS_2);

        List<SimulatedWorker> gasWorkers            = findGasWorkers();
        int                   totalLoops            = 305 * SC2Data.LOOPS_PER_TICK;
        int                   depositEvents         = 0, lockBlocks = 0;
        int                   gas1Deposits          = 0, gas2Deposits = 0;
        Map<String, Integer>  blockedLoopsPerWorker = new LinkedHashMap<>();

        System.out.println("=== Deposit Lock Contention Trace (per-loop) ===");
        System.out.printf("Lock duration: %.0f loops%n%n", WorkerSimulation.GAS_DEPOSIT_LOCK_LOOPS);

        for (int loop = 0; loop < totalLoops; loop++) {
            for (SimulatedWorker w : gasWorkers) {
                if (w.task != WorkerTask.RETURNING_GAS || !w.isAtTarget()) {continue;}
                double lockEnd = getDepositLockEnd(w.baseIndex);
                if (loop < lockEnd) {
                    lockBlocks++;
                    blockedLoopsPerWorker.merge(w.tag, 1, Integer::sum);
                    if (lockBlocks <= 20) {
                        System.out.printf("  BLOCKED loop %d: %s (geyser=%s) lockEnd=%.0f wait=%.0f%n",
                                          loop, w.tag, w.assignedGasSlot.buildingTag, lockEnd, lockEnd - loop);
                    }
                }
            }

            Map<String, Double> preCargo = new LinkedHashMap<>();
            for (SimulatedWorker w : gasWorkers) {
                preCargo.put(w.tag, w.cargo);
            }

            sim.tick(loop);

            for (SimulatedWorker w : gasWorkers) {
                if (preCargo.get(w.tag) > 0 && w.cargo == 0) {
                    depositEvents++;
                    if ("gas-1".equals(w.assignedGasSlot.buildingTag)) {gas1Deposits++;} else {gas2Deposits++;}
                }
            }

            // step by full tick (22 loops) since tick() processes all 22
            loop += SC2Data.LOOPS_PER_TICK - 1;
        }

        System.out.printf("%nDeposit events: %d (gas-1=%d, gas-2=%d)%n", depositEvents, gas1Deposits, gas2Deposits);
        System.out.printf("Lock block events: %d (%.1f per geyser-cycle)%n",
                          lockBlocks, lockBlocks > 0 ? (double) lockBlocks / depositEvents : 0);
        System.out.printf("Blocked loops per worker: %s%n", blockedLoopsPerWorker);
    }

    @Test
    void dockingVsRegimeSweep() {
        System.out.println("=== Gas approach docking sweep: 3w vs 6w ===");
        System.out.println("Tests geyser-limited vs walk-limited boundary");
        System.out.printf("%-8s | %-8s %-8s %-8s | %-8s %-8s %-8s | %-9s%n",
                          "docking", "3w gas", "3w err%", "regime", "6w gas", "6w err%", "regime", "combined");

        double sc2_3w = 552, sc2_6w = 1012;

        for (double docking : new double[]{0, 2, 4, 6, 8, 9, 9.3, 10, 11, 11.9, 12, 12.5, 13, 14, 15}) {
            SimulatedWorker.setGasApproachDockingLoops(docking);

            double gas3w = runGasScenario(1, 305);
            double gas6w = runGasScenario(2, 305);

            double err3w    = (gas3w - sc2_3w) / sc2_3w * 100;
            double err6w    = (gas6w - sc2_6w) / sc2_6w * 100;
            double combined = Math.abs(err3w) + Math.abs(err6w);

            double insideTime = docking + SimulatedWorker.GAS_HARVEST_DURATION_LOOPS;
            double nonInside  = 82;
            String regime3w   = nonInside < 2 * insideTime ? "geyser" : "walk";

            System.out.printf("%-8.1f | %-8.0f %-+7.1f%% %-8s | %-8.0f %-+7.1f%% %-8s | %-9.1f%n",
                              docking, gas3w, err3w, regime3w, gas6w, err6w, regime3w, combined);
        }

        SimulatedWorker.resetGasApproachDocking();
    }

    private double runGasScenario(int geyserCount, int ticks) {
        WorkerSimulation s = new WorkerSimulation();
        s.onTownHallCompleted(BASE_TAG, BASE_POS);
        List<String> tags = IntStream.rangeClosed(1, 12).mapToObj(i -> "w-" + i).toList();
        s.onInitialWorkers(tags, BASE_POS, BASE_TAG);
        s.onGasBuildingCompleted("gas-1", GAS_POS_1);
        if (geyserCount >= 2) {
            s.onGasBuildingCompleted("gas-2", GAS_POS_2);
        }
        double totalGas = 0;
        for (int t = 0; t < ticks; t++) {
            TickIncome income = s.tick(t * (double) SC2Data.LOOPS_PER_TICK);
            totalGas += income.vespeneIncome();
        }
        return totalGas;
    }


    private double getDepositLockEnd(int baseIndex) {
        try {
            java.lang.reflect.Field f = WorkerSimulation.class.getDeclaredField("gasDepositLockEndLoop");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<Integer, Double> lockMap = (Map<Integer, Double>) f.get(sim);
            return lockMap.getOrDefault(baseIndex, 0.0);
        } catch (Exception e) {
            return 0;
        }
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

package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.sc2.playbook.WorkerSnapshot;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag("diagnostic")
class ContentionCalibrationDiagnosticTest {

    private static final Point2d BASE_POS = new Point2d(50, 50);
    private static final String BASE_TAG = "base-1";

    @Test
    void measureEmContentionAndRerouting() {
        WorkerSimulation sim = new WorkerSimulation();
        sim.onTownHallCompleted(BASE_TAG, BASE_POS);
        List<String> tags = new ArrayList<>();
        for (int i = 1; i <= 12; i++) { tags.add("w-" + i); }
        sim.onInitialWorkers(tags, BASE_POS, BASE_TAG);

        Map<String, MineralPatch> prevPatch = new HashMap<>();
        int rerouteCount = 0;
        int totalDeliveries = 0;
        List<Integer> cycleLengths = new ArrayList<>();
        Map<String, Integer> lastDeliveryTick = new HashMap<>();

        for (int tick = 0; tick < 304; tick++) {
            TickIncome income = sim.tick(tick * (double) SC2Data.LOOPS_PER_TICK);

            for (SimulatedWorker w : sim.allWorkers()) {
                if (w.assignedGasSlot != null) continue;

                MineralPatch prev = prevPatch.get(w.tag);
                if (w.assignedPatch != null && prev != null && w.assignedPatch != prev) {
                    rerouteCount++;
                }
                prevPatch.put(w.tag, w.assignedPatch);

                if (w.task == WorkerTask.RETURNING_MINERALS || w.task == WorkerTask.DEPOSITING_MINERALS) {
                    Integer lastTick = lastDeliveryTick.get(w.tag);
                    if (lastTick != null) {
                        cycleLengths.add(tick - lastTick);
                    }
                    lastDeliveryTick.put(w.tag, tick);
                    totalDeliveries++;
                }
            }
        }

        double meanCycle = cycleLengths.stream().mapToInt(i -> i).average().orElse(0);
        double variance = cycleLengths.stream().mapToDouble(i -> (i - meanCycle) * (i - meanCycle)).average().orElse(0);
        double sigma = Math.sqrt(variance);

        int[] distribution = new int[20];
        for (int c : cycleLengths) {
            if (c < distribution.length) distribution[c]++;
        }

        System.out.println("=== EM Contention Calibration (12w, 304 ticks) ===");
        System.out.println("Reroute events: " + rerouteCount);
        System.out.println("Total deliveries: " + totalDeliveries);
        System.out.printf("Cycle mean: %.2f ticks, sigma: %.2f%n", meanCycle, sigma);
        System.out.println("Cycle distribution:");
        for (int i = 0; i < distribution.length; i++) {
            if (distribution[i] > 0) {
                System.out.printf("  %d-tick: %d (%.1f%%)%n", i, distribution[i],
                    100.0 * distribution[i] / cycleLengths.size());
            }
        }
    }

    @Test
    void cadenceMismatchWithConditionalApproach() {
        WorkerSimulation sim = new WorkerSimulation();
        sim.onTownHallCompleted(BASE_TAG, BASE_POS);
        List<String> tags = new ArrayList<>();
        for (int i = 1; i <= 12; i++) { tags.add("w-" + i); }
        sim.onInitialWorkers(tags, BASE_POS, BASE_TAG);

        List<Double> deliveries = new ArrayList<>();
        double totalMinerals = 0;
        for (int tick = 0; tick < 304; tick++) {
            TickIncome income = sim.tick(tick * (double) SC2Data.LOOPS_PER_TICK);
            deliveries.add(income.mineralIncome());
            totalMinerals += income.mineralIncome();
        }

        long nonZeroTicks = deliveries.stream().filter(d -> d > 0).count();
        long deliveriesMultOf5 = deliveries.stream()
            .filter(d -> d > 0)
            .filter(d -> d % 5 == 0)
            .count();

        System.out.println("=== Cadence Report (12w, 304 ticks) ===");
        System.out.printf("Total minerals: %.0f%n", totalMinerals);
        System.out.println("Non-zero delivery ticks: " + nonZeroTicks + " / 304");
        System.out.printf("Delivery rate: %.1f%%%n", 100.0 * nonZeroTicks / 304);
        System.out.printf("Deliveries that are multiples of 5: %d / %d%n", deliveriesMultOf5, nonZeroTicks);
    }
}

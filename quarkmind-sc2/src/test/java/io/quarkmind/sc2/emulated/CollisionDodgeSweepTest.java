package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.Resource;
import io.quarkmind.sc2.playbook.CalibrationBaseline;
import io.quarkmind.sc2.playbook.WorkerSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Tag("diagnostic")
class CollisionDodgeSweepTest {

    @AfterEach
    void restoreDefault() {
        SimulatedWorker.resetToDefaults();
    }

    @Test
    void sweepDodgeScaleFactor() {
        CalibrationBaseline cal = loadCalibration("calibration/cal-mineral-baseline-12w.json");

        float[] factors = {0.0f, 0.10f, 0.20f, 0.25f, 0.30f, 0.35f, 0.40f, 0.45f, 0.50f, 0.60f, 0.75f, 1.0f};

        System.out.println("=== Collision Dodge Scale Factor Sweep ===\n");
        System.out.printf("%-8s | %-6s | %-6s | %-7s | %-6s | %-20s%n",
                "Factor", "Mean", "sigma", "7+ %", "Total", "Distribution");
        System.out.println("-".repeat(85));

        for (float factor : factors) {
            WorkerSimulation.LOW_SATURATION_DODGE_SCALE = factor;
            EmulatedGame game = setupGame(cal);

            Map<Integer, List<Integer>> cycleLengths = new HashMap<>();
            int[] prevDeliveryTick = new int[12];
            String[] prevAction = new String[12];
            java.util.Arrays.fill(prevDeliveryTick, -1);

            for (int tick = 0; tick <= 304; tick++) {
                List<WorkerSnapshot> emw = game.workerSnapshots();
                for (int w = 0; w < Math.min(emw.size(), 12); w++) {
                    String act = emw.get(w).action().name();
                    if (prevAction[w] != null && prevAction[w].equals("GATHERING_MINERALS")
                            && act.equals("RETURNING_MINERALS")) {
                        if (prevDeliveryTick[w] >= 0) {
                            int cycle = tick - prevDeliveryTick[w];
                            cycleLengths.computeIfAbsent(w, k -> new ArrayList<>()).add(cycle);
                        }
                        prevDeliveryTick[w] = tick;
                    }
                    prevAction[w] = act;
                }
                game.tick();
            }

            Map<Integer, Integer> hist = new TreeMap<>();
            List<Integer> allCycles = new ArrayList<>();
            cycleLengths.values().stream().flatMap(List::stream).forEach(c -> {
                hist.merge(c, 1, Integer::sum);
                allCycles.add(c);
            });

            int total = allCycles.size();
            double mean = allCycles.stream().mapToInt(Integer::intValue).average().orElse(0);
            double variance = allCycles.stream().mapToDouble(c -> Math.pow(c - mean, 2)).sum() / total;
            double sigma = Math.sqrt(variance);
            long count7Plus = allCycles.stream().filter(c -> c >= 7).count();
            double pct7Plus = 100.0 * count7Plus / total;

            System.out.printf("%-8.2f | %6.2f | %6.2f | %6.1f%% | %6d | %s%n",
                    factor, mean, sigma, pct7Plus, total, hist);
        }

        System.out.println("\nSC2 reference: mean=5.28, sigma=0.56, 7+ tick=0.0%");
    }

    private static EmulatedGame setupGame(CalibrationBaseline cal) {
        EmulatedGame game = new EmulatedGame();
        game.setPlayerRaceModel(RaceModelFactory.forRace(Race.PROTOSS));
        if (cal.basePosition() != null && cal.mineralPatches() != null) {
            float fx = 8f - cal.basePosition()[0];
            float fy = 8f - cal.basePosition()[1];
            game.setMineralPatchResources(cal.mineralPatches().stream()
                    .map(p -> new Resource(
                            "cal-" + cal.mineralPatches().indexOf(p),
                            new Point2d(p.x() + fx, p.y() + fy), p.amount(), p.rich()))
                    .toList());
        }
        game.reset();
        return game;
    }

    private static CalibrationBaseline loadCalibration(String resource) {
        try {
            return CalibrationBaseline.fromJson(
                    Files.readString(Path.of("src/test/resources/" + resource)));
        } catch (Exception e) {
            throw new RuntimeException("Failed to load " + resource, e);
        }
    }
}

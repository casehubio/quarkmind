package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.Resource;
import io.quarkmind.sc2.playbook.CalibrationBaseline;
import io.quarkmind.sc2.playbook.WorkerSnapshot;
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
class ApproachEqualizationSweepTest {

    @Test
    void sweepEqualizationScale() {
        CalibrationBaseline cal = loadCalibration("calibration/cal-mineral-baseline-12w.json");

        double[] scales = {0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0};

        System.out.println("=== Approach Equalization Scale Sweep (dodge=0.25 fixed) ===\n");
        System.out.printf("%-8s | %-6s | %-6s | %-7s | %-6s | %-20s%n",
                "Scale", "Mean", "sigma", "7+ %", "Total", "Distribution");
        System.out.println("-".repeat(85));

        double originalLoopsPerUnit = io.quarkmind.domain.SC2Data.GAME_LOOPS_PER_SECOND
                / io.quarkmind.domain.SC2Data.unitSpeed(io.quarkmind.domain.UnitType.PROBE);

        for (double scale : scales) {
            MineralPatch.APPROACH_EQUALIZATION_SCALE = scale;
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

            System.out.printf("%-8.1f | %6.2f | %6.2f | %6.1f%% | %6d | %s%n",
                    scale, mean, sigma, pct7Plus, total, hist);
        }

        MineralPatch.APPROACH_EQUALIZATION_SCALE = 1.0;

        System.out.println("\nSC2 reference: mean=5.28, sigma=0.56, 7+ tick=0.0%");
        System.out.println("Scale=1.0 = full equalization (current). Scale=0.0 = no equalization (flat approach).");
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

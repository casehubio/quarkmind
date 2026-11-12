package io.quarkmind.sc2.playbook;

import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.Resource;
import io.quarkmind.sc2.emulated.EmulatedGame;
import io.quarkmind.sc2.emulated.RaceModelFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Tag("diagnostic")
class WorkerCadenceComparisonTest {

    @Test
    void compareDeliveryCadence() {
        WorkerCadenceBaseline sc2 = loadWorkerCadence("calibration/cal-worker-cadence-12w.json");
        CalibrationBaseline cal = loadCalibration("calibration/cal-mineral-baseline-12w.json");
        EmulatedGame game = setupGame(cal);

        System.out.println("=== EM vs SC2 Per-Worker Delivery Cadence ===\n");

        Map<Integer, Integer> sc2DeliveriesPerTick = new TreeMap<>();
        Map<Integer, Integer> emDeliveriesPerTick = new TreeMap<>();

        String[] prevSc2Action = new String[12];
        String[] prevEmAction = new String[12];

        for (int tick = 0; tick < sc2.tickCount() && tick <= 304; tick++) {
            List<WorkerSnapshot> sc2Workers = sc2.cadence().get(tick).workers();
            List<WorkerSnapshot> emWorkers = game.workerSnapshots();

            int sc2Deliveries = 0;
            int emDeliveries = 0;

            for (int w = 0; w < Math.min(sc2Workers.size(), 12); w++) {
                String sc2Act = sc2Workers.get(w).action().name();
                if (prevSc2Action[w] != null
                        && prevSc2Action[w].equals("GATHERING_MINERALS")
                        && sc2Act.equals("RETURNING_MINERALS")) {
                    sc2Deliveries++;
                }
                prevSc2Action[w] = sc2Act;
            }

            for (int w = 0; w < Math.min(emWorkers.size(), 12); w++) {
                String emAct = emWorkers.get(w).action().name();
                if (prevEmAction[w] != null
                        && prevEmAction[w].equals("GATHERING_MINERALS")
                        && emAct.equals("RETURNING_MINERALS")) {
                    emDeliveries++;
                }
                prevEmAction[w] = emAct;
            }

            sc2DeliveriesPerTick.put(tick, sc2Deliveries);
            emDeliveriesPerTick.put(tick, emDeliveries);
            game.tick();
        }

        System.out.println("Tick | SC2 del | EM del | Match");
        System.out.println("-----|---------|--------|------");
        int matchCount = 0;
        for (int tick = 0; tick <= 50; tick++) {
            int sc2d = sc2DeliveriesPerTick.getOrDefault(tick, 0);
            int emd = emDeliveriesPerTick.getOrDefault(tick, 0);
            String match = sc2d == emd ? "  ==" : String.format(" %+d", emd - sc2d);
            if (sc2d == emd) matchCount++;
            System.out.printf(" %3d |    %4d |   %4d | %s%n", tick, sc2d, emd, match);
        }

        System.out.println("\n=== EM Worker Cycle Lengths ===");
        Map<Integer, List<Integer>> emCycleLengths = new HashMap<>();
        int[] emPrevDeliveryTick = new int[12];
        java.util.Arrays.fill(emPrevDeliveryTick, -1);

        EmulatedGame game2 = setupGame(cal);
        String[] prev2 = new String[12];
        for (int tick = 0; tick <= 304; tick++) {
            List<WorkerSnapshot> emw = game2.workerSnapshots();
            for (int w = 0; w < Math.min(emw.size(), 12); w++) {
                String act = emw.get(w).action().name();
                if (prev2[w] != null && prev2[w].equals("GATHERING_MINERALS")
                        && act.equals("RETURNING_MINERALS")) {
                    if (emPrevDeliveryTick[w] >= 0) {
                        int cycle = tick - emPrevDeliveryTick[w];
                        emCycleLengths.computeIfAbsent(w, k -> new java.util.ArrayList<>()).add(cycle);
                    }
                    emPrevDeliveryTick[w] = tick;
                }
                prev2[w] = act;
            }
            game2.tick();
        }

        for (int w = 0; w < 12; w++) {
            List<Integer> cycles = emCycleLengths.get(w);
            if (cycles == null || cycles.isEmpty()) {
                System.out.printf("W%02d: no deliveries%n", w);
                continue;
            }
            double avg = cycles.stream().mapToInt(Integer::intValue).average().orElse(0);
            int min = cycles.stream().mapToInt(Integer::intValue).min().orElse(0);
            int max = cycles.stream().mapToInt(Integer::intValue).max().orElse(0);
            System.out.printf("W%02d: %d deliveries, cycle avg=%.1f min=%d max=%d%n",
                    w, cycles.size(), avg, min, max);
        }

        System.out.println("\n=== SC2 Worker Cycle Lengths ===");
        Map<Integer, List<Integer>> sc2CycleLengths = new HashMap<>();
        int[] sc2PrevDeliveryTick = new int[12];
        java.util.Arrays.fill(sc2PrevDeliveryTick, -1);
        String[] prevSc2_2 = new String[12];

        for (int tick = 0; tick < sc2.tickCount(); tick++) {
            List<WorkerSnapshot> sc2w = sc2.cadence().get(tick).workers();
            for (int w = 0; w < Math.min(sc2w.size(), 12); w++) {
                String act = sc2w.get(w).action().name();
                if (prevSc2_2[w] != null && prevSc2_2[w].equals("GATHERING_MINERALS")
                        && act.equals("RETURNING_MINERALS")) {
                    if (sc2PrevDeliveryTick[w] >= 0) {
                        int cycle = tick - sc2PrevDeliveryTick[w];
                        sc2CycleLengths.computeIfAbsent(w, k -> new java.util.ArrayList<>()).add(cycle);
                    }
                    sc2PrevDeliveryTick[w] = tick;
                }
                prevSc2_2[w] = act;
            }
        }

        for (int w = 0; w < 12; w++) {
            List<Integer> cycles = sc2CycleLengths.get(w);
            if (cycles == null || cycles.isEmpty()) {
                System.out.printf("W%02d: no deliveries%n", w);
                continue;
            }
            double avg = cycles.stream().mapToInt(Integer::intValue).average().orElse(0);
            int min = cycles.stream().mapToInt(Integer::intValue).min().orElse(0);
            int max = cycles.stream().mapToInt(Integer::intValue).max().orElse(0);
            System.out.printf("W%02d: %d deliveries, cycle avg=%.1f min=%d max=%d%n",
                    w, cycles.size(), avg, min, max);
        }
    }

    @Test
    void cycleLengthHistogram() {
        WorkerCadenceBaseline sc2 = loadWorkerCadence("calibration/cal-worker-cadence-12w.json");
        CalibrationBaseline   cal = loadCalibration("calibration/cal-mineral-baseline-12w.json");

        Map<Integer, Integer> sc2Histogram = new TreeMap<>();
        Map<Integer, Integer> emHistogram  = new TreeMap<>();

        extractCycleLengths(sc2).values().stream()
                                .flatMap(List::stream)
                                .forEach(c -> sc2Histogram.merge(c, 1, Integer::sum));

        EmulatedGame game = setupGame(cal);
        extractEmCycleLengths(game, 304).values().stream()
                                        .flatMap(List::stream)
                                        .forEach(c -> emHistogram.merge(c, 1, Integer::sum));

        int maxCycle = Math.max(
                sc2Histogram.keySet().stream().mapToInt(Integer::intValue).max().orElse(0),
                emHistogram.keySet().stream().mapToInt(Integer::intValue).max().orElse(0));
        int sc2Total = sc2Histogram.values().stream().mapToInt(Integer::intValue).sum();
        int emTotal  = emHistogram.values().stream().mapToInt(Integer::intValue).sum();

        System.out.println("=== Cycle Length Histogram (single SC2 run) ===\n");
        System.out.println("Cycle | SC2 cnt (%) | EM cnt (%)  | Bar (SC2=# EM=*)");
        System.out.println("------|-------------|-------------|------------------");
        for (int c = 2; c <= maxCycle; c++) {
            int    sc2c   = sc2Histogram.getOrDefault(c, 0);
            int    emc    = emHistogram.getOrDefault(c, 0);
            double sc2pct = sc2Total > 0 ? 100.0 * sc2c / sc2Total : 0;
            double empct  = emTotal > 0 ? 100.0 * emc / emTotal : 0;
            String sc2Bar = "#".repeat(Math.min((int) (sc2pct / 2), 40));
            String emBar  = "*".repeat(Math.min((int) (empct / 2), 40));
            System.out.printf("  %3d | %4d (%4.1f%%) | %4d (%4.1f%%) | %s %s%n",
                              c, sc2c, sc2pct, emc, empct, sc2Bar, emBar);
        }
        System.out.printf("%nSC2 total cycles: %d, EM total cycles: %d%n", sc2Total, emTotal);

        double sc2Mean = sc2Histogram.entrySet().stream()
                                     .mapToDouble(e -> e.getKey() * e.getValue()).sum() / sc2Total;
        double emMean = emHistogram.entrySet().stream()
                                   .mapToDouble(e -> e.getKey() * e.getValue()).sum() / emTotal;
        double sc2Var = sc2Histogram.entrySet().stream()
                                    .mapToDouble(e -> e.getValue() * Math.pow(e.getKey() - sc2Mean, 2)).sum() / sc2Total;
        double emVar = emHistogram.entrySet().stream()
                                  .mapToDouble(e -> e.getValue() * Math.pow(e.getKey() - emMean, 2)).sum() / emTotal;
        System.out.printf("SC2: mean=%.2f, σ=%.2f  |  EM: mean=%.2f, σ=%.2f%n",
                          sc2Mean, Math.sqrt(sc2Var), emMean, Math.sqrt(emVar));
    }

    @Test
    void multiRunCycleLengthHistogram() {
        Map<Integer, Integer> sc2Histogram = new TreeMap<>();
        int                   runCount     = 0;
        for (int run = 1; run <= 10; run++) {
            try {
                WorkerCadenceBaseline sc2 = loadWorkerCadence(
                        "calibration/multi-run/cal-worker-cadence-12w-run-" + run + ".json");
                extractCycleLengths(sc2).values().stream()
                                        .flatMap(List::stream)
                                        .forEach(c -> sc2Histogram.merge(c, 1, Integer::sum));
                runCount++;
            } catch (RuntimeException ignored) {}
        }
        if (runCount == 0) {
            System.out.println("No multi-run data found. Run PlaybookSC2RecorderTest#recordMultiRunCadence12w first.");
            return;
        }

        CalibrationBaseline   cal         = loadCalibration("calibration/cal-mineral-baseline-12w.json");
        Map<Integer, Integer> emHistogram = new TreeMap<>();
        EmulatedGame          game        = setupGame(cal);
        extractEmCycleLengths(game, 304).values().stream()
                                        .flatMap(List::stream)
                                        .forEach(c -> emHistogram.merge(c, 1, Integer::sum));

        int maxCycle = Math.max(
                sc2Histogram.keySet().stream().mapToInt(Integer::intValue).max().orElse(0),
                emHistogram.keySet().stream().mapToInt(Integer::intValue).max().orElse(0));
        int sc2Total = sc2Histogram.values().stream().mapToInt(Integer::intValue).sum();
        int emTotal  = emHistogram.values().stream().mapToInt(Integer::intValue).sum();

        System.out.printf("=== Cycle Length Histogram (%d SC2 runs aggregated) ===%n%n", runCount);
        System.out.println("Cycle | SC2 cnt (%) | EM cnt (%)  | Bar (SC2=# EM=*)");
        System.out.println("------|-------------|-------------|------------------");
        for (int c = 2; c <= maxCycle; c++) {
            int    sc2c   = sc2Histogram.getOrDefault(c, 0);
            int    emc    = emHistogram.getOrDefault(c, 0);
            double sc2pct = sc2Total > 0 ? 100.0 * sc2c / sc2Total : 0;
            double empct  = emTotal > 0 ? 100.0 * emc / emTotal : 0;
            String sc2Bar = "#".repeat(Math.min((int) (sc2pct / 2), 40));
            String emBar  = "*".repeat(Math.min((int) (empct / 2), 40));
            System.out.printf("  %3d | %4d (%4.1f%%) | %4d (%4.1f%%) | %s %s%n",
                              c, sc2c, sc2pct, emc, empct, sc2Bar, emBar);
        }
        System.out.printf("%nSC2 total cycles: %d (%d runs), EM total cycles: %d%n",
                          sc2Total, runCount, emTotal);

        double sc2Mean = sc2Histogram.entrySet().stream()
                                     .mapToDouble(e -> e.getKey() * e.getValue()).sum() / sc2Total;
        double emMean = emHistogram.entrySet().stream()
                                   .mapToDouble(e -> e.getKey() * e.getValue()).sum() / emTotal;
        double sc2Var = sc2Histogram.entrySet().stream()
                                    .mapToDouble(e -> e.getValue() * Math.pow(e.getKey() - sc2Mean, 2)).sum() / sc2Total;
        double emVar = emHistogram.entrySet().stream()
                                  .mapToDouble(e -> e.getValue() * Math.pow(e.getKey() - emMean, 2)).sum() / emTotal;
        System.out.printf("SC2: mean=%.2f, σ=%.2f  |  EM: mean=%.2f, σ=%.2f%n",
                          sc2Mean, Math.sqrt(sc2Var), emMean, Math.sqrt(emVar));
    }

    private static Map<Integer, List<Integer>> extractCycleLengths(WorkerCadenceBaseline baseline) {
        Map<Integer, List<Integer>> cycleLengths     = new HashMap<>();
        int                         workerCount      = baseline.cadence().get(0).workers().size();
        int[]                       prevDeliveryTick = new int[workerCount];
        String[]                    prevAction       = new String[workerCount];
        java.util.Arrays.fill(prevDeliveryTick, -1);

        for (int tick = 0; tick < baseline.tickCount(); tick++) {
            List<WorkerSnapshot> workers = baseline.cadence().get(tick).workers();
            for (int w = 0; w < Math.min(workers.size(), workerCount); w++) {
                String act = workers.get(w).action().name();
                if (prevAction[w] != null && prevAction[w].equals("GATHERING_MINERALS")
                    && act.equals("RETURNING_MINERALS")) {
                    if (prevDeliveryTick[w] >= 0) {
                        int cycle = tick - prevDeliveryTick[w];
                        cycleLengths.computeIfAbsent(w, k -> new java.util.ArrayList<>()).add(cycle);
                    }
                    prevDeliveryTick[w] = tick;
                }
                prevAction[w] = act;
            }
        }
        return cycleLengths;
    }

    private static Map<Integer, List<Integer>> extractEmCycleLengths(EmulatedGame game, int ticks) {
        Map<Integer, List<Integer>> cycleLengths     = new HashMap<>();
        int[]                       prevDeliveryTick = new int[12];
        String[]                    prevAction       = new String[12];
        java.util.Arrays.fill(prevDeliveryTick, -1);

        for (int tick = 0; tick <= ticks; tick++) {
            List<WorkerSnapshot> emw = game.workerSnapshots();
            for (int w = 0; w < Math.min(emw.size(), 12); w++) {
                String act = emw.get(w).action().name();
                if (prevAction[w] != null && prevAction[w].equals("GATHERING_MINERALS")
                    && act.equals("RETURNING_MINERALS")) {
                    if (prevDeliveryTick[w] >= 0) {
                        int cycle = tick - prevDeliveryTick[w];
                        cycleLengths.computeIfAbsent(w, k -> new java.util.ArrayList<>()).add(cycle);
                    }
                    prevDeliveryTick[w] = tick;
                }
                prevAction[w] = act;
            }
            game.tick();
        }
        return cycleLengths;
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

    private static WorkerCadenceBaseline loadWorkerCadence(String resource) {
        try {
            String json = Files.readString(Path.of("src/test/resources/" + resource));
            return WorkerCadenceBaseline.fromJson(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load " + resource, e);
        }
    }

    private static CalibrationBaseline loadCalibration(String resource) {
        try {
            String json = Files.readString(Path.of("src/test/resources/" + resource));
            return CalibrationBaseline.fromJson(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load " + resource, e);
        }
    }
}

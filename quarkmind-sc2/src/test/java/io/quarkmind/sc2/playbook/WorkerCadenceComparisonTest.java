package io.quarkmind.sc2.playbook;

import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.Resource;
import io.quarkmind.domain.WorkerAction;
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

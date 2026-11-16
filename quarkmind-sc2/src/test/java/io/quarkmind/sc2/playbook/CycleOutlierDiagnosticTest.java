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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Tag("diagnostic")
class CycleOutlierDiagnosticTest {

    @Test
    void trace7PlusTickCycles() {
        CalibrationBaseline cal = loadCalibration("calibration/cal-mineral-baseline-12w.json");
        EmulatedGame game = setupGame(cal);

        Map<Integer, List<CycleRecord>> cyclesPerWorker = new HashMap<>();
        Map<Integer, String> workerPatchAssignment = new HashMap<>();
        int[] prevDeliveryTick = new int[12];
        String[] prevAction = new String[12];
        Point2d[] prevPosition = new Point2d[12];
        java.util.Arrays.fill(prevDeliveryTick, -1);

        Map<Integer, List<TickTrace>> workerTraces = new HashMap<>();

        for (int tick = 0; tick <= 304; tick++) {
            List<WorkerSnapshot> emw = game.workerSnapshots();
            for (int w = 0; w < Math.min(emw.size(), 12); w++) {
                WorkerSnapshot ws = emw.get(w);
                String act = ws.action().name();
                Point2d pos = ws.position();

                workerTraces.computeIfAbsent(w, k -> new ArrayList<>())
                        .add(new TickTrace(tick, act, pos, ws.carrying()));

                if (prevAction[w] != null && prevAction[w].equals("GATHERING_MINERALS")
                        && act.equals("RETURNING_MINERALS")) {
                    if (prevDeliveryTick[w] >= 0) {
                        int cycleLen = tick - prevDeliveryTick[w];
                        cyclesPerWorker.computeIfAbsent(w, k -> new ArrayList<>())
                                .add(new CycleRecord(w, prevDeliveryTick[w], tick, cycleLen));
                    }
                    prevDeliveryTick[w] = tick;
                }
                prevAction[w] = act;
                prevPosition[w] = pos;
            }
            game.tick();
        }

        System.out.println("=== 7+ Tick Cycle Outlier Analysis ===\n");

        int total7Plus = 0;
        Map<String, Integer> causeCounts = new TreeMap<>();

        for (var entry : cyclesPerWorker.entrySet()) {
            int workerIdx = entry.getKey();
            for (CycleRecord cycle : entry.getValue()) {
                if (cycle.length >= 7) {
                    total7Plus++;
                    List<TickTrace> traces = workerTraces.get(workerIdx);
                    String cause = analyzeCycleCause(traces, cycle);
                    causeCounts.merge(cause, 1, Integer::sum);

                    System.out.printf("W%02d: ticks %d→%d (len=%d) cause=%s%n",
                            workerIdx, cycle.startTick, cycle.endTick, cycle.length, cause);
                    printCyclePhases(traces, cycle);
                    System.out.println();
                }
            }
        }

        System.out.println("=== Summary ===");
        int totalCycles = cyclesPerWorker.values().stream().mapToInt(List::size).sum();
        System.out.printf("Total cycles: %d, 7+ tick: %d (%.1f%%)%n",
                totalCycles, total7Plus, 100.0 * total7Plus / totalCycles);
        System.out.println("\nCause breakdown:");
        for (var e : causeCounts.entrySet()) {
            System.out.printf("  %-30s: %d (%.1f%%)%n", e.getKey(),
                    e.getValue(), 100.0 * e.getValue() / total7Plus);
        }

        System.out.println("\n=== Per-Worker Cycle Length Distribution ===");
        for (var entry : cyclesPerWorker.entrySet()) {
            int workerIdx = entry.getKey();
            List<CycleRecord> cycles = entry.getValue();
            Map<Integer, Integer> hist = new TreeMap<>();
            for (CycleRecord c : cycles) hist.merge(c.length, 1, Integer::sum);
            System.out.printf("W%02d: %s%n", workerIdx, hist);
        }
    }

    @Test
    void trace7PlusTickCyclesSc2() {
        WorkerCadenceBaseline sc2 = loadWorkerCadence("calibration/cal-worker-cadence-12w.json");

        Map<String, List<CycleRecord>> cyclesPerWorker = new HashMap<>();
        Map<String, String> prevAction = new HashMap<>();
        Map<String, Integer> prevDeliveryTick = new HashMap<>();
        Map<String, List<TickTrace>> workerTraces = new HashMap<>();

        for (int tick = 0; tick < sc2.tickCount(); tick++) {
            for (WorkerSnapshot ws : sc2.cadence().get(tick).workers()) {
                String tag = ws.tag();
                workerTraces.computeIfAbsent(tag, k -> new ArrayList<>())
                        .add(new TickTrace(tick, ws.action().name(), ws.position(), ws.carrying()));

                String prev = prevAction.get(tag);
                if (prev != null && prev.equals("GATHERING_MINERALS")
                        && ws.action().name().equals("RETURNING_MINERALS")) {
                    Integer prevTick = prevDeliveryTick.get(tag);
                    if (prevTick != null) {
                        int cycleLen = tick - prevTick;
                        cyclesPerWorker.computeIfAbsent(tag, k -> new ArrayList<>())
                                .add(new CycleRecord(-1, prevTick, tick, cycleLen));
                    }
                    prevDeliveryTick.put(tag, tick);
                }
                prevAction.put(tag, ws.action().name());
            }
        }

        System.out.println("=== SC2 Cycle Distribution (for comparison) ===\n");
        int totalCycles = cyclesPerWorker.values().stream().mapToInt(List::size).sum();
        int total7Plus = (int) cyclesPerWorker.values().stream()
                .flatMap(List::stream).filter(c -> c.length >= 7).count();
        System.out.printf("Total cycles: %d, 7+ tick: %d (%.1f%%)%n",
                totalCycles, total7Plus, 100.0 * total7Plus / Math.max(1, totalCycles));

        Map<Integer, Integer> hist = new TreeMap<>();
        cyclesPerWorker.values().stream().flatMap(List::stream)
                .forEach(c -> hist.merge(c.length, 1, Integer::sum));
        System.out.println("Distribution: " + hist);
    }

    private String analyzeCycleCause(List<TickTrace> traces, CycleRecord cycle) {
        int walkingTicks = 0;
        int waitingTicks = 0;
        int gatheringStillTicks = 0;

        for (TickTrace t : traces) {
            if (t.tick < cycle.startTick || t.tick > cycle.endTick) continue;

            if (t.action.equals("GATHERING_MINERALS") && !t.carrying) {
                TickTrace next = findTrace(traces, t.tick + 1);
                if (next != null && next.action.equals("GATHERING_MINERALS") && !next.carrying) {
                    double moved = t.position.distanceTo(next.position);
                    if (moved < 0.1) {
                        gatheringStillTicks++;
                    } else {
                        walkingTicks++;
                    }
                } else {
                    gatheringStillTicks++;
                }
            }
        }

        if (gatheringStillTicks >= 3) return "WAITING_AT_PATCH";
        if (walkingTicks >= 4) return "LONG_WALK";
        return "MIXED";
    }

    private void printCyclePhases(List<TickTrace> traces, CycleRecord cycle) {
        StringBuilder sb = new StringBuilder("  phases: ");
        for (TickTrace t : traces) {
            if (t.tick < cycle.startTick || t.tick > cycle.endTick) continue;
            char c = switch (t.action) {
                case "GATHERING_MINERALS" -> t.carrying ? 'G' : 'g';
                case "RETURNING_MINERALS" -> 'R';
                case "DEPOSITING_MINERALS" -> 'D';
                default -> '?';
            };

            TickTrace next = findTrace(traces, t.tick + 1);
            if (next != null && t.action.equals("GATHERING_MINERALS") && !t.carrying) {
                double moved = t.position.distanceTo(next.position);
                if (moved < 0.1) c = 'W';
            }
            sb.append(c);
        }
        System.out.println(sb);
    }

    private TickTrace findTrace(List<TickTrace> traces, int tick) {
        for (TickTrace t : traces) {
            if (t.tick == tick) return t;
        }
        return null;
    }

    record CycleRecord(int workerIdx, int startTick, int endTick, int length) {}
    record TickTrace(int tick, String action, Point2d position, boolean carrying) {}

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
            String json = Files.readString(Path.of("src/test/resources/" + resource));
            return CalibrationBaseline.fromJson(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load " + resource, e);
        }
    }

    private static WorkerCadenceBaseline loadWorkerCadence(String resource) {
        try {
            String json = Files.readString(Path.of("src/test/resources/" + resource));
            return WorkerCadenceBaseline.fromJson(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load " + resource, e);
        }
    }
}

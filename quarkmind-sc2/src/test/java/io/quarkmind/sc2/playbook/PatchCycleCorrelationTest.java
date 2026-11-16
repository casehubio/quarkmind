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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Tag("diagnostic")
class PatchCycleCorrelationTest {

    @Test
    void perPatchCycleCorrelation() {
        CalibrationBaseline cal = loadCalibration("calibration/cal-mineral-baseline-12w.json");
        EmulatedGame game = setupGame(cal);

        float[] basePos = cal.basePosition();
        double bx = basePos[0], by = basePos[1];
        float fx = 8f - basePos[0];
        float fy = 8f - basePos[1];

        List<CalibrationBaseline.PatchPosition> rawPatches = cal.mineralPatches().stream()
                .filter(p -> Math.sqrt((p.x() - bx) * (p.x() - bx) + (p.y() - by) * (p.y() - by)) <= 12.0)
                .toList();

        List<PatchInfo> patches = new ArrayList<>();
        for (int i = 0; i < rawPatches.size(); i++) {
            CalibrationBaseline.PatchPosition pp = rawPatches.get(i);
            double c2c = Math.sqrt((pp.x() - bx) * (pp.x() - bx) + (pp.y() - by) * (pp.y() - by));
            double angle = Math.toDegrees(Math.atan2(pp.y() - by, pp.x() - bx));
            Point2d emPos = new Point2d(pp.x() + fx, pp.y() + fy);
            patches.add(new PatchInfo(i, emPos, c2c, angle, pp.amount()));
        }

        Map<Integer, List<CycleRecord>> cyclesPerWorker = new HashMap<>();
        Map<Integer, List<Point2d>> miningPositionsPerWorker = new HashMap<>();
        int[] prevDeliveryTick = new int[12];
        String[] prevAction = new String[12];
        java.util.Arrays.fill(prevDeliveryTick, -1);

        for (int tick = 0; tick <= 304; tick++) {
            List<WorkerSnapshot> emw = game.workerSnapshots();
            for (int w = 0; w < Math.min(emw.size(), 12); w++) {
                WorkerSnapshot ws = emw.get(w);
                String act = ws.action().name();

                if (act.equals("GATHERING_MINERALS") && !ws.carrying() && tick > 10) {
                    miningPositionsPerWorker.computeIfAbsent(w, k -> new ArrayList<>()).add(ws.position());
                }

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
            }
            game.tick();
        }

        System.out.println("=== Per-Patch Cycle Correlation ===\n");
        System.out.printf("%-4s | %-6s | %-7s | %-7s | %-6s | %-4s | %-5s | %-6s | %s%n",
                "Wkr", "Patch", "C2C", "E2E", "Angle", "Cyc", "Mean", "7+", "Distribution");
        System.out.println("-".repeat(95));

        for (int w = 0; w < 12; w++) {
            List<Point2d> miningPos = miningPositionsPerWorker.getOrDefault(w, List.of());
            if (miningPos.isEmpty()) continue;

            double avgX = miningPos.stream().mapToDouble(Point2d::x).average().orElse(0);
            double avgY = miningPos.stream().mapToDouble(Point2d::y).average().orElse(0);

            PatchInfo bestPatch = null;
            double bestDist = Double.MAX_VALUE;
            for (PatchInfo pi : patches) {
                double d = Math.sqrt((avgX - pi.emPos.x()) * (avgX - pi.emPos.x())
                        + (avgY - pi.emPos.y()) * (avgY - pi.emPos.y()));
                if (d < bestDist) {
                    bestDist = d;
                    bestPatch = pi;
                }
            }

            List<CycleRecord> cycles = cyclesPerWorker.getOrDefault(w, List.of());
            Map<Integer, Integer> hist = new TreeMap<>();
            for (CycleRecord c : cycles) hist.merge(c.length, 1, Integer::sum);
            double mean = cycles.stream().mapToInt(CycleRecord::length).average().orElse(0);
            long count7Plus = cycles.stream().filter(c -> c.length >= 7).count();
            double e2e = bestPatch != null ? bestPatch.c2c - 2.75 - 1.0 : 0;

            System.out.printf("W%02d  | P%d     | %5.2f   | %5.2f   | %6.1f | %4d | %5.2f | %4d   | %s%n",
                    w, bestPatch != null ? bestPatch.idx : -1,
                    bestPatch != null ? bestPatch.c2c : 0,
                    e2e,
                    bestPatch != null ? bestPatch.angle : 0,
                    cycles.size(), mean, count7Plus, hist);
        }

        System.out.println("\n=== Patch Position Summary ===\n");
        System.out.printf("%-6s | %-7s | %-7s | %-7s | %-8s%n",
                "Patch", "C2C", "E2E", "Angle", "Minerals");
        System.out.println("-".repeat(50));
        for (PatchInfo pi : patches) {
            double e2e = pi.c2c - 2.75 - 1.0;
            System.out.printf("P%-5d | %5.2f   | %5.2f   | %6.1f° | %6.0f%n",
                    pi.idx, pi.c2c, e2e, pi.angle, pi.minerals);
        }

        System.out.println("\n=== Intervening Patch Analysis ===\n");
        Point2d base = new Point2d(8f, 8f);
        for (PatchInfo target : patches) {
            List<String> obstructors = new ArrayList<>();
            for (PatchInfo obstacle : patches) {
                if (obstacle == target) continue;
                double distBaseToTarget = base.distanceTo(target.emPos);
                double distBaseToObs = base.distanceTo(obstacle.emPos);
                double distObsToTarget = obstacle.emPos.distanceTo(target.emPos);
                if (distBaseToObs < distBaseToTarget && distObsToTarget < distBaseToTarget) {
                    double perpDist = perpendicularDistance(base, target.emPos, obstacle.emPos);
                    if (perpDist < 1.5) {
                        obstructors.add(String.format("P%d(%.2f)", obstacle.idx, perpDist));
                    }
                }
            }
            if (!obstructors.isEmpty()) {
                System.out.printf("P%d obstructed by: %s%n", target.idx, String.join(", ", obstructors));
            } else {
                System.out.printf("P%d: clear path%n", target.idx);
            }
        }
    }

    private double perpendicularDistance(Point2d lineStart, Point2d lineEnd, Point2d point) {
        double dx = lineEnd.x() - lineStart.x();
        double dy = lineEnd.y() - lineStart.y();
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 0.001) return point.distanceTo(lineStart);
        return Math.abs(dy * point.x() - dx * point.y() + lineEnd.x() * lineStart.y() - lineEnd.y() * lineStart.x()) / len;
    }

    record PatchInfo(int idx, Point2d emPos, double c2c, double angle, double minerals) {}
    record CycleRecord(int workerIdx, int startTick, int endTick, int length) {}

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
}

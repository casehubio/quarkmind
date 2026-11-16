package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.Resource;
import io.quarkmind.sc2.playbook.CalibrationBaseline;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Tag("diagnostic")
class PatchAssignmentDiagnosticTest {

    @Test
    void verifyPatchDistribution() {
        CalibrationBaseline cal = loadCalibration("calibration/cal-mineral-baseline-12w.json");
        EmulatedGame game = new EmulatedGame();
        game.setPlayerRaceModel(RaceModelFactory.forRace(Race.PROTOSS));

        float[] bp = cal.basePosition();
        float fx = 8f - bp[0];
        float fy = 8f - bp[1];
        List<Resource> resources = cal.mineralPatches().stream()
                .filter(p -> Math.sqrt((p.x() - bp[0]) * (p.x() - bp[0]) + (p.y() - bp[1]) * (p.y() - bp[1])) <= 12.0)
                .map(p -> new Resource("cal-" + cal.mineralPatches().indexOf(p),
                        new Point2d(p.x() + fx, p.y() + fy), p.amount(), p.rich()))
                .toList();
        game.setMineralPatchResources(resources);
        game.reset();

        WorkerSimulation ws = game.workerSimulation();

        System.out.println("=== Patch Positions (EM coordinates) ===\n");
        List<WorkerSimulation.BaseEntry> bases = ws.bases();
        if (!bases.isEmpty()) {
            WorkerSimulation.BaseEntry base = bases.get(0);
            System.out.printf("Base: (%.2f, %.2f)%n", base.position().x(), base.position().y());
            for (int i = 0; i < base.patches().size(); i++) {
                MineralPatch p = base.patches().get(i);
                double c2c = p.position.distanceTo(base.position());
                double e2e = c2c - WorkerSimulation.TOWN_HALL_RADIUS - WorkerSimulation.MINERAL_PATCH_RADIUS;
                System.out.printf("  P%d: (%.2f, %.2f) c2c=%.2f e2e=%.2f minerals=%.0f approach=%.1f%n",
                        i, p.position.x(), p.position.y(), c2c, e2e, p.remainingMinerals(), p.approachDockingLoops);
            }
        }

        System.out.println("\n=== Initial Assignment (tick 0) ===\n");
        printAssignments(ws, bases);

        for (int tick = 0; tick <= 304; tick++) {
            game.tick();
            if (tick == 50 || tick == 150 || tick == 304) {
                System.out.printf("%n=== Assignment at tick %d ===%n%n", tick);
                printAssignments(ws, bases);
            }
        }
    }

    private void printAssignments(WorkerSimulation ws, List<WorkerSimulation.BaseEntry> bases) {
        if (bases.isEmpty()) return;
        WorkerSimulation.BaseEntry base = bases.get(0);
        Map<Integer, Integer> patchCounts = new TreeMap<>();
        Map<Integer, String> patchWorkers = new TreeMap<>();
        for (int i = 0; i < base.patches().size(); i++) {
            patchCounts.put(i, 0);
            patchWorkers.put(i, "");
        }
        int unassigned = 0;

        for (SimulatedWorker w : ws.allWorkers()) {
            if (w.assignedPatch == null) {
                unassigned++;
                continue;
            }
            int patchIdx = base.patches().indexOf(w.assignedPatch);
            if (patchIdx >= 0) {
                patchCounts.merge(patchIdx, 1, Integer::sum);
                String existing = patchWorkers.getOrDefault(patchIdx, "");
                patchWorkers.put(patchIdx, existing + (existing.isEmpty() ? "" : ",") + w.tag);
            }
        }

        for (var e : patchCounts.entrySet()) {
            MineralPatch p = base.patches().get(e.getKey());
            double c2c = p.position.distanceTo(base.position());
            System.out.printf("  P%d (c2c=%.2f): %d workers [%s]%n",
                    e.getKey(), c2c, e.getValue(), patchWorkers.get(e.getKey()));
        }
        if (unassigned > 0) {
            System.out.printf("  Unassigned: %d (gas or building workers)%n", unassigned);
        }
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

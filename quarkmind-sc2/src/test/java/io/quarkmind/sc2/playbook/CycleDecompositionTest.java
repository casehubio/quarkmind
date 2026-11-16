package io.quarkmind.sc2.playbook;

import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.WorkerAction;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;

@Tag("diagnostic")
class CycleDecompositionTest {

    @Test
    void decompose8wCycles() throws IOException {
        WorkerCadenceBaseline cadence = loadCadence("calibration/cal-worker-cadence-8w.json");
        CalibrationBaseline   cal     = loadCalibration("calibration/cal-mineral-baseline-8w.json");

        assertFalse(cadence.cadence().isEmpty());

        float[] basePos    = cal.basePosition();
        double  bx         = basePos[0], by = basePos[1];

        List<CalibrationBaseline.PatchPosition> nearbyPatches = cal.mineralPatches().stream()
                .filter(p -> Math.sqrt((p.x() - bx) * (p.x() - bx) + (p.y() - by) * (p.y() - by)) <= 12.0)
                .toList();

        var tick20Workers = cadence.cadence().stream()
                .filter(s -> s.tick() == 20).findFirst()
                .map(s -> s.workers().stream().map(WorkerSnapshot::tag).toList())
                .orElse(List.of());

        System.out.printf("%-12s | %7s | %7s | %7s | %7s | %7s | %11s | %7s%n",
                "Worker", "PatchC2C", "MineRad", "DepDist", "WalkTo", "WalkBack", "StraightE2E", "Deviation");
        System.out.println("-".repeat(100));

        double totalMineRadius = 0, totalDepDist = 0, totalDeviation = 0;
        int workerCount = 0;

        for (String tag : tick20Workers) {
            List<Point2d> miningPositions  = new ArrayList<>();
            List<Point2d> depositPositions = new ArrayList<>();
            List<Double>  walkToDists      = new ArrayList<>();
            List<Double>  walkBackDists    = new ArrayList<>();

            Point2d prevPos      = null;
            boolean prevCarrying = false;
            double  walkAccum    = 0;
            String  walkPhase    = null;

            for (int i = 10; i < cadence.cadence().size(); i++) {
                WorkerCadenceSnapshot snap = cadence.cadence().get(i);
                WorkerSnapshot ws = snap.workers().stream()
                        .filter(w -> w.tag().equals(tag)).findFirst().orElse(null);
                if (ws == null) continue;

                Point2d pos      = ws.position();
                boolean carrying = ws.carrying();

                if (prevPos != null) {
                    double step = pos.distanceTo(prevPos);

                    if (prevCarrying && !carrying) {
                        depositPositions.add(pos);
                        if ("back".equals(walkPhase) && walkAccum > 0.5) walkBackDists.add(walkAccum);
                        walkAccum = 0;
                        walkPhase = null;
                    } else if (!prevCarrying && carrying) {
                        if ("to".equals(walkPhase) && walkAccum > 0.5) walkToDists.add(walkAccum);
                        walkAccum = 0;
                        walkPhase = null;
                    }

                    if (ws.action() == WorkerAction.GATHERING_MINERALS && !carrying && step < 0.1) {
                        miningPositions.add(pos);
                    }

                    if (step > 0.1) {
                        if (ws.action() == WorkerAction.GATHERING_MINERALS && !carrying) {
                            if (!"to".equals(walkPhase)) { walkAccum = 0; walkPhase = "to"; }
                            walkAccum += step;
                        } else if (ws.action() == WorkerAction.RETURNING_MINERALS && carrying) {
                            if (!"back".equals(walkPhase)) { walkAccum = 0; walkPhase = "back"; }
                            walkAccum += step;
                        }
                    }
                }
                prevPos      = pos;
                prevCarrying = carrying;
            }

            if (miningPositions.isEmpty() || depositPositions.isEmpty()) continue;

            double avgMx = miningPositions.stream().mapToDouble(Point2d::x).average().orElse(0);
            double avgMy = miningPositions.stream().mapToDouble(Point2d::y).average().orElse(0);
            double avgDx = depositPositions.stream().mapToDouble(Point2d::x).average().orElse(0);
            double avgDy = depositPositions.stream().mapToDouble(Point2d::y).average().orElse(0);

            CalibrationBaseline.PatchPosition bestPatch = nearbyPatches.stream()
                    .min((a, b) -> Double.compare(
                            Math.sqrt((avgMx - a.x()) * (avgMx - a.x()) + (avgMy - a.y()) * (avgMy - a.y())),
                            Math.sqrt((avgMx - b.x()) * (avgMx - b.x()) + (avgMy - b.y()) * (avgMy - b.y()))))
                    .orElse(null);
            if (bestPatch == null) continue;

            double mineRadius = Math.sqrt((avgMx - bestPatch.x()) * (avgMx - bestPatch.x())
                    + (avgMy - bestPatch.y()) * (avgMy - bestPatch.y()));
            double depDist    = Math.sqrt((avgDx - bx) * (avgDx - bx) + (avgDy - by) * (avgDy - by));
            double patchC2C   = Math.sqrt((bestPatch.x() - bx) * (bestPatch.x() - bx)
                    + (bestPatch.y() - by) * (bestPatch.y() - by));
            double e2e        = patchC2C - 2.75 - 1.0;

            double avgWalkTo   = walkToDists.stream().mapToDouble(d -> d).average().orElse(0);
            double avgWalkBack = walkBackDists.stream().mapToDouble(d -> d).average().orElse(0);
            double deviation   = e2e > 0 ? (avgWalkTo + avgWalkBack) / 2.0 / e2e : 0;

            totalMineRadius += mineRadius;
            totalDepDist    += depDist;
            totalDeviation  += deviation;
            workerCount++;

            System.out.printf("%-12s | %7.2f | %7.3f | %7.3f | %7.3f | %7.3f | %11.3f | %7.3f%n",
                    tag, patchC2C, mineRadius, depDist, avgWalkTo, avgWalkBack, e2e, deviation);
        }

        if (workerCount > 0) {
            System.out.println();
            System.out.printf("AGGREGATE (n=%d workers):%n", workerCount);
            System.out.printf("  Effective mining radius:    %.3f  (EM uses %.3f)%n",
                    totalMineRadius / workerCount, 1.0);
            System.out.printf("  Effective deposit distance: %.3f  (EM uses %.3f)%n",
                    totalDepDist / workerCount, 2.75);
            System.out.printf("  Path deviation factor:      %.3f  (1.0 = straight line)%n",
                    totalDeviation / workerCount);
        }
    }

    private WorkerCadenceBaseline loadCadence(String resource) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (is == null) throw new IOException("Resource not found: " + resource);
            return WorkerCadenceBaseline.fromJson(new String(is.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private CalibrationBaseline loadCalibration(String resource) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (is == null) throw new IOException("Resource not found: " + resource);
            return CalibrationBaseline.fromJson(new String(is.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}

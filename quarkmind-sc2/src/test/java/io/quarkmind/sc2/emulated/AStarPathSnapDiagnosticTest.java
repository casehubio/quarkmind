package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.AStarPathfinder;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.TerrainGrid;
import io.quarkmind.sc2.replay.MapTerrainCache;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Diagnoses tile-center snapping in A* paths on different grid sizes.
 * Confirms whether the half-integer offset causes different walk distances.
 */
@Tag("diagnostic")
class AStarPathSnapDiagnosticTest {

    private static final float BASE_X = 124.5f;
    private static final float BASE_Y = 159.5f;
    private static final float OFF_X = 8f - BASE_X;  // -116.5
    private static final float OFF_Y = 8f - BASE_Y;  // -151.5

    // Mineral patches near the starting base on TorchesAIE (within ~12u)
    private static final float[][] NEARBY_PATCHES = {
        {125.0f, 165.5f, 1500},
        {129.0f, 165.5f, 750},
        {131.0f, 159.5f, 1500},
        {131.0f, 161.5f, 1500},
        {131.0f, 163.5f, 750},
        {132.0f, 160.5f, 750},
        {130.0f, 164.5f, 1500},
        {126.0f, 166.5f, 750},
    };

    @Test
    void diagnosePathSnapOnBothGrids() {
        var realTerrain = MapTerrainCache.loadFromClasspath("terrain/Torches AIE.pathgrid");
        TerrainGrid real = realTerrain.orElseGet(() -> TerrainGrid.flatMap(170, 220));
        TerrainGrid flat = TerrainGrid.emulatedMap();

        AStarPathfinder pathfinder = new AStarPathfinder();

        Point2d realBase = new Point2d(BASE_X, BASE_Y);
        Point2d flatBase = new Point2d(8f, 8f);

        System.out.println("=== A* Path Snap Diagnostic ===");
        System.out.println("Base: real=(" + BASE_X + "," + BASE_Y + ") flat=(8,8)");
        System.out.println("Offset: (" + OFF_X + "," + OFF_Y + ")");
        System.out.println();

        // Check obstacle tile marking for each patch
        System.out.println("--- Obstacle Tile Marking (radius 0.3) ---");
        for (float[] patch : NEARBY_PATCHES) {
            float px = patch[0], py = patch[1];
            int realObstacles = countObstacleTiles(px, py, 0.3f);
            int flatObstacles = countObstacleTiles(px + OFF_X, py + OFF_Y, 0.3f);
            System.out.printf("  Patch (%.1f,%.1f) -> real=%d tiles, flat (%.1f,%.1f)=%d tiles%n",
                px, py, realObstacles, px + OFF_X, py + OFF_Y, flatObstacles);
        }
        System.out.println();

        // Compare A* paths for each patch
        System.out.println("--- Per-Patch Walk Distance Comparison ---");
        System.out.printf("%-20s %12s %12s %12s %12s %12s%n",
            "Patch", "RealTarget", "RealSnapped", "FlatTarget", "FlatSnapped", "Delta");

        double totalRealRT = 0, totalFlatRT = 0;

        for (float[] patch : NEARBY_PATCHES) {
            float px = patch[0], py = patch[1];
            Point2d realPatch = new Point2d(px, py);
            Point2d flatPatch = new Point2d(px + OFF_X, py + OFF_Y);

            // Mining target: edge point from patch toward base
            Point2d realMineTarget = WorkerSimulation.edgePoint(realPatch, realBase, WorkerSimulation.MINERAL_PATCH_RADIUS);
            Point2d flatMineTarget = WorkerSimulation.edgePoint(flatPatch, flatBase, WorkerSimulation.MINERAL_PATCH_RADIUS);

            // Deposit target: edge point from base toward patch
            Point2d realDepTarget = WorkerSimulation.edgePoint(realBase, realPatch, WorkerSimulation.TOWN_HALL_RADIUS);
            Point2d flatDepTarget = WorkerSimulation.edgePoint(flatBase, flatPatch, WorkerSimulation.TOWN_HALL_RADIUS);

            // A* paths: mine target
            List<Point2d> realMinePath = pathfinder.findPath(real, realDepTarget, realMineTarget);
            List<Point2d> realMineSmoothed = AStarPathfinder.smoothPath(realMinePath, real);
            Point2d realMineSnapped = realMineSmoothed.isEmpty() ? realMineTarget : realMineSmoothed.get(realMineSmoothed.size() - 1);

            List<Point2d> flatMinePath = pathfinder.findPath(flat, flatDepTarget, flatMineTarget);
            List<Point2d> flatMineSmoothed = AStarPathfinder.smoothPath(flatMinePath, flat);
            Point2d flatMineSnapped = flatMineSmoothed.isEmpty() ? flatMineTarget : flatMineSmoothed.get(flatMineSmoothed.size() - 1);

            // A* paths: deposit target
            List<Point2d> realDepPath = pathfinder.findPath(real, realMineTarget, realDepTarget);
            List<Point2d> realDepSmoothed = AStarPathfinder.smoothPath(realDepPath, real);
            Point2d realDepSnapped = realDepSmoothed.isEmpty() ? realDepTarget : realDepSmoothed.get(realDepSmoothed.size() - 1);

            List<Point2d> flatDepPath = pathfinder.findPath(flat, flatMineTarget, flatDepTarget);
            List<Point2d> flatDepSmoothed = AStarPathfinder.smoothPath(flatDepPath, flat);
            Point2d flatDepSnapped = flatDepSmoothed.isEmpty() ? flatDepTarget : flatDepSmoothed.get(flatDepSmoothed.size() - 1);

            // Steady-state round trip: tile-center to tile-center
            double realRT = realMineSnapped.distanceTo(realDepSnapped) * 2;
            double flatRT = flatMineSnapped.distanceTo(flatDepSnapped) * 2;

            // Intended round trip: edge to edge
            double intendedRT = realMineTarget.distanceTo(realDepTarget) * 2;

            totalRealRT += realRT;
            totalFlatRT += flatRT;

            System.out.printf("%-20s %12.2f %12.2f %12.2f %12.2f %+12.1f%%%n",
                String.format("(%.0f,%.0f)", px, py),
                intendedRT, realRT, intendedRT, flatRT,
                (realRT - flatRT) / flatRT * 100);

            // Detailed tile coordinates
            System.out.printf("  Real: mine=(%d,%d)->(%5.1f,%5.1f) dep=(%d,%d)->(%5.1f,%5.1f)  gap=%d tiles%n",
                (int) realMineTarget.x(), (int) realMineTarget.y(), realMineSnapped.x(), realMineSnapped.y(),
                (int) realDepTarget.x(), (int) realDepTarget.y(), realDepSnapped.x(), realDepSnapped.y(),
                Math.abs((int) realMineTarget.x() - (int) realDepTarget.x())
                    + Math.abs((int) realMineTarget.y() - (int) realDepTarget.y()));
            System.out.printf("  Flat: mine=(%d,%d)->(%5.1f,%5.1f) dep=(%d,%d)->(%5.1f,%5.1f)  gap=%d tiles%n",
                (int) flatMineTarget.x(), (int) flatMineTarget.y(), flatMineSnapped.x(), flatMineSnapped.y(),
                (int) flatDepTarget.x(), (int) flatDepTarget.y(), flatDepSnapped.x(), flatDepSnapped.y(),
                Math.abs((int) flatMineTarget.x() - (int) flatDepTarget.x())
                    + Math.abs((int) flatMineTarget.y() - (int) flatDepTarget.y()));
        }

        System.out.println();
        System.out.printf("Total round-trip (all 8 patches): real=%.2f  flat=%.2f  delta=%.1f%%%n",
            totalRealRT, totalFlatRT, (totalRealRT - totalFlatRT) / totalFlatRT * 100);
        System.out.println();
        System.out.println("CONCLUSION: If real < flat, workers walk shorter distances on real terrain -> over-production.");
        System.out.println("Root cause: A* snaps targets to tile centers. Half-integer offset changes tile boundary crossings.");
    }

    private static int countObstacleTiles(float cx, float cy, float radius) {
        int count = 0;
        int minX = (int) (cx - radius);
        int maxX = (int) (cx + radius);
        int minY = (int) (cy - radius);
        int maxY = (int) (cy + radius);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                double dx = (x + 0.5) - cx;
                double dy = (y + 0.5) - cy;
                if (dx * dx + dy * dy <= (double) radius * radius) {
                    count++;
                }
            }
        }
        return count;
    }
}

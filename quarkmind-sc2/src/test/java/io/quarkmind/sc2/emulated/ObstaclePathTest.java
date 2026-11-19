package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.AStarPathfinder;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.TerrainGrid;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ObstaclePathTest {

    @Test
    void outboundPathAroundMineralLineIsLongerThanStraightLine() {
        var grid       = TerrainGrid.emulatedMap();
        var pathfinder = new AStarPathfinder();

        var start = new Point2d(8, 4);
        var end   = new Point2d(8, 12);

        // Place a wall of obstacles between start and end
        for (int x = 5; x <= 11; x++) {
            grid.markObstacle(x, 8);
        }

        var obstaclePath = pathfinder.findPath(grid, start, end);
        grid.clearAllObstacles();
        var directPath = pathfinder.findPath(grid, start, end);

        double obstacleDist = pathLength(obstaclePath);
        double directDist   = pathLength(directPath);

        assertTrue(obstacleDist > directDist,
                   "obstacle path should be longer: " + obstacleDist + " vs " + directDist);
    }

    @Test
    void returnPathFromPatchToBaseIsApproximatelyDirect() {
        var grid       = TerrainGrid.emulatedMap();
        var pathfinder = new AStarPathfinder();

        var thPos = new Point2d(10, 4);

        // Place mineral patches as obstacles in an arc
        // Edge patches should have clear return paths to TH
        Point2d edgePatchPos = null;
        for (int i = 0; i < 8; i++) {
            double angle = Math.PI * (0.2 + 0.6 * i / 7);
            float  px    = thPos.x() + (float) (6.7 * Math.cos(angle));
            float  py    = thPos.y() + (float) (6.7 * Math.sin(angle));
            grid.markCircularObstacle(px, py, WorkerSimulation.MINERAL_PATCH_RADIUS);
            if (i == 0) {edgePatchPos = new Point2d(px, py);}
        }

        // Worker position just outside the edge patch (not inside the obstacle)
        var    workerReturn = new Point2d(edgePatchPos.x() - 1.5f, edgePatchPos.y());
        var    returnPath   = pathfinder.findPath(grid, workerReturn, thPos);
        double returnDist   = pathLength(returnPath);
        double straightLine = workerReturn.distanceTo(thPos);

        // Return path should be within 50% of straight-line
        // (edge patches have mostly clear lines back to TH)
        assertTrue(returnDist < straightLine * 1.5,
                   "return path should be approximately direct: " + returnDist + " vs " + straightLine);
    }

    private static double pathLength(java.util.List<Point2d> path) {
        double len = 0;
        for (int i = 1; i < path.size(); i++) {
            len += path.get(i - 1).distanceTo(path.get(i));
        }
        return len;
    }
}

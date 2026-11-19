package io.quarkmind.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TerrainGridObstacleTest {

    private TerrainGrid grid16() {
        TerrainGrid.Height[][] g = new TerrainGrid.Height[16][16];
        for (var col : g) java.util.Arrays.fill(col, TerrainGrid.Height.LOW);
        return new TerrainGrid(16, 16, g);
    }

    @Test
    void markObstacleMakesCellUnwalkable() {
        var grid = grid16();
        assertTrue(grid.isWalkable(5, 5));
        grid.markObstacle(5, 5);
        assertFalse(grid.isWalkable(5, 5));
    }

    @Test
    void clearObstacleRestoresWalkability() {
        var grid = grid16();
        grid.markObstacle(5, 5);
        grid.clearObstacle(5, 5);
        assertTrue(grid.isWalkable(5, 5));
    }

    @Test
    void overlappingObstaclesRequireBothClears() {
        var grid = grid16();
        grid.markObstacle(5, 5);
        grid.markObstacle(5, 5);
        grid.clearObstacle(5, 5);
        assertFalse(grid.isWalkable(5, 5), "one mark still active");
        grid.clearObstacle(5, 5);
        assertTrue(grid.isWalkable(5, 5), "both cleared");
    }

    @Test
    void wallCellsRemainUnwalkableAfterObstacleClear() {
        TerrainGrid.Height[][] g = new TerrainGrid.Height[16][16];
        for (var col : g) java.util.Arrays.fill(col, TerrainGrid.Height.LOW);
        g[5][5] = TerrainGrid.Height.WALL;
        var grid = new TerrainGrid(16, 16, g);
        assertFalse(grid.isWalkable(5, 5));
        grid.markObstacle(5, 5);
        grid.clearObstacle(5, 5);
        assertFalse(grid.isWalkable(5, 5), "wall is still a wall");
    }

    @Test
    void outOfBoundsMarkIsIgnored() {
        var grid = grid16();
        assertDoesNotThrow(() -> grid.markObstacle(-1, 5));
        assertDoesNotThrow(() -> grid.markObstacle(99, 5));
        assertDoesNotThrow(() -> grid.clearObstacle(-1, 5));
    }

    @Test
    void clearAllObstaclesResetsEverything() {
        var grid = grid16();
        grid.markObstacle(3, 3);
        grid.markObstacle(7, 7);
        grid.clearAllObstacles();
        assertTrue(grid.isWalkable(3, 3));
        assertTrue(grid.isWalkable(7, 7));
    }

    @Test
    void markCircularObstacleBlocksCellsWithinRadius() {
        var grid = grid16();
        grid.markCircularObstacle(8.0f, 8.0f, 1.0f);
        assertFalse(grid.isWalkable(8, 8), "center");
        assertFalse(grid.isWalkable(7, 8), "left");
        assertFalse(grid.isWalkable(8, 7), "below");
        assertTrue(grid.isWalkable(6, 6), "outside radius");
    }

    @Test
    void clearCircularObstacleRestoresCells() {
        var grid = grid16();
        grid.markCircularObstacle(8.0f, 8.0f, 1.0f);
        grid.clearCircularObstacle(8.0f, 8.0f, 1.0f);
        assertTrue(grid.isWalkable(8, 8));
        assertTrue(grid.isWalkable(7, 8));
    }

    @Test
    void aStarRoutesAroundObstacle() {
        var grid = grid16();
        grid.markCircularObstacle(8.0f, 4.0f, 2.0f);
        var pathfinder = new AStarPathfinder();
        var path = pathfinder.findPath(grid, new Point2d(4, 4), new Point2d(12, 4));
        assertFalse(path.isEmpty(), "path should exist around obstacle");
        for (var wp : path) {
            assertTrue(grid.isWalkable((int) wp.x(), (int) wp.y()),
                "waypoint at " + wp + " must be walkable");
        }
    }

    @Test
    void aStarPathAroundObstacleIsLongerThanDirect() {
        var grid = grid16();
        var pathfinder = new AStarPathfinder();
        var directPath = pathfinder.findPath(grid, new Point2d(4, 4), new Point2d(12, 4));
        grid.markCircularObstacle(8.0f, 4.0f, 2.0f);
        var detourPath = pathfinder.findPath(grid, new Point2d(4, 4), new Point2d(12, 4));
        double directDist = pathLength(directPath);
        double detourDist = pathLength(detourPath);
        assertTrue(detourDist > directDist, "detour should be longer: " + detourDist + " vs " + directDist);
    }

    private static double pathLength(java.util.List<Point2d> path) {
        double len = 0;
        for (int i = 1; i < path.size(); i++) {
            len += path.get(i - 1).distanceTo(path.get(i));
        }
        return len;
    }
}

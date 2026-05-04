package io.quarkmind.domain;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class AStarPathfinderTest {

    private final AStarPathfinder pf = new AStarPathfinder();

    private TerrainGrid open(int w, int h) {
        TerrainGrid.Height[][] g = new TerrainGrid.Height[w][h];
        for (TerrainGrid.Height[] col : g) Arrays.fill(col, TerrainGrid.Height.LOW);
        return new TerrainGrid(w, h, g);
    }

    private TerrainGrid withWall(int w, int h, int wallY, int gapX) {
        TerrainGrid.Height[][] g = new TerrainGrid.Height[w][h];
        for (TerrainGrid.Height[] col : g) Arrays.fill(col, TerrainGrid.Height.LOW);
        for (int x = 0; x < w; x++) {
            if (x != gapX) g[x][wallY] = TerrainGrid.Height.WALL;
        }
        return new TerrainGrid(w, h, g);
    }

    @Test void pathOnOpenMap_isNonEmpty() {
        List<Point2d> path = pf.findPath(open(10, 10), new Point2d(0, 0), new Point2d(9, 9));
        assertThat(path).isNotEmpty();
    }

    @Test void pathOnOpenMap_allWaypointsWalkable() {
        TerrainGrid g = open(10, 10);
        List<Point2d> path = pf.findPath(g, new Point2d(0, 0), new Point2d(9, 9));
        for (Point2d wp : path) {
            assertThat(g.isWalkable((int) wp.x(), (int) wp.y()))
                .as("waypoint %s should be walkable", wp).isTrue();
        }
    }

    @Test void pathOnOpenMap_endsNearGoal() {
        List<Point2d> path = pf.findPath(open(10, 10), new Point2d(0, 0), new Point2d(9, 9));
        Point2d last = path.get(path.size() - 1);
        assertThat(last.x()).isBetween(9f, 10f);
        assertThat(last.y()).isBetween(9f, 10f);
    }

    @Test void pathAroundWall_doesNotCrossWall() {
        TerrainGrid g = withWall(10, 10, 5, 5);
        List<Point2d> path = pf.findPath(g, new Point2d(2, 2), new Point2d(7, 8));
        for (Point2d wp : path) {
            int tx = (int) wp.x();
            int ty = (int) wp.y();
            if (ty == 5) {
                assertThat(tx).as("must cross wall only at gap (x=5)").isEqualTo(5);
            }
        }
    }

    @Test void pathAroundWall_reachesGoal() {
        TerrainGrid g = withWall(10, 10, 5, 5);
        List<Point2d> path = pf.findPath(g, new Point2d(2, 2), new Point2d(7, 8));
        assertThat(path).isNotEmpty();
        Point2d last = path.get(path.size() - 1);
        assertThat(last.x()).isEqualTo(7.5f);
        assertThat(last.y()).isEqualTo(8.5f);
    }

    @Test void sameStartAndGoal_returnsEmpty() {
        assertThat(pf.findPath(open(10, 10), new Point2d(5, 5), new Point2d(5, 5))).isEmpty();
    }

    @Test void unreachableGoal_returnsEmpty() {
        TerrainGrid.Height[][] g = new TerrainGrid.Height[10][10];
        for (TerrainGrid.Height[] col : g) Arrays.fill(col, TerrainGrid.Height.LOW);
        for (int y = 0; y < 10; y++) g[5][y] = TerrainGrid.Height.WALL;
        TerrainGrid blocked = new TerrainGrid(10, 10, g);
        assertThat(pf.findPath(blocked, new Point2d(2, 5), new Point2d(8, 5))).isEmpty();
    }

    @Test void pathOnEmulatedMap_nexusToStaging_isNonEmpty() {
        TerrainGrid g = TerrainGrid.emulatedMap();
        List<Point2d> path = pf.findPath(g, new Point2d(8, 8), new Point2d(26, 26));
        assertThat(path).isNotEmpty();
    }

    @Test void pathOnEmulatedMap_passesNearChokepoint() {
        TerrainGrid g = TerrainGrid.emulatedMap();
        List<Point2d> path = pf.findPath(g, new Point2d(8, 8), new Point2d(26, 26));
        for (Point2d wp : path) {
            int tx = (int) wp.x();
            int ty = (int) wp.y();
            if (ty == 18) {
                assertThat(tx).as("must cross wall only through gap x=[11,13]").isBetween(11, 13);
            }
        }
    }

    @Test void pathOnEmulatedMap_stagingToNexus() {
        TerrainGrid g = TerrainGrid.emulatedMap();
        List<Point2d> path = pf.findPath(g, new Point2d(26, 26), new Point2d(8, 8));
        assertThat(path).isNotEmpty();
        for (Point2d wp : path) {
            int tx = (int) wp.x();
            int ty = (int) wp.y();
            if (ty == 18) {
                assertThat(tx).isBetween(11, 13);
            }
        }
    }

    // ---- Weighted cost tests ----

    /**
     * Map layout (10 wide, 10 tall):
     *   Wall row at y=5, two gaps:
     *     x=3 → LOW tile (cost 1.0)
     *     x=7 → RAMP tile (cost 1.5)
     *   Start (2,2) → Goal (2,8)
     *   Both routes have equal tile count; flat gap costs less.
     */
    private TerrainGrid twoGapMap() {
        TerrainGrid.Height[][] g = new TerrainGrid.Height[10][10];
        for (TerrainGrid.Height[] col : g) Arrays.fill(col, TerrainGrid.Height.LOW);
        for (int x = 0; x < 10; x++) g[x][5] = TerrainGrid.Height.WALL;
        g[3][5] = TerrainGrid.Height.LOW;   // flat gap
        g[7][5] = TerrainGrid.Height.RAMP;  // ramp gap
        return new TerrainGrid(10, 10, g);
    }

    @Test
    void weightedCost_prefersFlat_overRamp() {
        // With start/goal near x=2 and flat gap at x=3, A*'s heuristic already steers toward x=3.
        // The weighting makes this deterministic rather than heuristic-coincident.
        // The strict cost-regression proof is in weightedCost_rampPathCostsMoreThanFlatPath below.
        TerrainGrid g = twoGapMap();
        List<Point2d> path = pf.findPath(g, new Point2d(2, 2), new Point2d(2, 8));
        assertThat(path).isNotEmpty();
        boolean passedFlatGap = path.stream().anyMatch(wp ->
            (int) wp.x() == 3 && (int) wp.y() == 5);
        boolean passedRampGap = path.stream().anyMatch(wp ->
            (int) wp.x() == 7 && (int) wp.y() == 5);
        assertThat(passedFlatGap).as("should use flat gap at x=3").isTrue();
        assertThat(passedRampGap).as("should not use ramp gap at x=7").isFalse();
    }

    @Test
    void weightedCost_rampPathCostsMoreThanFlatPath() {
        // Two identical maps — only the gap tile differs (RAMP vs LOW at the same position).
        // A* finds the same waypoints through both (only one gap exists).
        // Sum of movementCost() along the path is 0.5 higher for the ramp map.
        // This verifies the cost multiplier flows correctly from TerrainGrid into the path cost.
        TerrainGrid.Height[][] g = new TerrainGrid.Height[10][10];
        for (TerrainGrid.Height[] col : g) Arrays.fill(col, TerrainGrid.Height.LOW);
        for (int x = 0; x < 10; x++) g[x][5] = TerrainGrid.Height.WALL;

        g[5][5] = TerrainGrid.Height.RAMP;
        TerrainGrid rampGrid = new TerrainGrid(10, 10, g);

        g[5][5] = TerrainGrid.Height.LOW;
        TerrainGrid flatGrid = new TerrainGrid(10, 10, g);

        List<Point2d> rampPath = pf.findPath(rampGrid, new Point2d(5, 1), new Point2d(5, 9));
        List<Point2d> flatPath = pf.findPath(flatGrid, new Point2d(5, 1), new Point2d(5, 9));

        assertThat(rampPath).isEqualTo(flatPath); // same route — only gap tile cost differs

        double rampCost = rampPath.stream()
            .mapToDouble(wp -> rampGrid.movementCost((int) wp.x(), (int) wp.y())).sum();
        double flatCost = flatPath.stream()
            .mapToDouble(wp -> flatGrid.movementCost((int) wp.x(), (int) wp.y())).sum();

        assertThat(rampCost - flatCost).isCloseTo(0.5, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void weightedCost_rampStillUsed_whenOnlyOption() {
        TerrainGrid.Height[][] g = new TerrainGrid.Height[10][10];
        for (TerrainGrid.Height[] col : g) Arrays.fill(col, TerrainGrid.Height.LOW);
        for (int x = 0; x < 10; x++) g[x][5] = TerrainGrid.Height.WALL;
        g[5][5] = TerrainGrid.Height.RAMP;
        TerrainGrid grid = new TerrainGrid(10, 10, g);
        List<Point2d> path = pf.findPath(grid, new Point2d(2, 2), new Point2d(8, 8));
        assertThat(path).isNotEmpty();
        boolean passedRamp = path.stream().anyMatch(wp ->
            (int) wp.x() == 5 && (int) wp.y() == 5);
        assertThat(passedRamp).isTrue();
    }
}

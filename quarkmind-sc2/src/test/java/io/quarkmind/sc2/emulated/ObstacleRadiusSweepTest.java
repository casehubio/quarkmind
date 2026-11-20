package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.AStarPathfinder;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.TerrainGrid;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.DoubleSummaryStatistics;
import java.util.List;

@Tag("diagnostic")
class ObstacleRadiusSweepTest {

    private static final AStarPathfinder PATHFINDER = new AStarPathfinder();

    private static final Point2d[] BASE_POSITIONS = {
            new Point2d(10, 10),
            new Point2d(15, 8),
            new Point2d(8, 12),
            new Point2d(20, 10),
            new Point2d(12, 6),
    };

    @Test
    void sweepObstacleRadiusForPathDeviation() {
        System.out.printf("%-8s | %-6s | %-8s | %-8s | %-8s | %-8s | %-8s | %s%n",
                "Radius", "Runs", "Mean%", "Median%", "Min%", "Max%", "σ%", "RetMean%");
        System.out.println("-".repeat(85));

        for (double radius = 0.2; radius <= 1.0; radius += 0.05) {
            List<Double> outboundDeviations = new ArrayList<>();
            List<Double> returnDeviations = new ArrayList<>();

            for (Point2d basePos : BASE_POSITIONS) {
                TerrainGrid grid = TerrainGrid.emulatedMap();
                Point2d[] patchPositions = MineralPatch.patchPositions(basePos);

                for (Point2d pp : patchPositions) {
                    grid.markCircularObstacle(pp.x(), pp.y(), (float) radius);
                }

                for (int i = 0; i < patchPositions.length; i++) {
                    Point2d patchPos = patchPositions[i];
                    Point2d miningTarget = WorkerSimulation.edgePoint(patchPos, basePos,
                            WorkerSimulation.MINERAL_PATCH_RADIUS);
                    Point2d depositTarget = WorkerSimulation.edgePoint(basePos, patchPos,
                            WorkerSimulation.TOWN_HALL_RADIUS);

                    double straightOutbound = basePos.distanceTo(miningTarget);
                    double straightReturn = miningTarget.distanceTo(depositTarget);

                    List<Point2d> outPath = PATHFINDER.findPath(grid, basePos, miningTarget);
                    List<Point2d> retPath = PATHFINDER.findPath(grid, miningTarget, depositTarget);

                    double outDist = pathLength(basePos, outPath);
                    double retDist = pathLength(miningTarget, retPath);

                    if (straightOutbound > 0.1) {
                        double outDev = (outDist - straightOutbound) / straightOutbound * 100.0;
                        outboundDeviations.add(outDev);
                    }
                    if (straightReturn > 0.1) {
                        double retDev = (retDist - straightReturn) / straightReturn * 100.0;
                        returnDeviations.add(retDev);
                    }
                }

                grid.clearAllObstacles();
            }

            double[] sorted = outboundDeviations.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            DoubleSummaryStatistics stats = outboundDeviations.stream()
                    .mapToDouble(Double::doubleValue).summaryStatistics();
            double mean = stats.getAverage();
            double median = sorted.length % 2 == 0
                    ? (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2.0
                    : sorted[sorted.length / 2];
            double variance = outboundDeviations.stream()
                    .mapToDouble(d -> (d - mean) * (d - mean)).average().orElse(0);
            double sigma = Math.sqrt(variance);

            double retMean = returnDeviations.stream().mapToDouble(Double::doubleValue).average().orElse(0);

            System.out.printf("%-8.2f | %-6d | %+7.1f | %+7.1f | %+7.1f | %+7.1f | %7.1f | %+7.1f%n",
                    radius, sorted.length, mean, median, stats.getMin(), stats.getMax(), sigma, retMean);
        }

        System.out.println();
        System.out.println("Target: outbound mean ≈ +6%, return ≈ 0% (SC2 cadence data #690)");
    }

    @Test
    void perPatchDeviationAtSelectedRadii() {
        double[] radii = {0.3, 0.4, 0.5, 0.6, 0.7};

        for (double radius : radii) {
            System.out.printf("%n=== Radius %.2f ===%n", radius);
            System.out.printf("%-6s | %-10s | %-8s | %-8s | %-8s | %-8s%n",
                    "Base", "Patch", "E2E", "OutPath", "OutDev%", "RetDev%");
            System.out.println("-".repeat(65));

            for (int b = 0; b < BASE_POSITIONS.length; b++) {
                Point2d basePos = BASE_POSITIONS[b];
                TerrainGrid grid = TerrainGrid.emulatedMap();
                Point2d[] patchPositions = MineralPatch.patchPositions(basePos);

                for (Point2d pp : patchPositions) {
                    grid.markCircularObstacle(pp.x(), pp.y(), (float) radius);
                }

                for (int i = 0; i < patchPositions.length; i++) {
                    Point2d patchPos = patchPositions[i];
                    Point2d miningTarget = WorkerSimulation.edgePoint(patchPos, basePos,
                            WorkerSimulation.MINERAL_PATCH_RADIUS);
                    Point2d depositTarget = WorkerSimulation.edgePoint(basePos, patchPos,
                            WorkerSimulation.TOWN_HALL_RADIUS);

                    double e2e = basePos.distanceTo(patchPos)
                            - WorkerSimulation.TOWN_HALL_RADIUS
                            - WorkerSimulation.MINERAL_PATCH_RADIUS;
                    double straightOut = basePos.distanceTo(miningTarget);
                    double straightRet = miningTarget.distanceTo(depositTarget);

                    List<Point2d> outPath = PATHFINDER.findPath(grid, basePos, miningTarget);
                    List<Point2d> retPath = PATHFINDER.findPath(grid, miningTarget, depositTarget);

                    double outDist = pathLength(basePos, outPath);
                    double retDist = pathLength(miningTarget, retPath);

                    double outDev = straightOut > 0.1 ? (outDist - straightOut) / straightOut * 100.0 : 0;
                    double retDev = straightRet > 0.1 ? (retDist - straightRet) / straightRet * 100.0 : 0;

                    String patchLabel = (i < MineralPatch.CLOSE_PATCHES_PER_BASE ? "close" : "far") + "-" + i;

                    System.out.printf("%-6d | %-10s | %7.2f | %7.2f | %+7.1f | %+7.1f%n",
                            b, patchLabel, e2e, outDist, outDev, retDev);
                }

                grid.clearAllObstacles();
            }
        }
    }

    private static double pathLength(Point2d start, List<Point2d> path) {
        if (path.isEmpty()) return 0;
        double len = start.distanceTo(path.get(0));
        for (int i = 1; i < path.size(); i++) {
            len += path.get(i - 1).distanceTo(path.get(i));
        }
        return len;
    }
}

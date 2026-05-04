package io.quarkmind.domain;

import java.util.*;

/**
 * Stateless 8-directional A* pathfinder.
 * Returns tile-centre world coordinates (x+0.5f, y+0.5f) excluding the start tile.
 * Reusable by both the emulated engine and the real SC2 engine.
 */
public final class AStarPathfinder {

    private static final int[][] DIRS = {
        {1, 0}, {-1, 0}, {0, 1}, {0, -1},
        {1, 1}, {1,-1}, {-1, 1}, {-1,-1}
    };
    private static final double DIAG = Math.sqrt(2);

    public List<Point2d> findPath(TerrainGrid grid, Point2d from, Point2d to) {
        int[] start = nearestWalkable(grid, (int) from.x(), (int) from.y());
        int[] goal  = nearestWalkable(grid, (int) to.x(),   (int) to.y());
        final int sx = start[0], sy = start[1];
        final int gx = goal[0],  gy = goal[1];

        if (sx == gx && sy == gy) return List.of();

        record Node(int x, int y, double g, Node parent) {}

        PriorityQueue<Node> open = new PriorityQueue<>(
            Comparator.comparingDouble(n ->
                n.g() + Math.sqrt((n.x() - gx) * (double)(n.x() - gx)
                                + (n.y() - gy) * (double)(n.y() - gy))));

        // Lazy-deletion A*: tiles can be inserted multiple times with different g values.
        // The consistent Euclidean heuristic guarantees the first expansion of any tile
        // is always via the optimal path, so subsequent pops are safely discarded.
        boolean[][] closed = new boolean[grid.width()][grid.height()];
        open.add(new Node(sx, sy, 0, null));

        while (!open.isEmpty()) {
            Node cur = open.poll();
            if (closed[cur.x()][cur.y()]) continue;
            closed[cur.x()][cur.y()] = true;

            if (cur.x() == gx && cur.y() == gy) {
                Deque<Point2d> path = new ArrayDeque<>();
                for (Node n = cur; n.parent() != null; n = n.parent()) {
                    path.addFirst(new Point2d(n.x() + 0.5f, n.y() + 0.5f));
                }
                return new ArrayList<>(path);
            }

            for (int[] d : DIRS) {
                int nx = cur.x() + d[0];
                int ny = cur.y() + d[1];
                if (!grid.isWalkable(nx, ny) || closed[nx][ny]) continue;
                double cost = (d[0] != 0 && d[1] != 0) ? DIAG : 1.0;
                cost *= grid.movementCost(nx, ny);
                open.add(new Node(nx, ny, cur.g() + cost, cur));
            }
        }
        return List.of();
    }

    /**
     * Greedy string-pulling: starting from each waypoint, find the furthest later
     * waypoint reachable in a straight line (Bresenham LOS, no WALL tiles crossed).
     * Skips intermediate waypoints, reducing zigzag on open ground.
     * Returns the original path unchanged if it has ≤ 2 points.
     */
    public static List<Point2d> smoothPath(List<Point2d> path, TerrainGrid grid) {
        if (path.size() <= 2) return path;

        List<Point2d> result = new ArrayList<>();
        result.add(path.get(0));
        int i = 0;

        while (i < path.size() - 1) {
            int farthest = i + 1;
            for (int j = path.size() - 1; j > i + 1; j--) {
                if (hasLos(grid, path.get(i), path.get(j))) {
                    farthest = j;
                    break;
                }
            }
            result.add(path.get(farthest));
            i = farthest;
        }

        return result;
    }

    /**
     * Bresenham line-of-sight check. Returns true if the straight line between
     * {@code from} and {@code to} (in world tile-centre coordinates) crosses no WALL tiles.
     */
    private static boolean hasLos(TerrainGrid grid, Point2d from, Point2d to) {
        int x0 = (int) from.x(), y0 = (int) from.y();
        int x1 = (int) to.x(),   y1 = (int) to.y();

        int dx  = Math.abs(x1 - x0);
        int dy  = Math.abs(y1 - y0);
        int sx  = x0 < x1 ? 1 : -1;
        int sy  = y0 < y1 ? 1 : -1;
        int err = dx - dy;
        int x = x0, y = y0;

        while (x != x1 || y != y1) {
            if (!grid.isWalkable(x, y)) return false;
            int e2 = 2 * err;
            if (e2 > -dy) { err -= dy; x += sx; }
            if (e2 <  dx) { err += dx; y += sy; }
        }
        return grid.isWalkable(x1, y1);
    }

    private static int[] nearestWalkable(TerrainGrid grid, int x, int y) {
        // Clamp out-of-bounds coordinates to grid edge before spiral search.
        // Without this, a target like (224, 224) on a 64×64 grid would require
        // a search radius of 160+ to reach valid tiles — far beyond the loop limit.
        x = Math.max(0, Math.min(grid.width()  - 1, x));
        y = Math.max(0, Math.min(grid.height() - 1, y));
        if (grid.isWalkable(x, y)) return new int[]{x, y};
        for (int r = 1; r <= Math.max(grid.width(), grid.height()); r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    if (Math.abs(dx) == r || Math.abs(dy) == r) {
                        if (grid.isWalkable(x + dx, y + dy))
                            return new int[]{x + dx, y + dy};
                    }
                }
            }
        }
        return new int[]{x, y}; // no walkable tile found — caller produces an empty path harmlessly
    }
}

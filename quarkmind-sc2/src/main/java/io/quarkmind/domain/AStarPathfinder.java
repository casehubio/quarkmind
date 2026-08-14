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
     * Sub-tile line-of-sight check. Samples every 0.4 world units along the line
     * between {@code from} and {@code to} and returns false if any sample falls in
     * a WALL tile. This finer resolution (below the 0.5-tile movement speed) ensures
     * that movement along a smoothed segment never crosses a wall tile between steps.
     * Tile-centre Bresenham is insufficient because continuous movement at speed=0.5
     * can land in tiles the centre-to-centre line misses.
     */
    private static boolean hasLos(TerrainGrid grid, Point2d from, Point2d to) {
        double dx   = to.x() - from.x();
        double dy   = to.y() - from.y();
        double dist = Math.sqrt(dx * dx + dy * dy);
        if (dist < 0.001) return grid.isWalkable((int) from.x(), (int) from.y());

        double nx = dx / dist;
        double ny = dy / dist;
        double step = 0.4; // below movement speed (0.5) — catches all wall crossings

        for (double d = 0; d <= dist + step; d += step) {
            double t = Math.min(d, dist);
            int tx = (int)(from.x() + nx * t);
            int ty = (int)(from.y() + ny * t);
            if (!grid.isWalkable(tx, ty)) return false;
        }
        return true;
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

package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.AStarPathfinder;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.TerrainGrid;
import org.jboss.logging.Logger;

import java.util.*;

/** A* based movement: computes and follows waypoint queues per unit. */
public class PathfindingMovement implements MovementStrategy {

    private static final Logger log = Logger.getLogger(PathfindingMovement.class);

    private final TerrainGrid grid;
    private final AStarPathfinder pathfinder = new AStarPathfinder();
    private final Map<String, Deque<Point2d>> waypoints   = new HashMap<>();
    private final Map<String, Point2d>        lastTargets = new HashMap<>();

    public PathfindingMovement(TerrainGrid grid) { this.grid = grid; }

    @Override
    public Point2d advance(String unitTag, Point2d current, Point2d target, double speed) {
        // Recompute path when target changes
        if (!target.equals(lastTargets.get(unitTag))) {
            List<Point2d> path = pathfinder.findPath(grid, current, target);
            List<Point2d> smoothed = AStarPathfinder.smoothPath(path, grid);
            log.infof("[PATHFINDING] %s: %d waypoints → %d smoothed from (%.1f,%.1f) to (%.1f,%.1f)%s",
                unitTag, path.size(), smoothed.size(), current.x(), current.y(), target.x(), target.y(),
                path.isEmpty() ? " — EMPTY, falling back to direct movement" : "");
            waypoints.put(unitTag, new ArrayDeque<>(smoothed));
            lastTargets.put(unitTag, target);
        }

        Deque<Point2d> queue = waypoints.get(unitTag);
        if (queue == null || queue.isEmpty()) {
            // Two cases:
            // 1. Unit consumed all waypoints and is within ~1 tile of target (final approach) → direct is safe
            // 2. A* returned empty path (unreachable/out-of-bounds target) → unit is far away → stay put
            if (EmulatedGame.distance(current, target) < 1.5) {
                return EmulatedGame.stepToward(current, target, speed);
            }
            return current; // unreachable target — stay put rather than walk through walls
        }

        Point2d next   = queue.peek();
        Point2d newPos = EmulatedGame.stepToward(current, next, speed);

        // Advance to next waypoint when current one is reached (within 0.1 tiles)
        if (EmulatedGame.distance(newPos, next) < 0.1) {
            queue.poll();
        }
        return newPos;
    }

    @Override
    public void clearUnit(String unitTag) {
        waypoints.remove(unitTag);
        lastTargets.remove(unitTag);
    }

    @Override
    public void invalidatePath(String unitTag) {
        // Clear cached path and target so advance() recomputes from current position next tick.
        // lastTargets is also cleared so the target-change check triggers a fresh A* call.
        waypoints.remove(unitTag);
        lastTargets.remove(unitTag);
    }

    @Override
    public void reset() {
        waypoints.clear();
        lastTargets.clear();
    }
}

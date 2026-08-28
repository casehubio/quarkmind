package io.quarkmind.sc2.replay;

import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.TerrainGrid;
import io.quarkmind.domain.UnitType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class UnitOrderTracker {

    private static final float ARRIVAL_THRESHOLD = 0.5f;
    private              TerrainGrid terrain;


    private final Map<String, UnitOrder> activeOrders = new HashMap<>();
    private final List<UnitOrder> pending = new ArrayList<>();
    private int pendingCursor = 0;

    public void loadOrders(List<UnitOrder> orders) {
        pending.clear();
        pending.addAll(orders);
        pendingCursor = 0;
        activeOrders.clear();
    }

    public void setTerrain(TerrainGrid terrain) {
        this.terrain = terrain;
    }


    public void advance(long currentLoop, Map<String, Point2d> positions,
                        Map<String, UnitType> unitTypes) {
        while (pendingCursor < pending.size()
               && pending.get(pendingCursor).loop() <= currentLoop) {
            UnitOrder o = pending.get(pendingCursor++);
            activeOrders.put(o.unitTag(), o);
        }

        float secondsPerTick = 22 / 22.4f;

        for (Map.Entry<String, UnitOrder> entry : activeOrders.entrySet()) {
            String    tag     = entry.getKey();
            UnitOrder order   = entry.getValue();
            Point2d   current = positions.get(tag);
            if (current == null) {continue;}

            Point2d target = resolveTarget(order, positions);
            if (target == null) {continue;}

            float dx   = target.x() - current.x();
            float dy   = target.y() - current.y();
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            if (dist <= ARRIVAL_THRESHOLD) {continue;}

            float speed = (float) SC2Data.unitSpeed(
                    unitTypes.getOrDefault(tag, UnitType.UNKNOWN));
            float step = speed * secondsPerTick;

            Point2d next;
            if (step >= dist) {
                next = target;
            } else {
                float ratio = step / dist;
                next = new Point2d(current.x() + dx * ratio, current.y() + dy * ratio);
            }

            if (terrain != null) {
                next = clampToWalkable(current, next);
                if (next == null) {continue;}
            }

            positions.put(tag, next);
        }
    }

    private Point2d clampToWalkable(Point2d from, Point2d to) {
        int x0     = (int) from.x(), y0 = (int) from.y();
        int x1     = (int) to.x(), y1 = (int) to.y();
        int steps = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
        if (steps == 0) {
            return terrain.isWalkable(x1, y1) ? to : null;
        }
        float   sx   = (to.x() - from.x()) / steps;
        float   sy   = (to.y() - from.y()) / steps;
        Point2d last = from;
        for (int i = 1; i <= steps; i++) {
            float nx = from.x() + sx * i;
            float ny = from.y() + sy * i;
            if (!terrain.isWalkable((int) nx, (int) ny)) {
                return last.equals(from) ? null : last;
            }
            last = new Point2d(nx, ny);
        }
        return to;
    }

    public void removeUnit(String tag) {
        activeOrders.remove(tag);
    }

    public void reset() {
        activeOrders.clear();
        pendingCursor = 0;
    }

    private static Point2d resolveTarget(UnitOrder order, Map<String, Point2d> positions) {
        if (order.isMove())   return order.targetPos();
        if (order.isFollow()) return positions.get(order.targetUnitTag());
        return null;
    }
}

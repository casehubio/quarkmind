package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.SC2Data;

class SimulatedWorker {
    static final double MINERAL_CARGO = 5.0;
    static final double RICH_MINERAL_CARGO = 7.0;

    static final int MINING_DURATION_LOOPS = 46;
    static final int MINING_DURATION_TICKS =
            (int) Math.ceil((double) MINING_DURATION_LOOPS / SC2Data.LOOPS_PER_TICK);
    static final double GAS_CARGO = 4.0;
    static final int GAS_HARVEST_DURATION_LOOPS_DEFAULT = 29;
    static int GAS_HARVEST_DURATION_TICKS =
            (int) Math.ceil((double) GAS_HARVEST_DURATION_LOOPS_DEFAULT / SC2Data.LOOPS_PER_TICK);

    static void setGasHarvestDurationLoops(int loops) {
        GAS_HARVEST_DURATION_TICKS = (int) Math.ceil((double) loops / SC2Data.LOOPS_PER_TICK);
    }

    static void resetGasHarvestDuration() {
        GAS_HARVEST_DURATION_TICKS =
                (int) Math.ceil((double) GAS_HARVEST_DURATION_LOOPS_DEFAULT / SC2Data.LOOPS_PER_TICK);
    }
    static final double ARRIVAL_THRESHOLD = 0.5;

    final String tag;
    Point2d position;
    WorkerTask task;
    int baseIndex;

    Point2d targetPosition;
    MineralPatch assignedPatch;
    GasSlot assignedGasSlot;
    double cargo;
    int cycleTicksRemaining;

    SimulatedWorker(String tag, Point2d position, int baseIndex) {
        this.tag = tag;
        this.position = position;
        this.baseIndex = baseIndex;
        this.task = WorkerTask.IDLE;
    }

    boolean isAtTarget() {
        return targetPosition != null
                && position.distanceTo(targetPosition) <= ARRIVAL_THRESHOLD;
    }

    void moveToward(double distancePerTick) {
        if (targetPosition == null) return;
        double dist = position.distanceTo(targetPosition);
        if (dist <= distancePerTick) {
            position = targetPosition;
        } else {
            float dx = targetPosition.x() - position.x();
            float dy = targetPosition.y() - position.y();
            float ratio = (float) (distancePerTick / dist);
            position = new Point2d(
                    position.x() + dx * ratio,
                    position.y() + dy * ratio
            );
        }
    }
}

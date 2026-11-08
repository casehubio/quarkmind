package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.Point2d;

class SimulatedWorker {
    static final double MINERAL_CARGO = 5.0;
    static final double RICH_MINERAL_CARGO = 7.0;

    static final int MINING_DURATION_LOOPS = 16;
    static final double GAS_CARGO_DEFAULT = 4.0;
    static double GAS_CARGO = GAS_CARGO_DEFAULT;
    static final double GAS_HARVEST_DURATION_LOOPS_DEFAULT = 31.696;
    static double GAS_HARVEST_DURATION_LOOPS = GAS_HARVEST_DURATION_LOOPS_DEFAULT;

    static void setGasHarvestDurationLoops(double loops) {
        GAS_HARVEST_DURATION_LOOPS = loops;
    }

    static void resetGasHarvestDuration() {
        GAS_HARVEST_DURATION_LOOPS = GAS_HARVEST_DURATION_LOOPS_DEFAULT;
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
    double cycleLoopsRemaining;

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

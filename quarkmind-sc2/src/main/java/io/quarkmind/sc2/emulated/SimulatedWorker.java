package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.Point2d;

class SimulatedWorker {
    static final double MINERAL_CARGO = 5.0;
    static final double RICH_MINERAL_CARGO = 7.0;

    static final double MINING_DURATION_LOOPS_DEFAULT = 44.576;
    static double MINING_DURATION_LOOPS = MINING_DURATION_LOOPS_DEFAULT;

    static void setMiningDurationLoops(double loops) {
        MINING_DURATION_LOOPS = loops;
    }

    static void resetMiningDuration() {
        MINING_DURATION_LOOPS = MINING_DURATION_LOOPS_DEFAULT;
    }
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
    static final double MAX_SPEED         = io.quarkmind.domain.SC2Data.unitSpeed(io.quarkmind.domain.UnitType.PROBE);
    static final double ACCELERATION      = 3.5;
    static final double DECELERATION      = 3.5;
    static final double LOOP_DURATION     = 1.0 / io.quarkmind.domain.SC2Data.GAME_LOOPS_PER_SECOND;
    static final int    LOOPS_PER_TICK    = io.quarkmind.domain.SC2Data.LOOPS_PER_TICK;


    final String tag;
    Point2d position;
    WorkerTask task;
    int baseIndex;

    Point2d targetPosition;
    MineralPatch assignedPatch;
    GasSlot assignedGasSlot;
    double cargo;
    double phaseEndLoop;
    double scoutReturnLoop;
    double currentSpeed;


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

    void moveToward() {moveLoops(LOOPS_PER_TICK);}

    int moveLoops(int loops) {
        if (targetPosition == null) {return loops;}
        for (int loop = 0; loop < loops; loop++) {
            double dist = position.distanceTo(targetPosition);
            if (dist <= ARRIVAL_THRESHOLD) {
                position     = targetPosition;
                currentSpeed = 0;
                return loop;
            }
            double decelDist = currentSpeed * currentSpeed / (2.0 * DECELERATION);
            if (dist <= decelDist) {
                currentSpeed = Math.max(0, currentSpeed - DECELERATION * LOOP_DURATION);
            } else if (currentSpeed < MAX_SPEED) {
                currentSpeed = Math.min(MAX_SPEED, currentSpeed + ACCELERATION * LOOP_DURATION);
            }
            double stepDist = currentSpeed * LOOP_DURATION;
            if (stepDist <= 0) {continue;}
            if (stepDist >= dist) {
                position     = targetPosition;
                currentSpeed = 0;
                return loop + 1;
            }
            float dx    = targetPosition.x() - position.x();
            float dy    = targetPosition.y() - position.y();
            float ratio = (float) (stepDist / dist);
            position = new Point2d(
                    position.x() + dx * ratio,
                    position.y() + dy * ratio
            );
        }
        return loops;
    }

}

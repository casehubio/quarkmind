package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.Point2d;

class SimulatedWorker {
    static final double COLLISION_RADIUS   = 0.75;
    static final double COLLISION_DIAMETER = COLLISION_RADIUS * 2;

    static final double MINERAL_CARGO = 5.0;
    static final double RICH_MINERAL_CARGO = 7.0;

    static double MINING_DURATION_LOOPS = WorkerPhysicsConfig.DEFAULT_MINING_DURATION_LOOPS;

    static void setMiningDurationLoops(double loops) {
        MINING_DURATION_LOOPS = loops;
    }

    static void resetMiningDuration() {
        MINING_DURATION_LOOPS = WorkerPhysicsConfig.DEFAULT_MINING_DURATION_LOOPS;
    }

    static final double GAS_CARGO_DEFAULT = 4.0;
    static double GAS_CARGO = GAS_CARGO_DEFAULT;
    static double GAS_HARVEST_DURATION_LOOPS = WorkerPhysicsConfig.DEFAULT_GAS_HARVEST_DURATION_LOOPS;

    static void setGasHarvestDurationLoops(double loops) {
        GAS_HARVEST_DURATION_LOOPS = loops;
    }

    static void resetGasHarvestDuration() {
        GAS_HARVEST_DURATION_LOOPS = WorkerPhysicsConfig.DEFAULT_GAS_HARVEST_DURATION_LOOPS;
    }

    static final double ARRIVAL_THRESHOLD = 0.5;
    static final double MAX_SPEED         = io.quarkmind.domain.SC2Data.unitSpeed(io.quarkmind.domain.UnitType.PROBE);
    static double ACCELERATION            = WorkerPhysicsConfig.DEFAULT_ACCELERATION;
    static double DECELERATION            = WorkerPhysicsConfig.DEFAULT_DECELERATION;
    static double PATCH_DOCKING_OVERHEAD_LOOPS = WorkerPhysicsConfig.DEFAULT_PATCH_DOCKING_OVERHEAD;
    static double INITIAL_WORKER_STAGGER_LOOPS = WorkerPhysicsConfig.DEFAULT_INITIAL_WORKER_STAGGER;
    static double COLLISION_SPEED_FACTOR       = WorkerPhysicsConfig.DEFAULT_COLLISION_SPEED_FACTOR;
    static double APPROACH_DOCKING_LOOPS        = WorkerPhysicsConfig.DEFAULT_APPROACH_DOCKING_LOOPS;

    static final double LOOP_DURATION     = 1.0 / io.quarkmind.domain.SC2Data.GAME_LOOPS_PER_SECOND;
    static final int    LOOPS_PER_TICK    = io.quarkmind.domain.SC2Data.LOOPS_PER_TICK;

    static void configure(WorkerPhysicsConfig config) {
        MINING_DURATION_LOOPS        = config.miningDurationLoops();
        GAS_HARVEST_DURATION_LOOPS   = config.gasHarvestDurationLoops();
        DEPOSIT_DURATION_LOOPS       = config.depositDurationLoops();
        ACCELERATION                 = config.acceleration();
        DECELERATION                 = config.deceleration();
        PATCH_DOCKING_OVERHEAD_LOOPS = config.patchDockingOverheadLoops();
        INITIAL_WORKER_STAGGER_LOOPS = config.initialWorkerStaggerLoops();
        COLLISION_SPEED_FACTOR       = config.collisionSpeedFactor();
        APPROACH_DOCKING_LOOPS        = config.approachDockingLoops();
        MineralPatch.PATCH_DISTANCE = config.patchDistance();
    }

    static void resetToDefaults() {
        configure(WorkerPhysicsConfig.defaults());
    }


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
    double walkStartLoop;


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

    boolean moveOneLoop() {
        if (targetPosition == null) {return false;}
        double dist = position.distanceTo(targetPosition);
        if (dist <= ARRIVAL_THRESHOLD) {
            position     = targetPosition;
            currentSpeed = 0;
            return true;
        }
        double decelDist = currentSpeed * currentSpeed / (2.0 * DECELERATION);
        if (dist <= decelDist) {
            currentSpeed = Math.max(0, currentSpeed - DECELERATION * LOOP_DURATION);
        } else if (currentSpeed < MAX_SPEED) {
            currentSpeed = Math.min(MAX_SPEED, currentSpeed + ACCELERATION * LOOP_DURATION);
        }
        double stepDist = currentSpeed * LOOP_DURATION;
        if (stepDist <= 0) {return false;}
        if (stepDist >= dist) {
            position     = targetPosition;
            currentSpeed = 0;
            return true;
        }
        float dx    = targetPosition.x() - position.x();
        float dy    = targetPosition.y() - position.y();
        float ratio = (float) (stepDist / dist);
        position = new Point2d(position.x() + dx * ratio, position.y() + dy * ratio);
        return false;
    }


    static double DEPOSIT_DURATION_LOOPS = WorkerPhysicsConfig.DEFAULT_DEPOSIT_DURATION_LOOPS;

    static void setDepositDurationLoops(double loops) {
        DEPOSIT_DURATION_LOOPS = loops;
    }

    static void resetDepositDuration() {
        DEPOSIT_DURATION_LOOPS = WorkerPhysicsConfig.DEFAULT_DEPOSIT_DURATION_LOOPS;
    }
}

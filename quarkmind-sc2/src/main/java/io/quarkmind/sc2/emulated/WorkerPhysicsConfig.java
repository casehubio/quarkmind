package io.quarkmind.sc2.emulated;

public record WorkerPhysicsConfig(
        double miningDurationLoops,
        double gasHarvestDurationLoops,
        double depositDurationLoops,
        double acceleration,
        double deceleration,
        double patchDockingOverheadLoops,
        float  patchDistance,
        double initialWorkerStaggerLoops,
        double collisionSpeedFactor
) {

    public static final double DEFAULT_MINING_DURATION_LOOPS      = 14.0;
    public static final double DEFAULT_GAS_HARVEST_DURATION_LOOPS = 26.0;
    public static final double DEFAULT_DEPOSIT_DURATION_LOOPS     = 0.0;
    public static final double DEFAULT_ACCELERATION               = 3.5;
    public static final double DEFAULT_DECELERATION               = 3.5;
    public static final double DEFAULT_PATCH_DOCKING_OVERHEAD     = 34.0;
    public static final float  DEFAULT_PATCH_DISTANCE             = 7.0f;
    public static final double DEFAULT_INITIAL_WORKER_STAGGER     = 6.0;
    public static final double DEFAULT_COLLISION_SPEED_FACTOR     = 0.952;

    public WorkerPhysicsConfig(double miningDurationLoops, double gasHarvestDurationLoops,
                               double depositDurationLoops, double acceleration,
                               double deceleration, double patchDockingOverheadLoops) {
        this(miningDurationLoops, gasHarvestDurationLoops, depositDurationLoops,
             acceleration, deceleration, patchDockingOverheadLoops, DEFAULT_PATCH_DISTANCE,
             DEFAULT_INITIAL_WORKER_STAGGER, DEFAULT_COLLISION_SPEED_FACTOR);
    }

    public WorkerPhysicsConfig(double miningDurationLoops, double gasHarvestDurationLoops,
                               double depositDurationLoops, double acceleration,
                               double deceleration, double patchDockingOverheadLoops,
                               float patchDistance) {
        this(miningDurationLoops, gasHarvestDurationLoops, depositDurationLoops,
             acceleration, deceleration, patchDockingOverheadLoops, patchDistance,
             DEFAULT_INITIAL_WORKER_STAGGER, DEFAULT_COLLISION_SPEED_FACTOR);
    }

    public static WorkerPhysicsConfig defaults() {
        return new WorkerPhysicsConfig(
                DEFAULT_MINING_DURATION_LOOPS,
                DEFAULT_GAS_HARVEST_DURATION_LOOPS,
                DEFAULT_DEPOSIT_DURATION_LOOPS,
                DEFAULT_ACCELERATION,
                DEFAULT_DECELERATION,
                DEFAULT_PATCH_DOCKING_OVERHEAD,
                DEFAULT_PATCH_DISTANCE,
                DEFAULT_INITIAL_WORKER_STAGGER,
                DEFAULT_COLLISION_SPEED_FACTOR
        );
    }
}

package io.quarkmind.sc2.emulated;

public record WorkerPhysicsConfig(
        double miningDurationLoops,
        double gasHarvestDurationLoops,
        double gasApproachDockingLoops,
        double depositDurationLoops,
        double acceleration,
        double deceleration,
        double patchDockingOverheadLoops,
        float patchDistance,
        double initialWorkerStaggerLoops,
        double collisionSpeedFactor,
        double approachDockingLoops,
        double rerouteThresholdLoops,
        float lowSaturationDodgeScale,
        double approachAnimationLoops,
        double gasDepositLockLoops,
        double gasDepositCongestionPerWorker
) {

    public static final double DEFAULT_MINING_DURATION_LOOPS             = 22.0;
    public static final double DEFAULT_GAS_HARVEST_DURATION_LOOPS        = 31.7;
    public static final double DEFAULT_GAS_APPROACH_DOCKING_LOOPS        = 11.5;
    public static final double DEFAULT_DEPOSIT_DURATION_LOOPS            = 12.0;
    public static final double DEFAULT_ACCELERATION                      = 3.5;
    public static final double DEFAULT_DECELERATION                      = 3.5;
    public static final double DEFAULT_PATCH_DOCKING_OVERHEAD            = 22.0;
    public static final float  DEFAULT_PATCH_DISTANCE                    = 7.0f;
    public static final double DEFAULT_INITIAL_WORKER_STAGGER            = 6.1;
    public static final double DEFAULT_COLLISION_SPEED_FACTOR            = 1.0;
    public static final double DEFAULT_APPROACH_DOCKING_LOOPS            = 24.5;
    public static final double DEFAULT_REROUTE_THRESHOLD_LOOPS           = 22.0;
    public static final float  DEFAULT_LOW_SATURATION_DODGE_SCALE        = 0.25f;
    public static final double DEFAULT_APPROACH_ANIMATION_LOOPS          = 20.0;
    public static final double DEFAULT_GAS_DEPOSIT_LOCK_LOOPS            = 8.0;
    public static final double DEFAULT_GAS_DEPOSIT_CONGESTION_PER_WORKER = 0.0;

    public WorkerPhysicsConfig(double miningDurationLoops, double gasHarvestDurationLoops,
                               double depositDurationLoops, double acceleration,
                               double deceleration, double patchDockingOverheadLoops) {
        this(miningDurationLoops, gasHarvestDurationLoops, DEFAULT_GAS_APPROACH_DOCKING_LOOPS,
             depositDurationLoops,
             acceleration, deceleration, patchDockingOverheadLoops, DEFAULT_PATCH_DISTANCE,
             DEFAULT_INITIAL_WORKER_STAGGER, DEFAULT_COLLISION_SPEED_FACTOR,
             DEFAULT_APPROACH_DOCKING_LOOPS, DEFAULT_REROUTE_THRESHOLD_LOOPS,
             DEFAULT_LOW_SATURATION_DODGE_SCALE,
             DEFAULT_APPROACH_ANIMATION_LOOPS,
             DEFAULT_GAS_DEPOSIT_LOCK_LOOPS,
             DEFAULT_GAS_DEPOSIT_CONGESTION_PER_WORKER);
    }

    public WorkerPhysicsConfig(double miningDurationLoops, double gasHarvestDurationLoops,
                               double depositDurationLoops, double acceleration,
                               double deceleration, double patchDockingOverheadLoops,
                               float patchDistance) {
        this(miningDurationLoops, gasHarvestDurationLoops, DEFAULT_GAS_APPROACH_DOCKING_LOOPS,
             depositDurationLoops,
             acceleration, deceleration, patchDockingOverheadLoops, patchDistance,
             DEFAULT_INITIAL_WORKER_STAGGER, DEFAULT_COLLISION_SPEED_FACTOR,
             DEFAULT_APPROACH_DOCKING_LOOPS, DEFAULT_REROUTE_THRESHOLD_LOOPS,
             DEFAULT_LOW_SATURATION_DODGE_SCALE,
             DEFAULT_APPROACH_ANIMATION_LOOPS,
             DEFAULT_GAS_DEPOSIT_LOCK_LOOPS,
             DEFAULT_GAS_DEPOSIT_CONGESTION_PER_WORKER);
    }

    public static WorkerPhysicsConfig defaults() {
        return new WorkerPhysicsConfig(
                DEFAULT_MINING_DURATION_LOOPS,
                DEFAULT_GAS_HARVEST_DURATION_LOOPS,
                DEFAULT_GAS_APPROACH_DOCKING_LOOPS,
                DEFAULT_DEPOSIT_DURATION_LOOPS,
                DEFAULT_ACCELERATION,
                DEFAULT_DECELERATION,
                DEFAULT_PATCH_DOCKING_OVERHEAD,
                DEFAULT_PATCH_DISTANCE,
                DEFAULT_INITIAL_WORKER_STAGGER,
                DEFAULT_COLLISION_SPEED_FACTOR,
                DEFAULT_APPROACH_DOCKING_LOOPS,
                DEFAULT_REROUTE_THRESHOLD_LOOPS,
                DEFAULT_LOW_SATURATION_DODGE_SCALE,
                DEFAULT_APPROACH_ANIMATION_LOOPS,
                DEFAULT_GAS_DEPOSIT_LOCK_LOOPS,
                DEFAULT_GAS_DEPOSIT_CONGESTION_PER_WORKER
        );
    }
}

package io.quarkmind.sc2.emulated;

public record WorkerPhysicsConfig(
        double miningDurationLoops,
        double gasHarvestDurationLoops,
        double depositDurationLoops,
        double acceleration,
        double deceleration,
        double patchDockingOverheadLoops,
        float  patchDistance
) {

    public static final double DEFAULT_MINING_DURATION_LOOPS      = 12.0;
    public static final double DEFAULT_GAS_HARVEST_DURATION_LOOPS = 26.0;
    public static final double DEFAULT_DEPOSIT_DURATION_LOOPS     = 0.0;
    public static final double DEFAULT_ACCELERATION               = 3.5;
    public static final double DEFAULT_DECELERATION               = 3.5;
    public static final double DEFAULT_PATCH_DOCKING_OVERHEAD     = 34.0;
    public static final float  DEFAULT_PATCH_DISTANCE             = 7.0f;

    public WorkerPhysicsConfig(double miningDurationLoops, double gasHarvestDurationLoops,
                               double depositDurationLoops, double acceleration,
                               double deceleration, double patchDockingOverheadLoops) {
        this(miningDurationLoops, gasHarvestDurationLoops, depositDurationLoops,
             acceleration, deceleration, patchDockingOverheadLoops, DEFAULT_PATCH_DISTANCE);
    }

    public static WorkerPhysicsConfig defaults() {
        return new WorkerPhysicsConfig(
                DEFAULT_MINING_DURATION_LOOPS,
                DEFAULT_GAS_HARVEST_DURATION_LOOPS,
                DEFAULT_DEPOSIT_DURATION_LOOPS,
                DEFAULT_ACCELERATION,
                DEFAULT_DECELERATION,
                DEFAULT_PATCH_DOCKING_OVERHEAD,
                DEFAULT_PATCH_DISTANCE
        );
    }
}

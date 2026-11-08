package io.quarkmind.sc2.emulated;

/**
 * Physics constants for worker mining/gas simulation.
 * Defaults match calibrated values. Override via application.properties:
 *
 *   starcraft.worker.mining-duration-loops=12.0
 *   starcraft.worker.gas-harvest-duration-loops=31.696
 *   starcraft.worker.acceleration=3.5
 *   starcraft.worker.deceleration=3.5
 *
 * Or command line: -Dstarcraft.worker.mining-duration-loops=15
 */
public record WorkerPhysicsConfig(
    double miningDurationLoops,
    double gasHarvestDurationLoops,
    double acceleration,
    double deceleration
) {

    public static final double DEFAULT_MINING_DURATION_LOOPS = 12.0;
    public static final double DEFAULT_GAS_HARVEST_DURATION_LOOPS = 31.696;
    public static final double DEFAULT_ACCELERATION = 3.5;
    public static final double DEFAULT_DECELERATION = 3.5;

    public static WorkerPhysicsConfig defaults() {
        return new WorkerPhysicsConfig(
            DEFAULT_MINING_DURATION_LOOPS,
            DEFAULT_GAS_HARVEST_DURATION_LOOPS,
            DEFAULT_ACCELERATION,
            DEFAULT_DECELERATION
        );
    }
}

package io.quarkmind.sc2.emulated;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
class WorkerPhysicsConfigProducer {

    @ConfigProperty(name = "starcraft.worker.mining-duration-loops",
                    defaultValue = "22.0")
    double miningDurationLoops;

    @ConfigProperty(name = "starcraft.worker.gas-harvest-duration-loops",
                    defaultValue = "31.7")
    double gasHarvestDurationLoops;

    @ConfigProperty(name = "starcraft.worker.gas-approach-docking-loops",
                    defaultValue = "11.5")
    double gasApproachDockingLoops;

    @ConfigProperty(name = "starcraft.worker.deposit-duration-loops",
                    defaultValue = "0")
    double depositDurationLoops;

    @ConfigProperty(name = "starcraft.worker.acceleration",
                    defaultValue = "4.9")
    double acceleration;

    @ConfigProperty(name = "starcraft.worker.deceleration",
                    defaultValue = "4.9")
    double deceleration;

    @ConfigProperty(name = "starcraft.worker.patch-docking-overhead-loops",
                    defaultValue = "22.0")
    double patchDockingOverheadLoops;

    @ConfigProperty(name = "starcraft.worker.patch-distance",
                    defaultValue = "7.0")
    double patchDistance;

    @ConfigProperty(name = "starcraft.worker.initial-stagger-loops",
                    defaultValue = "6.1")
    double initialWorkerStaggerLoops;

    @ConfigProperty(name = "starcraft.worker.collision-speed-factor",
                    defaultValue = "1.0")
    double collisionSpeedFactor;

    @ConfigProperty(name = "starcraft.worker.reroute-threshold-loops",
                    defaultValue = "30.0")
    double rerouteThresholdLoops;

    @ConfigProperty(name = "starcraft.worker.low-saturation-dodge-scale",
                    defaultValue = "0.10")
    double lowSaturationDodgeScale;

    @ConfigProperty(name = "starcraft.worker.approach-animation-loops",
                    defaultValue = "20.0")
    double approachAnimationLoops;

    @ConfigProperty(name = "starcraft.worker.gas-deposit-lock-loops",
                    defaultValue = "8.0")
    double gasDepositLockLoops;

    @ConfigProperty(name = "starcraft.worker.gas-deposit-congestion-per-worker",
                    defaultValue = "0.0")
    double gasDepositCongestionPerWorker;
    @ConfigProperty(name = "starcraft.worker.mineral-patch-obstacle-radius",
                    defaultValue = "0.5")
    double mineralPatchObstacleRadius;


    @Produces
    @jakarta.inject.Singleton
    WorkerPhysicsConfig produce() {
        WorkerPhysicsConfig config = new WorkerPhysicsConfig(
                miningDurationLoops, gasHarvestDurationLoops, gasApproachDockingLoops,
                depositDurationLoops,
                acceleration, deceleration, patchDockingOverheadLoops, (float) patchDistance,
                initialWorkerStaggerLoops, collisionSpeedFactor,
                rerouteThresholdLoops, (float) lowSaturationDodgeScale,
                approachAnimationLoops,
                gasDepositLockLoops, gasDepositCongestionPerWorker,
                (float) mineralPatchObstacleRadius);
        SimulatedWorker.configure(config);
        return config;
    }
}

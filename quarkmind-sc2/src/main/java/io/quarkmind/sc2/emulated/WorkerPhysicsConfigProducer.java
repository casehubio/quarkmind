package io.quarkmind.sc2.emulated;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
class WorkerPhysicsConfigProducer {

    @ConfigProperty(name = "starcraft.worker.mining-duration-loops",
                    defaultValue = "5.0")
    double miningDurationLoops;

    @ConfigProperty(name = "starcraft.worker.gas-harvest-duration-loops",
                    defaultValue = "26.0")
    double gasHarvestDurationLoops;

    @ConfigProperty(name = "starcraft.worker.deposit-duration-loops",
                    defaultValue = "3.0")
    double depositDurationLoops;

    @ConfigProperty(name = "starcraft.worker.acceleration",
                    defaultValue = "3.5")
    double acceleration;

    @ConfigProperty(name = "starcraft.worker.deceleration",
                    defaultValue = "3.5")
    double deceleration;

    @ConfigProperty(name = "starcraft.worker.patch-docking-overhead-loops",
                    defaultValue = "34.0")
    double patchDockingOverheadLoops;

    @ConfigProperty(name = "starcraft.worker.patch-distance",
                    defaultValue = "7.0")
    double patchDistance;

    @ConfigProperty(name = "starcraft.worker.initial-stagger-loops",
                    defaultValue = "6.0")
    double initialWorkerStaggerLoops;

    @ConfigProperty(name = "starcraft.worker.collision-speed-factor",
                    defaultValue = "0.952")
    double collisionSpeedFactor;

    @ConfigProperty(name = "starcraft.worker.approach-docking-loops",
                    defaultValue = "7.0")
    double approachDockingLoops;
    @ConfigProperty(name = "starcraft.worker.reroute-threshold-loops",
                    defaultValue = "22.0")
    double rerouteThresholdLoops;


    @Produces
    @jakarta.inject.Singleton
    WorkerPhysicsConfig produce() {
        WorkerPhysicsConfig config = new WorkerPhysicsConfig(
                miningDurationLoops, gasHarvestDurationLoops, depositDurationLoops,
                acceleration, deceleration, patchDockingOverheadLoops, (float) patchDistance,
                initialWorkerStaggerLoops, collisionSpeedFactor, approachDockingLoops,
                rerouteThresholdLoops);
        SimulatedWorker.configure(config);
        return config;
    }
}

package io.quarkmind.sc2.emulated;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
class WorkerPhysicsConfigProducer {

    @ConfigProperty(name = "starcraft.worker.mining-duration-loops",
        defaultValue = "12.0")
    double miningDurationLoops;

    @ConfigProperty(name = "starcraft.worker.gas-harvest-duration-loops",
        defaultValue = "22.0")
    double gasHarvestDurationLoops;

    @ConfigProperty(name = "starcraft.worker.acceleration",
        defaultValue = "3.5")
    double acceleration;

    @ConfigProperty(name = "starcraft.worker.deceleration",
        defaultValue = "3.5")
    double deceleration;

    @Produces
    @ApplicationScoped
    WorkerPhysicsConfig produce() {
        WorkerPhysicsConfig config = new WorkerPhysicsConfig(
            miningDurationLoops, gasHarvestDurationLoops,
            acceleration, deceleration);
        SimulatedWorker.configure(config);
        return config;
    }
}

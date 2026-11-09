package io.quarkmind.sc2.emulated;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
class EconomyConfigProducer {

    @ConfigProperty(name = "starcraft.economy.rate-interval-loops",
                    defaultValue = "160")
    int rateIntervalLoops;

    @Produces
    @ApplicationScoped
    EconomyConfig produce() {
        return new EconomyConfig(rateIntervalLoops);
    }
}

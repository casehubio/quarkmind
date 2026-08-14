package io.quarkmind.agent;

import io.quarkmind.domain.PhaseResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class PhaseResolverProducer {

    @Produces
    @ApplicationScoped
    PhaseResolver phaseResolver(
            @ConfigProperty(name = "quarkmind.phase-resolver.strategy",
                           defaultValue = "state-based") String strategy) {
        return "time-based".equals(strategy)
            ? new TimeBasedPhaseResolver()
            : new StateBasedPhaseResolver();
    }
}

package io.quarkmind.agent;

import io.casehub.api.spi.routing.RoutingPromptAssembler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import java.util.List;

/**
 * Provides CDI beans required by casehub-agency producers that QuarkMind
 * doesn't use directly. Without these, the Quarkus augmentation fails
 * with unsatisfied dependency errors at build time.
 */
@ApplicationScoped
public class QuarkMindAgencyFallbacks {

    @Produces @ApplicationScoped
    RoutingPromptAssembler routingPromptAssembler() {
        return new RoutingPromptAssembler(List.of());
    }
}

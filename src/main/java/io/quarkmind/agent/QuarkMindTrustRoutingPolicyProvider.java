package io.quarkmind.agent;

import io.casehub.api.spi.routing.TrustRoutingPolicy;
import io.casehub.api.spi.routing.TrustRoutingPolicyProvider;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Map;

/**
 * QuarkMind-specific trust routing policy provider.
 * Configures per-capability policies with quality floors for response latency,
 * recommendation quality, and game outcome.
 * <p>
 * Displaces the engine's default provider via {@code @Alternative @Priority(1)}.
 * <p>
 * Crisis advisors converge faster (minimumObservations=5);
 * strategic and economic advisors use minimumObservations=10.
 */
@ApplicationScoped
@Alternative
@Priority(1)
public class QuarkMindTrustRoutingPolicyProvider implements TrustRoutingPolicyProvider {

    private final int crisisMinObservations;
    private final int strategicMinObservations;
    private final int economicMinObservations;

    private final double responseLatencyFloor;
    private final double recommendationQualityFloor;
    private final double gameOutcomeFloor;

    public QuarkMindTrustRoutingPolicyProvider(
            @ConfigProperty(name = "quarkmind.advisory.trust.crisis.min-observations", defaultValue = "5")
            int crisisMinObservations,
            @ConfigProperty(name = "quarkmind.advisory.trust.strategic.min-observations", defaultValue = "10")
            int strategicMinObservations,
            @ConfigProperty(name = "quarkmind.advisory.trust.economic.min-observations", defaultValue = "10")
            int economicMinObservations,
            @ConfigProperty(name = "quarkmind.advisory.trust.quality-floors.response-latency", defaultValue = "0.3")
            double responseLatencyFloor,
            @ConfigProperty(name = "quarkmind.advisory.trust.quality-floors.recommendation-quality", defaultValue = "0.2")
            double recommendationQualityFloor,
            @ConfigProperty(name = "quarkmind.advisory.trust.quality-floors.game-outcome", defaultValue = "0.2")
            double gameOutcomeFloor) {
        this.crisisMinObservations = crisisMinObservations;
        this.strategicMinObservations = strategicMinObservations;
        this.economicMinObservations = economicMinObservations;
        this.responseLatencyFloor = responseLatencyFloor;
        this.recommendationQualityFloor = recommendationQualityFloor;
        this.gameOutcomeFloor = gameOutcomeFloor;
    }

    @Override
    public TrustRoutingPolicy forCapability(String capabilityName) {
        return switch (capabilityName) {
            case "tick-decision" -> buildPolicy(strategicMinObservations);
            case "advisory-crisis" -> buildPolicy(crisisMinObservations);
            case "advisory-strategic" -> buildPolicy(strategicMinObservations);
            case "advisory-economic" -> buildPolicy(economicMinObservations);
            default -> TrustRoutingPolicy.DEFAULT;
        };
    }

    private TrustRoutingPolicy buildPolicy(int minimumObservations) {
        Map<String, Double> qualityFloors = Map.of(
                "response-latency", responseLatencyFloor,
                "recommendation-quality", recommendationQualityFloor,
                "game-outcome", gameOutcomeFloor
        );

        return new TrustRoutingPolicy(
                0.7,                    // threshold
                minimumObservations,    // minimumObservations (varies per capability)
                0.1,                    // borderlineMargin
                0.6,                    // blendFactor
                qualityFloors,          // qualityFloors
                false,                  // bootstrapEscalationRequired
                null                    // fallbackBinding
        );
    }
}

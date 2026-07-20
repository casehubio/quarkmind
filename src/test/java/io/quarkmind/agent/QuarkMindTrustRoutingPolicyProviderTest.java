package io.quarkmind.agent;

import io.casehub.api.spi.routing.TrustRoutingPolicy;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuarkMindTrustRoutingPolicyProviderTest {

    @Test
    void tickDecisionReturnsStrategyPolicy() {
        var provider = new QuarkMindTrustRoutingPolicyProvider(
                5, 10, 10, 5,
                0.3, 0.2, 0.2
        );

        TrustRoutingPolicy policy = provider.forCapability("tick-decision");

        assertNotNull(policy);
        assertEquals(10, policy.minimumObservations(),
                "tick-decision (strategy) should use strategic minimumObservations");
    }

    @Test
    void advisoryCrisisReturnsCorrectPolicy() {
        var provider = new QuarkMindTrustRoutingPolicyProvider(
                5, 10, 10, 5,
                0.3, 0.2, 0.2
        );

        TrustRoutingPolicy policy = provider.forCapability("advisory-crisis");

        assertNotNull(policy);
        assertEquals(5, policy.minimumObservations(),
                "crisis advisor should converge fast with minimumObservations=5");
    }

    @Test
    void advisoryStrategicReturnsCorrectPolicy() {
        var provider = new QuarkMindTrustRoutingPolicyProvider(
                5, 10, 10, 5,
                0.3, 0.2, 0.2
        );

        TrustRoutingPolicy policy = provider.forCapability("advisory-strategic");

        assertNotNull(policy);
        assertEquals(10, policy.minimumObservations(),
                "strategic advisor should use minimumObservations=10");
    }

    @Test
    void advisoryEconomicReturnsCorrectPolicy() {
        var provider = new QuarkMindTrustRoutingPolicyProvider(
                5, 10, 10, 5,
                0.3, 0.2, 0.2
        );

        TrustRoutingPolicy policy = provider.forCapability("advisory-economic");

        assertNotNull(policy);
        assertEquals(10, policy.minimumObservations(),
                "economic advisor should use minimumObservations=10");
    }

    @Test
    void qualityFloorsMatchConfiguredValues() {
        var provider = new QuarkMindTrustRoutingPolicyProvider(
                5, 10, 10, 5,
                0.3, 0.2, 0.2
        );

        TrustRoutingPolicy policy = provider.forCapability("advisory-crisis");

        Map<String, Double> qualityFloors = policy.qualityFloors();
        assertEquals(0.3, qualityFloors.get("response-latency"),
                "response-latency floor should be 0.3");
        assertEquals(0.2, qualityFloors.get("recommendation-quality"),
                "recommendation-quality floor should be 0.2");
        assertEquals(0.2, qualityFloors.get("game-outcome"),
                "game-outcome floor should be 0.2");
    }

    @Test
    void allCapabilitiesUseSameQualityFloors() {
        var provider = new QuarkMindTrustRoutingPolicyProvider(
                5, 10, 10, 5,
                0.3, 0.2, 0.2
        );

        Map<String, Double> expectedFloors = Map.of(
                "response-latency", 0.3,
                "recommendation-quality", 0.2,
                "game-outcome", 0.2
        );

        assertEquals(expectedFloors, provider.forCapability("tick-decision").qualityFloors());
        assertEquals(expectedFloors, provider.forCapability("advisory-crisis").qualityFloors());
        assertEquals(expectedFloors, provider.forCapability("advisory-strategic").qualityFloors());
        assertEquals(expectedFloors, provider.forCapability("advisory-economic").qualityFloors());
    }

    @Test
    void unknownCapabilityReturnsDefault() {
        var provider = new QuarkMindTrustRoutingPolicyProvider(
                5, 10, 10, 5,
                0.3, 0.2, 0.2
        );

        TrustRoutingPolicy policy = provider.forCapability("unknown-capability");

        assertNotNull(policy);
        assertEquals(TrustRoutingPolicy.DEFAULT, policy,
                "unknown capabilities should return TrustRoutingPolicy.DEFAULT");
    }

    @Test
    void commentaryReactive_hasCorrectMinObservations() {
        var provider = new QuarkMindTrustRoutingPolicyProvider(
                5, 10, 10, 5,
                0.3, 0.2, 0.2
        );

        TrustRoutingPolicy policy = provider.forCapability("commentary-reactive");

        assertNotNull(policy);
        assertEquals(5, policy.minimumObservations(),
                "reactive commentary should converge fast with minimumObservations=5");
    }

    @Test
    void commentaryNarrative_hasCorrectMinObservations() {
        var provider = new QuarkMindTrustRoutingPolicyProvider(
                5, 10, 10, 5,
                0.3, 0.2, 0.2
        );

        TrustRoutingPolicy policy = provider.forCapability("commentary-narrative");

        assertNotNull(policy);
        assertEquals(5, policy.minimumObservations(),
                "narrative commentary should converge fast with minimumObservations=5");
    }

    @Test
    void commentaryReactive_hasResponseLatencyFloorOnly() {
        var provider = new QuarkMindTrustRoutingPolicyProvider(
                5, 10, 10, 5,
                0.3, 0.2, 0.2
        );

        TrustRoutingPolicy policy = provider.forCapability("commentary-reactive");

        Map<String, Double> qualityFloors = policy.qualityFloors();
        assertEquals(1, qualityFloors.size(),
                "Reactive commentary should only have response-latency quality floor");
        assertEquals(0.4, qualityFloors.get("response-latency"),
                "reactive commentary response-latency floor should be 0.4");
    }

    @Test
    void commentaryNarrative_hasResponseLatencyFloorOnly() {
        var provider = new QuarkMindTrustRoutingPolicyProvider(
                5, 10, 10, 5,
                0.3, 0.2, 0.2
        );

        TrustRoutingPolicy policy = provider.forCapability("commentary-narrative");

        Map<String, Double> qualityFloors = policy.qualityFloors();
        assertEquals(1, qualityFloors.size(),
                "Narrative commentary should only have response-latency quality floor");
        assertEquals(0.3, qualityFloors.get("response-latency"),
                "narrative commentary response-latency floor should be 0.3");
    }

    @Test
    void advisory_hasAllThreeQualityFloors() {
        var provider = new QuarkMindTrustRoutingPolicyProvider(
                5, 10, 10, 5,
                0.3, 0.2, 0.2
        );

        TrustRoutingPolicy policy = provider.forCapability("advisory-crisis");

        Map<String, Double> qualityFloors = policy.qualityFloors();
        assertEquals(3, qualityFloors.size(),
                "Advisory should have all three quality floors");
        assertTrue(qualityFloors.containsKey("response-latency"));
        assertTrue(qualityFloors.containsKey("recommendation-quality"));
        assertTrue(qualityFloors.containsKey("game-outcome"));
    }

    @Test
    void coaching_returnsCoachingPolicy() {
        var provider = new QuarkMindTrustRoutingPolicyProvider(
                5, 10, 10, 5,
                0.3, 0.2, 0.2
        );

        TrustRoutingPolicy policy = provider.forCapability("coaching");

        assertNotNull(policy);
        assertNotEquals(TrustRoutingPolicy.DEFAULT, policy);
        assertTrue(policy.qualityFloors().containsKey("coaching-effectiveness"));
        assertTrue(policy.qualityFloors().containsKey("response-latency"));
        assertFalse(policy.qualityFloors().containsKey("game-outcome"));
        assertEquals(3, policy.minimumObservations());
    }

}

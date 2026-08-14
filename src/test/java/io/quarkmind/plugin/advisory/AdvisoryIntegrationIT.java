package io.quarkmind.plugin.advisory;

import io.casehub.api.context.CaseContext;
import io.casehub.api.spi.routing.TrustRoutingPolicy;
import io.casehub.eidos.api.AgentDescriptor;
import io.quarkmind.agent.AdvisoryInvocationCounter;
import io.quarkmind.agent.AdvisoryTriggerBuilder;
import io.quarkmind.agent.DeferredAdvisoryEvaluator;
import io.quarkmind.agent.MapCaseContext;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.QuarkMindTrustRoutingPolicyProvider;
import io.quarkmind.plugin.summarisation.GameMoment;
import io.quarkmind.plugin.summarisation.GameMomentType;
import io.quarkmind.sc2.GameStarted;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying the full advisory pipeline CDI wiring.
 *
 * <p>Because no ChatModel bean exists in the test profile, advisory Workers are not created.
 * This test verifies all supporting infrastructure is correctly wired: trigger building,
 * channel broker, invocation counter, deferred evaluator, trust routing policy, and
 * advisor registrar.
 *
 * <p>Pattern follows {@code SummarisationPipelineIT} and {@code MomentBrokerIT}.
 *
 * <p>Refs #180
 */
@QuarkusTest
class AdvisoryIntegrationIT {

    @Inject AdvisoryChannelBroker channelBroker;
    @Inject AdvisoryChannelBackend channelBackend;
    @Inject AdvisoryInvocationCounter invocationCounter;
    @Inject DeferredAdvisoryEvaluator deferredEvaluator;
    @Inject QuarkMindTrustRoutingPolicyProvider trustRoutingProvider;
    @Inject
            QuarkMindAgentRegistrar             advisorRegistrar;
    @Inject Event<GameStarted>                  gameStartedEvent;

    @BeforeEach
    void setUp() {
        // Reset game state for each test
        gameStartedEvent.fire(new GameStarted());
    }

    // ---- AdvisoryChannelBroker wiring ----

    @Test
    void channelBroker_channelCreatedOnStartup() {
        assertThat(channelBroker.channelId())
            .as("Advisory channel should be created during @PostConstruct")
            .isNotNull();
    }

    // ---- AdvisoryChannelBackend wiring ----

    @Test
    void channelBackend_injectedAsBean() {
        assertThat(channelBackend)
            .as("AdvisoryChannelBackend should be a CDI bean")
            .isNotNull();
        assertThat(channelBackend.backendId())
            .isEqualTo("quarkmind-advisory-observer");
    }

    // ---- AdvisoryTriggerBuilder (static utility) ----

    @Test
    void triggerBuilder_mapsCrisisMoments() {
        CaseContext ctx = new MapCaseContext(Map.of(
            QuarkMindCaseFile.MOMENTS_LATEST,
            List.of(new GameMoment(GameMomentType.NEXUS_UNDER_ATTACK, 500, Map.of()))
        ));

        Map<String, Object> triggers = AdvisoryTriggerBuilder.buildTriggers(ctx, 500);

        assertThat(triggers).containsKey("game.advisory.trigger.crisis");
        assertThat(triggers).doesNotContainKey("game.advisory.trigger.strategic");
        assertThat(triggers).doesNotContainKey("game.advisory.trigger.economic");
    }

    @Test
    void triggerBuilder_mapsStrategicMoments() {
        CaseContext ctx = new MapCaseContext(Map.of(
            QuarkMindCaseFile.MOMENTS_LATEST,
            List.of(new GameMoment(GameMomentType.TECH_TRANSITION_DETECTED, 800, Map.of()))
        ));

        Map<String, Object> triggers = AdvisoryTriggerBuilder.buildTriggers(ctx, 800);

        assertThat(triggers).containsKey("game.advisory.trigger.strategic");
        assertThat(triggers).doesNotContainKey("game.advisory.trigger.crisis");
    }

    @Test
    void triggerBuilder_mapsEconomicMoments() {
        CaseContext ctx = new MapCaseContext(Map.of(
            QuarkMindCaseFile.MOMENTS_LATEST,
            List.of(new GameMoment(GameMomentType.ECONOMIC_CRISIS, 600, Map.of()))
        ));

        Map<String, Object> triggers = AdvisoryTriggerBuilder.buildTriggers(ctx, 600);

        assertThat(triggers).containsKey("game.advisory.trigger.economic");
        assertThat(triggers).doesNotContainKey("game.advisory.trigger.crisis");
    }

    @Test
    void triggerBuilder_emptyForUnmappedMoments() {
        CaseContext ctx = new MapCaseContext(Map.of(
            QuarkMindCaseFile.MOMENTS_LATEST,
            List.of(new GameMoment(GameMomentType.FIRST_CONTACT, 300, Map.of()))
        ));

        Map<String, Object> triggers = AdvisoryTriggerBuilder.buildTriggers(ctx, 300);

        assertThat(triggers).isEmpty();
    }

    @Test
    void triggerBuilder_emptyForNoMoments() {
        CaseContext ctx = new MapCaseContext(Map.of());

        Map<String, Object> triggers = AdvisoryTriggerBuilder.buildTriggers(ctx, 100);

        assertThat(triggers).isEmpty();
    }

    // ---- AdvisoryInvocationCounter lifecycle ----

    @Test
    void invocationCounter_tracksAcrossGameLifecycle() {
        // Record some advisory invocations
        invocationCounter.record("claude:crisis-aggressive@v1", 0L);
        invocationCounter.record("claude:strategic-bold@v1", 0L);

        Set<String> snapshot = invocationCounter.snapshot();
        assertThat(snapshot)
            .containsExactlyInAnyOrder("claude:crisis-aggressive@v1", "claude:strategic-bold@v1");

        // Fire GameStarted — should reset
        gameStartedEvent.fire(new GameStarted());

        assertThat(invocationCounter.snapshot())
            .as("Invocation counter should be cleared on GameStarted")
            .isEmpty();
    }

    @Test
    void invocationCounter_deduplicatesAdvisors() {
        invocationCounter.record("claude:crisis-aggressive@v1", 0L);
        invocationCounter.record("claude:crisis-aggressive@v1", 0L);
        invocationCounter.record("claude:crisis-aggressive@v1", 0L);

        assertThat(invocationCounter.snapshot())
            .as("Set-based: duplicate advisorIds are deduplicated")
            .hasSize(1);
    }

    // ---- DeferredAdvisoryEvaluator lifecycle ----

    @Test
    void deferredEvaluator_injectedAsBean() {
        assertThat(deferredEvaluator)
            .as("DeferredAdvisoryEvaluator should be a CDI bean")
            .isNotNull();
    }

    @Test
    void deferredEvaluator_evaluateDoesNotThrowWithEmptyState() {
        // Evaluate with no pending evaluations — should be a no-op
        CaseContext ctx = new MapCaseContext(Map.of(
            QuarkMindCaseFile.MINERALS, 400,
            QuarkMindCaseFile.SUPPLY_USED, 30,
            QuarkMindCaseFile.ARMY, List.of()
        ));

        // Should not throw even with no pending evaluations
        deferredEvaluator.evaluate(ctx, 1000L);
    }

    // ---- QuarkMindTrustRoutingPolicyProvider ----

    @Test
    void trustRoutingPolicy_crisisHasLowerMinObservations() {
        TrustRoutingPolicy crisis = trustRoutingProvider.forCapability("advisory-crisis");
        TrustRoutingPolicy strategic = trustRoutingProvider.forCapability("advisory-strategic");

        assertThat(crisis.minimumObservations())
            .as("Crisis advisors converge faster (5 vs 10)")
            .isLessThan(strategic.minimumObservations());
        assertThat(crisis.minimumObservations()).isEqualTo(5);
        assertThat(strategic.minimumObservations()).isEqualTo(10);
    }

    @Test
    void trustRoutingPolicy_economicMatchesStrategic() {
        TrustRoutingPolicy strategic = trustRoutingProvider.forCapability("advisory-strategic");
        TrustRoutingPolicy economic = trustRoutingProvider.forCapability("advisory-economic");

        assertThat(economic.minimumObservations())
            .isEqualTo(strategic.minimumObservations());
    }

    @Test
    void trustRoutingPolicy_unknownCapabilityReturnsDefault() {
        TrustRoutingPolicy unknown = trustRoutingProvider.forCapability("unknown-capability");

        assertThat(unknown).isSameAs(TrustRoutingPolicy.DEFAULT);
    }

    @Test
    void trustRoutingPolicy_qualityFloorsPresent() {
        TrustRoutingPolicy crisis = trustRoutingProvider.forCapability("advisory-crisis");

        assertThat(crisis.qualityFloors())
            .as("Quality floors should include response-latency, recommendation-quality, game-outcome")
            .containsKeys("response-latency", "recommendation-quality", "game-outcome");
    }

    // ---- QuarkMindAgentRegistrar ----

    @Test
    void agentRegistrar_produces12Descriptors() {
        List<AgentDescriptor> descriptors = advisorRegistrar.descriptors();

        assertThat(descriptors)
                .as("6 advisory + 4 commentary + 2 coaching = 12 descriptors")
                .hasSize(12);
    }

    @Test
    void agentRegistrar_produces6AdvisoryDescriptors() {
        List<AgentDescriptor> descriptors = advisorRegistrar.descriptors();

        long advisoryCount = descriptors.stream()
            .filter(d -> d.capabilities().stream()
                .anyMatch(c -> c.name().startsWith("advisory-")))
            .count();

        assertThat(advisoryCount)
            .as("3 roles x 2 disposition variants = 6 advisory descriptors")
            .isEqualTo(6);
    }

    @Test
    void agentRegistrar_produces4CommentaryDescriptors() {
        List<AgentDescriptor> descriptors = advisorRegistrar.descriptors();

        long commentaryCount = descriptors.stream()
            .filter(d -> d.capabilities().stream()
                .anyMatch(c -> c.name().startsWith("commentary-")))
            .count();

        assertThat(commentaryCount)
            .as("4 commentary descriptors (2 reactive + 2 narrative)")
            .isEqualTo(4);
    }

    @Test
    void advisorRegistrar_coversAllAdvisoryCapabilities() {
        List<AgentDescriptor> descriptors = advisorRegistrar.descriptors();

        // Extract all capability names from all descriptors
        List<String> allCapabilities = descriptors.stream()
            .flatMap(d -> d.capabilities().stream())
            .map(c -> c.name())
            .toList();

        assertThat(allCapabilities)
            .as("All three advisory capabilities should be covered")
            .contains("advisory-crisis", "advisory-strategic", "advisory-economic");
    }

    @Test
    void advisorRegistrar_agentIdsFollowConvention() {
        List<AgentDescriptor> descriptors = advisorRegistrar.descriptors();

        List<String> advisoryIds = descriptors.stream()
                                              .filter(d -> d.capabilities().stream()
                                                            .anyMatch(c -> c.name().startsWith("advisory-")))
                                              .map(AgentDescriptor::agentId)
                                              .toList();

        assertThat(advisoryIds)
                .as("Advisory agent IDs follow {model-family}:{persona}@{major} format")
                .containsExactlyInAnyOrder(
                        "claude:crisis-aggressive@v1",
                        "claude:crisis-conservative@v1",
                        "claude:strategic-bold@v1",
                        "claude:strategic-measured@v1",
                        "claude:economic-expansion@v1",
                        "claude:economic-defensive@v1"
                                          );
    }

    @Test
    void advisorRegistrar_eachDescriptorHasDisposition() {
        List<AgentDescriptor> descriptors = advisorRegistrar.descriptors();

        for (AgentDescriptor descriptor : descriptors) {
            assertThat(descriptor.disposition())
                .as("Descriptor %s should have a disposition", descriptor.agentId())
                .isNotNull();
            assertThat(descriptor.disposition().riskAppetite())
                .as("Descriptor %s should have riskAppetite", descriptor.agentId())
                .isNotNull();
        }
    }
}

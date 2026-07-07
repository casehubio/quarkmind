package io.quarkmind.plugin.commentary;

import io.casehub.api.context.CaseContext;
import io.casehub.api.spi.routing.TrustRoutingPolicy;
import io.quarkmind.agent.MapCaseContext;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.QuarkMindTrustRoutingPolicyProvider;
import io.quarkmind.plugin.advisory.QuarkMindAgentRegistrar;
import io.quarkmind.plugin.summarisation.GameMoment;
import io.quarkmind.plugin.summarisation.GameMomentType;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying the full commentary pipeline CDI wiring.
 *
 * <p>Because no ChatModel bean exists in the test profile, commentary Workers are not created.
 * This test verifies all supporting infrastructure is correctly wired: channel broker,
 * channel backend, trigger builder, accumulator, trust routing policy, and agent registrar.
 *
 * <p>Pattern follows {@code AdvisoryIntegrationIT}.
 *
 * <p>Refs #181
 */
@QuarkusTest
class CommentaryIntegrationIT {

    @Inject CommentaryChannelBroker channelBroker;
    @Inject CommentaryChannelBackend channelBackend;
    @Inject CommentaryTriggerBuilder commentaryTriggerBuilder;
    @Inject CommentaryAccumulator commentaryAccumulator;
    @Inject QuarkMindTrustRoutingPolicyProvider trustRoutingProvider;
    @Inject QuarkMindAgentRegistrar agentRegistrar;

    // ---- CommentaryChannelBroker wiring ----

    @Test
    void channelBroker_channelCreatedOnStartup() {
        assertThat(channelBroker.channelId())
            .as("Commentary channel should be created during @PostConstruct")
            .isNotNull();
    }

    // ---- CommentaryChannelBackend wiring ----

    @Test
    void channelBackend_injectedAsBean() {
        assertThat(channelBackend)
            .as("CommentaryChannelBackend should be a CDI bean")
            .isNotNull();
        assertThat(channelBackend.backendId())
            .isEqualTo("quarkmind-commentary-observer");
    }

    // ---- QuarkMindAgentRegistrar ----

    @Test
    void agentRegistrar_produces10Descriptors() {
        assertThat(agentRegistrar.descriptors())
            .as("6 advisory + 4 commentary = 10 descriptors")
            .hasSize(10);
    }

    // ---- QuarkMindTrustRoutingPolicyProvider ----

    @Test
    void trustRoutingPolicy_reactiveCommentaryHasValidPolicy() {
        TrustRoutingPolicy reactive = trustRoutingProvider.forCapability("commentary-reactive");

        assertThat(reactive).isNotSameAs(TrustRoutingPolicy.DEFAULT);
        assertThat(reactive.minimumObservations())
            .as("Reactive commentary converges fast (5 observations)")
            .isEqualTo(5);
        assertThat(reactive.qualityFloors())
            .as("Reactive commentary only has response-latency floor")
            .containsKey("response-latency");
    }

    @Test
    void trustRoutingPolicy_narrativeCommentaryHasValidPolicy() {
        TrustRoutingPolicy narrative = trustRoutingProvider.forCapability("commentary-narrative");

        assertThat(narrative).isNotSameAs(TrustRoutingPolicy.DEFAULT);
        assertThat(narrative.minimumObservations())
            .as("Narrative commentary converges fast (5 observations)")
            .isEqualTo(5);
        assertThat(narrative.qualityFloors())
            .as("Narrative commentary only has response-latency floor")
            .containsKey("response-latency");
    }

    // ---- CommentaryTriggerBuilder (CDI bean) ----

    @Test
    void triggerBuilder_emptyWhenNoMoments() {
        CaseContext ctx = new MapCaseContext(Map.of());

        Map<String, Object> triggers = commentaryTriggerBuilder.build(ctx, 100);

        assertThat(triggers)
            .as("No moments → no triggers")
            .isEmpty();
    }

    // ---- CommentaryAccumulator (CDI bean) ----

    @Test
    void accumulator_emptyOnFirstTick() {
        // First tick with no accumulated moments → no narrative trigger
        Map<String, Object> triggers = commentaryAccumulator.tick(100);

        assertThat(triggers)
            .as("First tick → nothing accumulated yet")
            .isEmpty();
    }
}

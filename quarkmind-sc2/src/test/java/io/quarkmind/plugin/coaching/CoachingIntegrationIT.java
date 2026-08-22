package io.quarkmind.plugin.coaching;

import io.casehub.api.context.CaseContext;
import io.casehub.api.spi.routing.TrustRoutingPolicy;
import io.casehub.eidos.api.AgentDescriptor;
import io.quarkmind.agency.context.MapCaseContext;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.QuarkMindTrustRoutingPolicyProvider;
import io.quarkmind.domain.*;
import io.quarkmind.plugin.advisory.QuarkMindAgentRegistrar;
import io.quarkmind.plugin.summarisation.GameMoment;
import io.quarkmind.plugin.summarisation.GameMomentType;
import io.quarkmind.sc2.GameStarted;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class CoachingIntegrationIT {

    @Inject CoachingChannelBroker channelBroker;
    @Inject CoachingTriggerBuilder triggerBuilder;
    @Inject CoachingComplianceEvaluator complianceEvaluator;
    @Inject CoachingEffectivenessTrustRecorder trustRecorder;
    @Inject QuarkMindTrustRoutingPolicyProvider trustRoutingProvider;
    @Inject QuarkMindAgentRegistrar agentRegistrar;
    @Inject Event<GameStarted> gameStartedEvent;

    @BeforeEach
    void setUp() {
        gameStartedEvent.fire(new GameStarted());
    }

    @Test
    void channelBroker_channelCreatedOnStartup() {
        assertThat(channelBroker.channelId())
            .as("Coaching channel should be created during @PostConstruct")
            .isNotNull();
    }

    @Test
    void channelBroker_commitmentsSharedWithEvaluator() {
        var advice = new CoachingAdvice("build stalkers", CoachingDomain.MILITARY,
            new CountDelta(UnitType.STALKER, null, 3, 0), 450);
        var event = new CoachingCompleted(
            "claude:coach-directive@v1", "coaching", 100,
            advice, CoachingUrgencyTier.STRATEGIC, 500, null);

        channelBroker.onCoachingCompleted(event);

        assertThat(channelBroker.commitments())
            .as("Commitment stored after CoachingCompleted event")
            .containsKey(CoachingDomain.MILITARY);
    }

    @Test
    void triggerBuilder_mapsCrisisMoments() {
        CaseContext ctx = new MapCaseContext(Map.of(
            QuarkMindCaseFile.MOMENTS_LATEST,
            List.of(new GameMoment(GameMomentType.NEXUS_UNDER_ATTACK, 500, Map.of()))));

        Map<String, Object> triggers = triggerBuilder.build(ctx, 500);

        assertThat(triggers).containsKey(QuarkMindCaseFile.COACHING_TRIGGER);
        @SuppressWarnings("unchecked")
        var trigger = (Map<String, Object>) triggers.get(QuarkMindCaseFile.COACHING_TRIGGER);
        assertThat(trigger.get("urgencyTier")).isEqualTo("CRISIS");
    }

    @Test
    void triggerBuilder_resetsOnGameStarted() {
        CaseContext ctx = new MapCaseContext(Map.of(
            QuarkMindCaseFile.MOMENTS_LATEST,
            List.of(new GameMoment(GameMomentType.SUPPLY_BLOCK, 100, Map.of()))));

        triggerBuilder.build(ctx, 100);
        gameStartedEvent.fire(new GameStarted());
        Map<String, Object> triggers = triggerBuilder.build(ctx, 101);

        assertThat(triggers).isNotEmpty();
    }

    @Test
    void complianceEvaluator_injectedAndFunctional() {
        assertThat(complianceEvaluator).isNotNull();

        var state = new GameState(400, 200, 62, 44, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 350, null, PlayerEconomyStats.EMPTY, PlayerEconomyStats.EMPTY, Set.of(), Set.of());

        complianceEvaluator.evaluate(state, 350);
    }

    @Test
    void complianceEvaluator_endToEnd_endorsedOnCompliance() {
        var advice = new CoachingAdvice("build stalkers", CoachingDomain.MILITARY,
            new CountDelta(UnitType.STALKER, null, 3, 0), 200);

        channelBroker.commitments().put(CoachingDomain.MILITARY,
            new OpenCommitment("corr-test", "worker-1", advice, 100, null));

        assertThat(channelBroker.commitments()).containsKey(CoachingDomain.MILITARY);

        List<Unit> units = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            units.add(new Unit("u" + i, UnitType.STALKER, new Point2d(0f, 0f),
                100, 100, 50, 50, 0, 0));
        }
        var state = new GameState(400, 200, 62, 44, units, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 350, null, PlayerEconomyStats.EMPTY, PlayerEconomyStats.EMPTY, Set.of(), Set.of());

        var manualEvaluator = new CoachingComplianceEvaluator(
            channelBroker.commitments(), trustRecorder, new LocationResolver());
        manualEvaluator.evaluate(state, 350);

        assertThat(channelBroker.commitments())
            .as("Commitment fulfilled — should be removed")
            .doesNotContainKey(CoachingDomain.MILITARY);
    }

    @Test
    void complianceEvaluator_supersession_replacesCommitment() {
        var advice1 = new CoachingAdvice("build stalkers", CoachingDomain.MILITARY,
            new CountDelta(UnitType.STALKER, null, 3, 0), 450);
        channelBroker.onCoachingCompleted(new CoachingCompleted(
            "w1", "coaching", 100, advice1, CoachingUrgencyTier.STRATEGIC, 500, null));

        String firstCorrelationId = channelBroker.commitments().get(CoachingDomain.MILITARY).correlationId();

        var advice2 = new CoachingAdvice("build zealots", CoachingDomain.MILITARY,
            new CountDelta(UnitType.ZEALOT, null, 4, 0), 450);
        channelBroker.onCoachingCompleted(new CoachingCompleted(
            "w1", "coaching", 200, advice2, CoachingUrgencyTier.STRATEGIC, 500, null));

        assertThat(channelBroker.commitments()).hasSize(1);
        assertThat(channelBroker.commitments().get(CoachingDomain.MILITARY).correlationId())
            .isNotEqualTo(firstCorrelationId);
        assertThat(channelBroker.commitments().get(CoachingDomain.MILITARY).advice().advice())
            .isEqualTo("build zealots");
    }

    @Test
    void complianceEvaluator_crossDomain_independent() {
        var militaryAdvice = new CoachingAdvice("build stalkers", CoachingDomain.MILITARY,
            new CountDelta(UnitType.STALKER, null, 3, 0), 450);
        channelBroker.onCoachingCompleted(new CoachingCompleted(
            "w1", "coaching", 100, militaryAdvice, CoachingUrgencyTier.STRATEGIC, 500, null));

        var expandAdvice = new CoachingAdvice("expand", CoachingDomain.EXPAND,
            null, 450);
        channelBroker.onCoachingCompleted(new CoachingCompleted(
            "w1", "coaching", 100, expandAdvice, CoachingUrgencyTier.ECONOMIC, 500, null));

        assertThat(channelBroker.commitments()).hasSize(2);
        assertThat(channelBroker.commitments()).containsKeys(CoachingDomain.MILITARY, CoachingDomain.EXPAND);
    }

    @Test
    void trustRouting_coachingPolicyExists() {
        TrustRoutingPolicy policy = trustRoutingProvider.forCapability("coaching");

        assertThat(policy).isNotSameAs(TrustRoutingPolicy.DEFAULT);
        assertThat(policy.qualityFloors()).containsKey("coaching-effectiveness");
        assertThat(policy.qualityFloors()).containsKey("response-latency");
        assertThat(policy.minimumObservations()).isEqualTo(3);
    }

    @Test
    void agentRegistrar_includesCoachingDescriptors() {
        List<AgentDescriptor> descriptors = agentRegistrar.descriptors();

        long coachingCount = descriptors.stream()
            .filter(d -> d.capabilities().stream().anyMatch(c -> c.name().equals("coaching")))
            .count();

        assertThat(coachingCount).isEqualTo(2);
    }

    @Test
    void agentRegistrar_coachDirectiveAndSocratic() {
        List<AgentDescriptor> descriptors = agentRegistrar.descriptors();

        List<String> coachIds = descriptors.stream()
            .filter(d -> d.capabilities().stream().anyMatch(c -> c.name().equals("coaching")))
            .map(AgentDescriptor::agentId)
            .toList();

        assertThat(coachIds).containsExactlyInAnyOrder(
            "claude:coach-directive@v1",
            "claude:coach-socratic@v1");
    }
}

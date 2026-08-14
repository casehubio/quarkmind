package io.quarkmind.plugin.coaching;

import io.casehub.eidos.api.AgentCapability;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentDisposition;
import io.casehub.eidos.vocab.ConscientiousnessTerm;
import io.casehub.platform.api.identity.TenancyConstants;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class CoachingSessionSelectorTest {

    private AgentDescriptor directiveAgent() {
        return AgentDescriptor.builder()
            .agentId("claude:coach-directive@v1")
            .name("Coach Directive")
            .provider("anthropic").modelFamily("claude").modelVersion("sonnet-4")
            .slot("coach")
            .slotVocabulary(ConscientiousnessTerm.URI)
            .disposition(AgentDisposition.builder()
                .riskAppetite(ConscientiousnessTerm.BOLD.value())
                .socialOrient(ConscientiousnessTerm.COLLABORATIVE.value())
                .ruleFollowing(ConscientiousnessTerm.FLEXIBLE.value())
                .autonomy(ConscientiousnessTerm.SEMI_AUTONOMOUS.value())
                .conflictMode("collaborate")
                .delegation(false)
                .build())
            .capabilities(List.of(
                AgentCapability.builder()
                    .name("coaching")
                    .latencyHintP50Ms(2000L)
                    .qualityHint(0.7)
                    .tags(List.of("starcraft.coaching"))
                    .build()))
            .tenancyId(TenancyConstants.DEFAULT_TENANT_ID)
            .build();
    }

    private AgentDescriptor socraticAgent() {
        return AgentDescriptor.builder()
            .agentId("claude:coach-socratic@v1")
            .name("Coach Socratic")
            .provider("anthropic").modelFamily("claude").modelVersion("sonnet-4")
            .slot("coach")
            .slotVocabulary(ConscientiousnessTerm.URI)
            .disposition(AgentDisposition.builder()
                .riskAppetite(ConscientiousnessTerm.CONSERVATIVE.value())
                .socialOrient(ConscientiousnessTerm.COLLABORATIVE.value())
                .ruleFollowing(ConscientiousnessTerm.STRICT.value())
                .autonomy(ConscientiousnessTerm.SEMI_AUTONOMOUS.value())
                .conflictMode("collaborate")
                .delegation(false)
                .build())
            .capabilities(List.of(
                AgentCapability.builder()
                    .name("coaching")
                    .latencyHintP50Ms(2000L)
                    .qualityHint(0.7)
                    .tags(List.of("starcraft.coaching"))
                    .build()))
            .tenancyId(TenancyConstants.DEFAULT_TENANT_ID)
            .build();
    }

    @Test
    void firstSelect_cachesResult() {
        var agents = List.of(directiveAgent(), socraticAgent());
        var selector = new CoachingSessionSelector(agents, "directive");
        var first = selector.select(CoachingUrgencyTier.STRATEGIC);
        var second = selector.select(CoachingUrgencyTier.STRATEGIC);
        assertThat(first).isSameAs(second);
    }

    @Test
    void defaultPersonality_directive_selectsDirectiveAgent() {
        var agents = List.of(directiveAgent(), socraticAgent());
        var selector = new CoachingSessionSelector(agents, "directive");
        var selected = selector.select(CoachingUrgencyTier.STRATEGIC);
        assertThat(selected.agentId()).isEqualTo("claude:coach-directive@v1");
    }

    @Test
    void defaultPersonality_socratic_selectsSocraticAgent() {
        var agents = List.of(directiveAgent(), socraticAgent());
        var selector = new CoachingSessionSelector(agents, "socratic");
        var selected = selector.select(CoachingUrgencyTier.STRATEGIC);
        assertThat(selected.agentId()).isEqualTo("claude:coach-socratic@v1");
    }

    @Test
    void onGameStarted_clearsCache() {
        var agents = List.of(directiveAgent(), socraticAgent());
        var selector = new CoachingSessionSelector(agents, "directive");
        var first = selector.select(CoachingUrgencyTier.STRATEGIC);
        selector.onGameStarted(null);
        var second = selector.select(CoachingUrgencyTier.STRATEGIC);
        assertThat(second.agentId()).isEqualTo(first.agentId());
    }

    @Test
    void fallsBackToFirstAgent_whenNoIdMatch() {
        var agents = List.of(directiveAgent(), socraticAgent());
        var selector = new CoachingSessionSelector(agents, "unknown");
        var selected = selector.select(CoachingUrgencyTier.STRATEGIC);
        assertThat(selected.agentId()).isEqualTo("claude:coach-directive@v1");
    }
}

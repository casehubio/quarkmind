package io.quarkmind.plugin.coaching;

import io.casehub.eidos.api.AgentCapability;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentDisposition;
import io.casehub.eidos.vocab.ConscientiousnessTerm;
import io.casehub.platform.api.identity.TenancyConstants;
import io.quarkmind.agent.QuarkMindCaseFile;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class CoachingWorkerFactoryTest {

    private AgentDescriptor directiveDescriptor() {
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
                .conflictMode("collaborate").delegation(false).build())
            .capabilities(List.of(
                AgentCapability.builder().name("coaching")
                    .latencyHintP50Ms(2000L).qualityHint(0.7)
                    .tags(List.of("starcraft.coaching")).build()))
            .tenancyId(TenancyConstants.DEFAULT_TENANT_ID)
            .build();
    }

    private AgentDescriptor socraticDescriptor() {
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
                .conflictMode("collaborate").delegation(false).build())
            .capabilities(List.of(
                AgentCapability.builder().name("coaching")
                    .latencyHintP50Ms(2000L).qualityHint(0.7)
                    .tags(List.of("starcraft.coaching")).build()))
            .tenancyId(TenancyConstants.DEFAULT_TENANT_ID)
            .build();
    }

    @Test
    void buildDirectiveSystemPrompt_containsCoachIdentity() {
        var prompt = CoachingWorkerFactory.buildSystemPrompt(directiveDescriptor(), false);
        assertThat(prompt).contains("StarCraft II coach");
        assertThat(prompt).contains("direct, actionable instructions");
    }

    @Test
    void buildSocraticSystemPrompt_containsGuidingQuestions() {
        var prompt = CoachingWorkerFactory.buildSystemPrompt(socraticDescriptor(), false);
        assertThat(prompt).contains("guiding questions");
    }

    @Test
    void buildSystemPrompt_crisisOverride_alwaysDirective() {
        var prompt = CoachingWorkerFactory.buildSystemPrompt(socraticDescriptor(), true);
        assertThat(prompt).contains("direct, actionable instructions");
        assertThat(prompt).doesNotContain("guiding questions");
    }

    @Test
    void buildSystemPrompt_includesStructuredOutputInstructions() {
        var prompt = CoachingWorkerFactory.buildSystemPrompt(directiveDescriptor(), false);
        assertThat(prompt).contains("CoachingDomain");
        assertThat(prompt).contains("BUILD");
        assertThat(prompt).contains("MILITARY");
        assertThat(prompt).contains("verificationUnitType");
    }

    @Test
    void buildUserMessage_containsGameState() {
        Map<String, Object> input = Map.of(
            QuarkMindCaseFile.COACHING_TRIGGER, Map.of(
                "gameFrame", 1000L,
                "urgencyTier", "CRISIS",
                "momentTypes", List.of("NEXUS_UNDER_ATTACK")),
            QuarkMindCaseFile.MINERALS, 500,
            QuarkMindCaseFile.SUPPLY_USED, 44,
            QuarkMindCaseFile.SUPPLY_CAP, 62);
        var message = CoachingWorkerFactory.buildUserMessage(input);
        assertThat(message).contains("NEXUS_UNDER_ATTACK");
        assertThat(message).contains("500");
        assertThat(message).contains("44");
    }

    @Test
    void buildUserMessage_includesPatternAssessment_whenPresent() {
        Map<String, Object> input = Map.of(
            QuarkMindCaseFile.COACHING_TRIGGER, Map.of(
                "gameFrame", 2000L,
                "urgencyTier", "STRATEGIC",
                "momentTypes", List.of("TECH_TRANSITION_DETECTED"),
                "patternAssessment", Map.of(
                    "archetype", "ZERG_ROACH_RUSH",
                    "confidence", 0.85)),
            QuarkMindCaseFile.MINERALS, 300);
        var message = CoachingWorkerFactory.buildUserMessage(input);
        assertThat(message).contains("ZERG_ROACH_RUSH");
        assertThat(message).contains("0.85");
    }

    @Test
    void parseAdvice_validJson_returnsCoachingAdvice() {
        String json = """
            {"advice": "Build 3 Stalkers to counter Roach timing",
             "domain": "MILITARY",
             "verificationUnitType": "STALKER",
             "verificationCountDelta": 3,
             "verificationWindowFrames": 450}""";
        var advice = CoachingWorkerFactory.parseAdvice(json);
        assertThat(advice).isNotNull();
        assertThat(advice.advice()).isEqualTo("Build 3 Stalkers to counter Roach timing");
        assertThat(advice.domainTag()).isEqualTo(CoachingDomain.MILITARY);
        assertThat(advice.verificationUnitType()).isEqualTo(io.quarkmind.domain.UnitType.STALKER);
        assertThat(advice.verificationCountDelta()).isEqualTo(3);
        assertThat(advice.verificationWindowFrames()).isEqualTo(450);
    }

    @Test
    void parseAdvice_nonVerifiable_returnsAdviceWithNulls() {
        String json = """
            {"advice": "Improve your macro",
             "domain": "BUILD"}""";
        var advice = CoachingWorkerFactory.parseAdvice(json);
        assertThat(advice).isNotNull();
        assertThat(advice.advice()).isEqualTo("Improve your macro");
        assertThat(advice.domainTag()).isEqualTo(CoachingDomain.BUILD);
        assertThat(advice.isVerifiable()).isFalse();
    }

    @Test
    void parseAdvice_invalidJson_returnsNull() {
        var advice = CoachingWorkerFactory.parseAdvice("not json");
        assertThat(advice).isNull();
    }
}

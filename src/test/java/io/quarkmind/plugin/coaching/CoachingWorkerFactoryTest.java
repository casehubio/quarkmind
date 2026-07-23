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
        assertThat(prompt).contains("verificationType");
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
        var message = CoachingWorkerFactory.buildUserMessage(input, null);
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
        var message = CoachingWorkerFactory.buildUserMessage(input, null);
        assertThat(message).contains("ZERG_ROACH_RUSH");
        assertThat(message).contains("0.85");
    }

    @Test
    void buildUserMessage_withTaxonomy_includesCountersAndPhase() {
        var taxonomy = new io.quarkmind.agent.StrategyTaxonomy();
        taxonomy.init();
        Map<String, Object> input = Map.of(
                QuarkMindCaseFile.COACHING_TRIGGER, Map.of(
                        "gameFrame", 2000L,
                        "urgencyTier", "STRATEGIC",
                        "momentTypes", List.of("TECH_TRANSITION_DETECTED"),
                        "patternAssessment", Map.of(
                                "archetype", "ZERG_ROACH_RUSH",
                                "confidence", 0.85)),
                QuarkMindCaseFile.MINERALS, 300,
                QuarkMindCaseFile.GAME_PHASE, "EARLY");
        var message = CoachingWorkerFactory.buildUserMessage(input, taxonomy);
        assertThat(message).contains("STRONG COUNTERS:");
        assertThat(message).contains("Immortal");
        assertThat(message).contains("GAME PHASE: EARLY");
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
        assertThat(advice.isVerifiable()).isTrue();
        var countDelta = (CountDelta) advice.verification();
        assertThat(countDelta.unitType()).isEqualTo(io.quarkmind.domain.UnitType.STALKER);
        assertThat(countDelta.expectedDelta()).isEqualTo(3);
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

    @Test
    void parseAdvice_armyCentroidRetreat() {
        String json = """
                      {"advice": "Retreat your army",
                       "domain": "MILITARY",
                       "verificationType": "ARMY_CENTROID_RETREAT",
                       "verificationParams": {"referenceLocation": "ENEMY_BASE", "minDistance": 8.0},
                       "verificationWindowFrames": 450}""";
        var advice = CoachingWorkerFactory.parseAdvice(json);
        assertThat(advice).isNotNull();
        assertThat(advice.isVerifiable()).isTrue();
        var pred = (ArmyCentroidMovement) advice.verification();
        assertThat(pred.direction()).isEqualTo(MovementDirection.RETREAT);
        assertThat(pred.referencePoint()).isInstanceOf(LocationReference.EnemyBase.class);
        assertThat(pred.minDistance()).isEqualTo(8.0);
    }

    @Test
    void parseAdvice_armyCentroidAdvance() {
        String json = """
                      {"advice": "Push forward",
                       "domain": "MILITARY",
                       "verificationType": "ARMY_CENTROID_ADVANCE",
                       "verificationParams": {"referenceLocation": "ENEMY_BASE", "minDistance": 10.0},
                       "verificationWindowFrames": 300}""";
        var advice = CoachingWorkerFactory.parseAdvice(json);
        var pred   = (ArmyCentroidMovement) advice.verification();
        assertThat(pred.direction()).isEqualTo(MovementDirection.ADVANCE);
    }

    @Test
    void parseAdvice_expansionPlacement() {
        String json = """
                      {"advice": "Take your natural",
                       "domain": "EXPAND",
                       "verificationType": "EXPANSION_PLACEMENT",
                       "verificationParams": {"expansionOrdinal": 1},
                       "verificationWindowFrames": 600}""";
        var advice = CoachingWorkerFactory.parseAdvice(json);
        assertThat(advice.isVerifiable()).isTrue();
        var pred = (ExpansionPlacement) advice.verification();
        assertThat(pred.targetExpansion()).isInstanceOf(LocationReference.ExpansionOrdinal.class);
        assertThat(((LocationReference.ExpansionOrdinal) pred.targetExpansion()).ordinal()).isEqualTo(1);
    }

    @Test
    void parseAdvice_unitsNearLocation() {
        String json = """
                      {"advice": "Position stalkers near the natural",
                       "domain": "MILITARY",
                       "verificationType": "UNITS_NEAR_LOCATION",
                       "verificationParams": {"location": "NATURAL", "unitType": "STALKER", "radius": 10.0, "minCount": 3},
                       "verificationWindowFrames": 400}""";
        var advice = CoachingWorkerFactory.parseAdvice(json);
        var pred   = (UnitsNearLocation) advice.verification();
        assertThat(pred.unitType()).isEqualTo(io.quarkmind.domain.UnitType.STALKER);
        assertThat(pred.location()).isInstanceOf(LocationReference.ExpansionOrdinal.class);
        assertThat(pred.radius()).isEqualTo(10.0);
        assertThat(pred.minCount()).isEqualTo(3);
    }

    @Test
    void parseAdvice_countDeltaExplicit() {
        String json = """
                      {"advice": "Build 2 Immortals",
                       "domain": "BUILD",
                       "verificationType": "COUNT_DELTA",
                       "verificationParams": {"unitType": "IMMORTAL", "expectedDelta": 2},
                       "verificationWindowFrames": 500}""";
        var advice = CoachingWorkerFactory.parseAdvice(json);
        var pred   = (CountDelta) advice.verification();
        assertThat(pred.unitType()).isEqualTo(io.quarkmind.domain.UnitType.IMMORTAL);
        assertThat(pred.expectedDelta()).isEqualTo(2);
    }

    @Test
    void parseAdvice_locationTokens_playerBase() {
        String json = """
                      {"advice": "Retreat",
                       "domain": "MILITARY",
                       "verificationType": "ARMY_CENTROID_RETREAT",
                       "verificationParams": {"referenceLocation": "PLAYER_BASE"},
                       "verificationWindowFrames": 300}""";
        var advice = CoachingWorkerFactory.parseAdvice(json);
        var pred   = (ArmyCentroidMovement) advice.verification();
        assertThat(pred.referencePoint()).isInstanceOf(LocationReference.PlayerBase.class);
    }

    @Test
    void parseAdvice_locationTokens_mapCenter() {
        String json = """
                      {"advice": "Control the map center",
                       "domain": "MILITARY",
                       "verificationType": "UNITS_NEAR_LOCATION",
                       "verificationParams": {"location": "MAP_CENTER", "minCount": 5},
                       "verificationWindowFrames": 400}""";
        var advice = CoachingWorkerFactory.parseAdvice(json);
        var pred   = (UnitsNearLocation) advice.verification();
        assertThat(pred.location()).isInstanceOf(LocationReference.MapCenter.class);
    }

    @Test
    void parseAdvice_locationTokens_naturalAlias() {
        String json = """
                      {"advice": "Expand at third",
                       "domain": "EXPAND",
                       "verificationType": "EXPANSION_PLACEMENT",
                       "verificationParams": {"expansionOrdinal": 2},
                       "verificationWindowFrames": 600}""";
        var advice = CoachingWorkerFactory.parseAdvice(json);
        var pred   = (ExpansionPlacement) advice.verification();
        assertThat(((LocationReference.ExpansionOrdinal) pred.targetExpansion()).ordinal()).isEqualTo(2);
    }

    @Test
    void parseAdvice_noVerificationType_legacyFormat_producesCountDelta() {
        String json = """
                      {"advice": "Build stalkers",
                       "domain": "MILITARY",
                       "verificationUnitType": "STALKER",
                       "verificationCountDelta": 3,
                       "verificationWindowFrames": 450}""";
        var advice = CoachingWorkerFactory.parseAdvice(json);
        assertThat(advice.isVerifiable()).isTrue();
        assertThat(advice.verification()).isInstanceOf(CountDelta.class);
    }

    @Test
    void buildSystemPrompt_includesVerificationTypes() {
        var prompt = CoachingWorkerFactory.buildSystemPrompt(directiveDescriptor(), false);
        assertThat(prompt).contains("verificationType");
        assertThat(prompt).contains("ARMY_CENTROID_RETREAT");
        assertThat(prompt).contains("EXPANSION_PLACEMENT");
        assertThat(prompt).contains("UNITS_NEAR_LOCATION");
    }

}

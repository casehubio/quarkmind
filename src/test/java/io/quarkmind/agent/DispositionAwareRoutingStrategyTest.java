package io.quarkmind.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.spi.routing.AgentCandidate;
import io.casehub.api.spi.routing.AgentHealth;
import io.casehub.api.spi.routing.AgentRoutingContext;
import io.casehub.api.spi.routing.RoutingResult;
import io.casehub.api.spi.routing.TrustRoutingPolicy;
import io.casehub.api.spi.routing.TrustRoutingPolicyProvider;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentDisposition;
import io.casehub.eidos.api.MatchDegree;
import io.casehub.eidos.vocab.ConscientiousnessTerm;
import io.casehub.ledger.routing.TrustCandidateClassifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class DispositionAwareRoutingStrategyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CAPABILITY = "advisory-crisis";
    private static final UUID CASE_ID = UUID.randomUUID();

    private static final TrustRoutingPolicy POLICY = TrustRoutingPolicy.DEFAULT;

    private TrustCandidateClassifier classifier;
    private DispositionAwareRoutingStrategy strategy;

    @BeforeEach
    void setUp() {
        classifier = new TrustCandidateClassifier();
        StubTrustScoreSource scoreSource = new StubTrustScoreSource();
        scoreSource.setCapabilityScore("bold-advisor", CAPABILITY, 0.85);
        scoreSource.setDecisionCount("bold-advisor", CAPABILITY, 20);
        scoreSource.setCapabilityScore("conservative-advisor", CAPABILITY, 0.85);
        scoreSource.setDecisionCount("conservative-advisor", CAPABILITY, 20);

        strategy = new DispositionAwareRoutingStrategy(classifier, stubPolicyProvider(), scoreSource);
    }

    private AgentCandidate boldAdvisor() {
        return new AgentCandidate(
                "bold-advisor", Set.of(CAPABILITY), 0, AgentHealth.READY,
                AgentDescriptor.builder()
                        .agentId("claude:crisis-bold@v1").name("Bold Crisis Advisor")
                        .slot("crisis-responder")
                        .disposition(AgentDisposition.builder()
                                .riskAppetite(ConscientiousnessTerm.BOLD.value())
                                .ruleFollowing(ConscientiousnessTerm.FLEXIBLE.value()).build())
                        .tenancyId("default").build(),
                new MatchDegree.Exact());
    }

    private AgentCandidate conservativeAdvisor() {
        return new AgentCandidate(
                "conservative-advisor", Set.of(CAPABILITY), 0, AgentHealth.READY,
                AgentDescriptor.builder()
                        .agentId("claude:crisis-conservative@v1").name("Conservative Crisis Advisor")
                        .slot("crisis-responder")
                        .disposition(AgentDisposition.builder()
                                .riskAppetite(ConscientiousnessTerm.CONSERVATIVE.value())
                                .ruleFollowing(ConscientiousnessTerm.STRICT.value()).build())
                        .tenancyId("default").build(),
                new MatchDegree.Exact());
    }

    private ObjectNode aggressiveEnemyContext() {
        ObjectNode root = MAPPER.createObjectNode();
        root.putObject("working").put("game.enemy.posture", "AGGRESSIVE").put("game.phase", "mid");
        return root;
    }

    private ObjectNode economicEnemyContext() {
        ObjectNode root = MAPPER.createObjectNode();
        root.putObject("working").put("game.enemy.posture", "ECONOMIC").put("game.phase", "mid");
        return root;
    }

    private ObjectNode earlyGameContext() {
        ObjectNode root = MAPPER.createObjectNode();
        root.putObject("working").put("game.phase", "early");
        return root;
    }

    private ObjectNode lateGameContext() {
        ObjectNode root = MAPPER.createObjectNode();
        root.putObject("working").put("game.phase", "late");
        return root;
    }

    @Test
    void conservativePreferredWhenEnemyAggressive() {
        var ctx = new AgentRoutingContext(CASE_ID, CAPABILITY, aggressiveEnemyContext(), "default", List.of());
        RoutingResult result = strategy.select(ctx, List.of(boldAdvisor(), conservativeAdvisor()));
        var selected = assertInstanceOf(RoutingResult.Selected.class, result);
        assertEquals("conservative-advisor", selected.single().executorId());
    }

    @Test
    void boldPreferredWhenEnemyEconomic() {
        var ctx = new AgentRoutingContext(CASE_ID, CAPABILITY, economicEnemyContext(), "default", List.of());
        RoutingResult result = strategy.select(ctx, List.of(boldAdvisor(), conservativeAdvisor()));
        var selected = assertInstanceOf(RoutingResult.Selected.class, result);
        assertEquals("bold-advisor", selected.single().executorId());
    }

    @Test
    void strictPreferredInEarlyGame() {
        var ctx = new AgentRoutingContext(CASE_ID, CAPABILITY, earlyGameContext(), "default", List.of());
        RoutingResult result = strategy.select(ctx, List.of(boldAdvisor(), conservativeAdvisor()));
        var selected = assertInstanceOf(RoutingResult.Selected.class, result);
        assertEquals("conservative-advisor", selected.single().executorId());
    }

    @Test
    void flexiblePreferredInLateGame() {
        var ctx = new AgentRoutingContext(CASE_ID, CAPABILITY, lateGameContext(), "default", List.of());
        RoutingResult result = strategy.select(ctx, List.of(boldAdvisor(), conservativeAdvisor()));
        var selected = assertInstanceOf(RoutingResult.Selected.class, result);
        assertEquals("bold-advisor", selected.single().executorId());
    }

    @Test
    void dispositionNeverHardExcludes_highTrustOverridesDisposition() {
        StubTrustScoreSource scoreSource = new StubTrustScoreSource();
        scoreSource.setCapabilityScore("bold-advisor", CAPABILITY, 0.95);
        scoreSource.setDecisionCount("bold-advisor", CAPABILITY, 50);
        scoreSource.setCapabilityScore("conservative-advisor", CAPABILITY, 0.72);
        scoreSource.setDecisionCount("conservative-advisor", CAPABILITY, 20);
        var highTrustStrategy = new DispositionAwareRoutingStrategy(classifier, stubPolicyProvider(), scoreSource);
        var ctx = new AgentRoutingContext(CASE_ID, CAPABILITY, aggressiveEnemyContext(), "default", List.of());
        RoutingResult result = highTrustStrategy.select(ctx, List.of(boldAdvisor(), conservativeAdvisor()));
        var selected = assertInstanceOf(RoutingResult.Selected.class, result);
        assertEquals("bold-advisor", selected.single().executorId());
    }

    @Test
    void emptyContextDefaultsToNeutralMultiplier() {
        StubTrustScoreSource scoreSource = new StubTrustScoreSource();
        scoreSource.setCapabilityScore("bold-advisor", CAPABILITY, 0.90);
        scoreSource.setDecisionCount("bold-advisor", CAPABILITY, 20);
        scoreSource.setCapabilityScore("conservative-advisor", CAPABILITY, 0.85);
        scoreSource.setDecisionCount("conservative-advisor", CAPABILITY, 20);
        var neutralStrategy = new DispositionAwareRoutingStrategy(classifier, stubPolicyProvider(), scoreSource);
        var ctx = new AgentRoutingContext(CASE_ID, CAPABILITY, MAPPER.createObjectNode(), "default", List.of());
        RoutingResult result = neutralStrategy.select(ctx, List.of(boldAdvisor(), conservativeAdvisor()));
        var selected = assertInstanceOf(RoutingResult.Selected.class, result);
        assertEquals("bold-advisor", selected.single().executorId());
    }

    @Test
    void emptyCandidateListReturnsUnresolvable() {
        var ctx = new AgentRoutingContext(CASE_ID, CAPABILITY, aggressiveEnemyContext(), "default", List.of());
        RoutingResult result = strategy.select(ctx, List.of());
        assertInstanceOf(RoutingResult.Unresolvable.class, result);
    }

    @Test
    void nullDescriptorTreatedAsBootstrap() {
        var noDescriptor = new AgentCandidate("no-descriptor", Set.of(CAPABILITY), 0, AgentHealth.READY, null, new MatchDegree.None());
        var bootstrapStrategy = new DispositionAwareRoutingStrategy(classifier, stubPolicyProvider(), new StubTrustScoreSource());
        var ctx = new AgentRoutingContext(CASE_ID, CAPABILITY, aggressiveEnemyContext(), "default", List.of());
        RoutingResult result = bootstrapStrategy.select(ctx, List.of(noDescriptor));
        var selected = assertInstanceOf(RoutingResult.Selected.class, result);
        assertEquals("no-descriptor", selected.single().executorId());
    }

    @Test
    void dispositionPreferenceMultiplierRange() {
        DispositionPreference pref = new DispositionPreference("conservative", "strict");
        assertEquals(1.2, pref.computeMultiplier(AgentDisposition.builder().riskAppetite("conservative").ruleFollowing("strict").build()), 0.001);
        assertEquals(0.8, pref.computeMultiplier(AgentDisposition.builder().riskAppetite("bold").ruleFollowing("flexible").build()), 0.001);
    }

    @Test
    void dispositionPreferencePartialMatch() {
        DispositionPreference pref = new DispositionPreference("conservative", "strict");
        assertEquals(1.0, pref.computeMultiplier(AgentDisposition.builder().riskAppetite("conservative").ruleFollowing("flexible").build()), 0.001);
    }

    @Test
    void dispositionPreferenceNullAxesToleratedAsNeutral() {
        DispositionPreference onlyRisk = new DispositionPreference("conservative", null);
        assertEquals(1.1, onlyRisk.computeMultiplier(AgentDisposition.builder().riskAppetite("conservative").ruleFollowing("strict").build()), 0.001);
    }

    private static TrustRoutingPolicyProvider stubPolicyProvider() {
        return new TrustRoutingPolicyProvider() {
            @Override public String id() { return "test-stub"; }
            @Override public TrustRoutingPolicy forCapability(String cap) { return POLICY; }
        };
    }

    static class StubTrustScoreSource implements io.casehub.ledger.api.spi.TrustScoreSource {
        private final java.util.Map<String, OptionalDouble> capScores = new java.util.HashMap<>();
        private final java.util.Map<String, Integer> decisionCounts = new java.util.HashMap<>();

        void setCapabilityScore(String actorId, String capabilityTag, double score) {
            capScores.put(actorId + "::" + capabilityTag, OptionalDouble.of(score));
        }
        void setDecisionCount(String actorId, String capabilityTag, int count) {
            decisionCounts.put(actorId + "::" + capabilityTag, count);
        }

        @Override public OptionalDouble globalScore(String actorId) { return OptionalDouble.empty(); }
        @Override public OptionalDouble capabilityScore(String actorId, String capabilityTag) {
            return capScores.getOrDefault(actorId + "::" + capabilityTag, OptionalDouble.empty());
        }
        @Override public OptionalDouble dimensionScore(String actorId, String dimKey) { return OptionalDouble.empty(); }
        @Override public OptionalDouble capabilityDimensionScore(String actorId, String capabilityTag, String dimKey) { return OptionalDouble.empty(); }
        @Override public int decisionCount(String actorId, String capabilityTag) {
            return decisionCounts.getOrDefault(actorId + "::" + capabilityTag, 0);
        }
        @Override public Map<String, Double> allCapabilityScores(String actorId) { return Map.of(); }
        @Override public Map<String, Double> allDimensionScores(String actorId) { return Map.of(); }
        @Override public Map<String, Double> qualityScores(String actorId, String capabilityTag) { return Map.of(); }
    }
}

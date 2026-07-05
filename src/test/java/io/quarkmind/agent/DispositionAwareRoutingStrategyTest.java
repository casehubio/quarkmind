package io.quarkmind.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.spi.routing.AgentAssignment;
import io.casehub.api.spi.routing.AgentCandidate;
import io.casehub.api.spi.routing.AgentHealth;
import io.casehub.api.spi.routing.AgentRoutingContext;
import io.casehub.api.spi.routing.TrustRoutingPolicy;
import io.casehub.api.spi.routing.TrustRoutingPolicyProvider;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentDisposition;
import io.casehub.eidos.vocab.ConscientiousnessTerm;
import io.casehub.ledger.routing.TrustCandidateClassifier;
import io.casehub.ledger.routing.TrustCandidateClassifier.ClassifiedCandidate;
import io.casehub.ledger.routing.TrustCandidateClassifier.Phase;
import io.casehub.ledger.routing.TrustCandidateClassifier.ScoredCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Tests for {@link DispositionAwareRoutingStrategy}.
 *
 * <p>Verifies that disposition scoring adjusts candidate ordering based on game context
 * without ever hard-excluding candidates. A candidate with the "wrong" disposition but
 * sufficient trust score can still be selected.
 */
class DispositionAwareRoutingStrategyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CAPABILITY = "advisory-crisis";
    private static final UUID CASE_ID = UUID.randomUUID();

    /** Reusable trust routing policy — conservative defaults with no quality floors. */
    private static final TrustRoutingPolicy POLICY = TrustRoutingPolicy.DEFAULT;

    private TrustCandidateClassifier classifier;
    private DispositionAwareRoutingStrategy strategy;

    @BeforeEach
    void setUp() {
        classifier = new TrustCandidateClassifier();
        // Stub trust score source: both candidates have identical QUALIFIED trust scores.
        // This isolates the disposition multiplier as the only scoring differentiator.
        StubTrustScoreSource scoreSource = new StubTrustScoreSource();
        scoreSource.setCapabilityScore("bold-advisor", CAPABILITY, 0.85);
        scoreSource.setDecisionCount("bold-advisor", CAPABILITY, 20);
        scoreSource.setCapabilityScore("conservative-advisor", CAPABILITY, 0.85);
        scoreSource.setDecisionCount("conservative-advisor", CAPABILITY, 20);

        strategy = new DispositionAwareRoutingStrategy(classifier, stubPolicyProvider(), scoreSource);
    }

    // ── Candidate builders ──────────────────────────────────────────────

    private AgentCandidate boldAdvisor() {
        return new AgentCandidate(
                "bold-advisor",
                Set.of(CAPABILITY),
                0,
                AgentHealth.READY,
                AgentDescriptor.builder()
                        .agentId("claude:crisis-bold@v1")
                        .name("Bold Crisis Advisor")
                        .slot("crisis-responder")
                        .disposition(AgentDisposition.builder()
                                .riskAppetite(ConscientiousnessTerm.BOLD.value())
                                .ruleFollowing(ConscientiousnessTerm.FLEXIBLE.value())
                                .build())
                        .tenancyId("default")
                        .build());
    }

    private AgentCandidate conservativeAdvisor() {
        return new AgentCandidate(
                "conservative-advisor",
                Set.of(CAPABILITY),
                0,
                AgentHealth.READY,
                AgentDescriptor.builder()
                        .agentId("claude:crisis-conservative@v1")
                        .name("Conservative Crisis Advisor")
                        .slot("crisis-responder")
                        .disposition(AgentDisposition.builder()
                                .riskAppetite(ConscientiousnessTerm.CONSERVATIVE.value())
                                .ruleFollowing(ConscientiousnessTerm.STRICT.value())
                                .build())
                        .tenancyId("default")
                        .build());
    }

    // ── Game context builders ───────────────────────────────────────────

    private ObjectNode aggressiveEnemyContext() {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode working = root.putObject("working");
        working.put("game.enemy.posture", "AGGRESSIVE");
        working.put("game.phase", "mid");
        return root;
    }

    private ObjectNode economicEnemyContext() {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode working = root.putObject("working");
        working.put("game.enemy.posture", "ECONOMIC");
        working.put("game.phase", "mid");
        return root;
    }

    private ObjectNode earlyGameContext() {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode working = root.putObject("working");
        working.put("game.phase", "early");
        return root;
    }

    private ObjectNode lateGameContext() {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode working = root.putObject("working");
        working.put("game.phase", "late");
        return root;
    }

    // ── Tests ───────────────────────────────────────────────────────────

    @Test
    void conservativePreferredWhenEnemyAggressive() {
        // Against AGGRESSIVE enemy: prefer conservative (defensive counsel under pressure)
        AgentRoutingContext ctx = new AgentRoutingContext(CASE_ID, CAPABILITY, aggressiveEnemyContext(), "default");
        List<AgentCandidate> candidates = List.of(boldAdvisor(), conservativeAdvisor());

        AgentAssignment result = strategy.select(ctx, candidates).await().indefinitely();

        AgentAssignment.Assigned assigned = assertInstanceOf(AgentAssignment.Assigned.class, result);
        assertEquals("conservative-advisor", assigned.workerId(),
                "Conservative advisor should be preferred when enemy is AGGRESSIVE");
    }

    @Test
    void boldPreferredWhenEnemyEconomic() {
        // Against ECONOMIC enemy: prefer bold (exploit the opponent's greed window)
        AgentRoutingContext ctx = new AgentRoutingContext(CASE_ID, CAPABILITY, economicEnemyContext(), "default");
        List<AgentCandidate> candidates = List.of(boldAdvisor(), conservativeAdvisor());

        AgentAssignment result = strategy.select(ctx, candidates).await().indefinitely();

        AgentAssignment.Assigned assigned = assertInstanceOf(AgentAssignment.Assigned.class, result);
        assertEquals("bold-advisor", assigned.workerId(),
                "Bold advisor should be preferred when enemy is ECONOMIC");
    }

    @Test
    void strictPreferredInEarlyGame() {
        // Early game: prefer strict rule following (follow established build orders)
        AgentRoutingContext ctx = new AgentRoutingContext(CASE_ID, CAPABILITY, earlyGameContext(), "default");
        List<AgentCandidate> candidates = List.of(boldAdvisor(), conservativeAdvisor());

        AgentAssignment result = strategy.select(ctx, candidates).await().indefinitely();

        AgentAssignment.Assigned assigned = assertInstanceOf(AgentAssignment.Assigned.class, result);
        assertEquals("conservative-advisor", assigned.workerId(),
                "Strict advisor should be preferred in early game");
    }

    @Test
    void flexiblePreferredInLateGame() {
        // Late game: prefer flexible rule following (adapt to evolving situation)
        AgentRoutingContext ctx = new AgentRoutingContext(CASE_ID, CAPABILITY, lateGameContext(), "default");
        List<AgentCandidate> candidates = List.of(boldAdvisor(), conservativeAdvisor());

        AgentAssignment result = strategy.select(ctx, candidates).await().indefinitely();

        AgentAssignment.Assigned assigned = assertInstanceOf(AgentAssignment.Assigned.class, result);
        assertEquals("bold-advisor", assigned.workerId(),
                "Flexible advisor should be preferred in late game");
    }

    @Test
    void dispositionNeverHardExcludes_highTrustOverridesDisposition() {
        // Even with "wrong" disposition, a candidate with significantly higher trust
        // score can still be selected — disposition is a soft preference (multiplier 0.8-1.2).
        StubTrustScoreSource scoreSource = new StubTrustScoreSource();
        // Bold advisor has much higher trust score
        scoreSource.setCapabilityScore("bold-advisor", CAPABILITY, 0.95);
        scoreSource.setDecisionCount("bold-advisor", CAPABILITY, 50);
        // Conservative advisor has lower trust score
        scoreSource.setCapabilityScore("conservative-advisor", CAPABILITY, 0.72);
        scoreSource.setDecisionCount("conservative-advisor", CAPABILITY, 20);

        DispositionAwareRoutingStrategy highTrustStrategy = new DispositionAwareRoutingStrategy(
                classifier, stubPolicyProvider(), scoreSource);

        // AGGRESSIVE context prefers conservative — but bold has much higher trust
        AgentRoutingContext ctx = new AgentRoutingContext(CASE_ID, CAPABILITY, aggressiveEnemyContext(), "default");
        List<AgentCandidate> candidates = List.of(boldAdvisor(), conservativeAdvisor());

        AgentAssignment result = highTrustStrategy.select(ctx, candidates).await().indefinitely();

        AgentAssignment.Assigned assigned = assertInstanceOf(AgentAssignment.Assigned.class, result);
        assertEquals("bold-advisor", assigned.workerId(),
                "High trust should override disposition preference — soft preference, not hard exclusion");
    }

    @Test
    void emptyContextDefaultsToNeutralMultiplier() {
        // When game context has no posture or phase data, multiplier is 1.0 for all candidates.
        // This means trust score alone determines the winner — no disposition influence.
        StubTrustScoreSource scoreSource = new StubTrustScoreSource();
        scoreSource.setCapabilityScore("bold-advisor", CAPABILITY, 0.90);
        scoreSource.setDecisionCount("bold-advisor", CAPABILITY, 20);
        scoreSource.setCapabilityScore("conservative-advisor", CAPABILITY, 0.85);
        scoreSource.setDecisionCount("conservative-advisor", CAPABILITY, 20);

        DispositionAwareRoutingStrategy neutralStrategy = new DispositionAwareRoutingStrategy(
                classifier, stubPolicyProvider(), scoreSource);

        ObjectNode emptyContext = MAPPER.createObjectNode();
        AgentRoutingContext ctx = new AgentRoutingContext(CASE_ID, CAPABILITY, emptyContext, "default");
        List<AgentCandidate> candidates = List.of(boldAdvisor(), conservativeAdvisor());

        AgentAssignment result = neutralStrategy.select(ctx, candidates).await().indefinitely();

        AgentAssignment.Assigned assigned = assertInstanceOf(AgentAssignment.Assigned.class, result);
        assertEquals("bold-advisor", assigned.workerId(),
                "With no game context, higher trust score should win");
    }

    @Test
    void emptyCandidateListReturnsUnresolvable() {
        AgentRoutingContext ctx = new AgentRoutingContext(CASE_ID, CAPABILITY, aggressiveEnemyContext(), "default");

        AgentAssignment result = strategy.select(ctx, List.of()).await().indefinitely();

        assertInstanceOf(AgentAssignment.Unresolvable.class, result);
    }

    @Test
    void nullDescriptorTreatedAsBootstrap() {
        // Candidate with null descriptor: no disposition to match, treated as bootstrap
        AgentCandidate noDescriptor = new AgentCandidate(
                "no-descriptor", Set.of(CAPABILITY), 0, AgentHealth.READY, null);

        StubTrustScoreSource scoreSource = new StubTrustScoreSource();
        // No trust scores seeded → bootstrap phase

        DispositionAwareRoutingStrategy bootstrapStrategy = new DispositionAwareRoutingStrategy(
                classifier, stubPolicyProvider(), scoreSource);

        AgentRoutingContext ctx = new AgentRoutingContext(CASE_ID, CAPABILITY, aggressiveEnemyContext(), "default");

        AgentAssignment result = bootstrapStrategy.select(ctx, List.of(noDescriptor))
                .await().indefinitely();

        // Bootstrap candidates get workload score only — should still be assignable
        AgentAssignment.Assigned assigned = assertInstanceOf(AgentAssignment.Assigned.class, result);
        assertEquals("no-descriptor", assigned.workerId());
    }

    // ── DispositionPreference unit tests ─────────────────────────────────

    @Test
    void dispositionPreferenceMultiplierRange() {
        // Verify multiplier stays in [0.8, 1.2] range
        DispositionPreference pref = new DispositionPreference("conservative", "strict");

        // Perfect match: both axes match
        AgentDisposition matching = AgentDisposition.builder()
                .riskAppetite("conservative")
                .ruleFollowing("strict")
                .build();
        double matchMultiplier = pref.computeMultiplier(matching);
        assertEquals(1.2, matchMultiplier, 0.001, "Perfect match should give 1.2 multiplier");

        // Perfect mismatch: neither axis matches
        AgentDisposition mismatching = AgentDisposition.builder()
                .riskAppetite("bold")
                .ruleFollowing("flexible")
                .build();
        double mismatchMultiplier = pref.computeMultiplier(mismatching);
        assertEquals(0.8, mismatchMultiplier, 0.001, "Complete mismatch should give 0.8 multiplier");
    }

    @Test
    void dispositionPreferencePartialMatch() {
        // One axis matches, one does not: multiplier at neutral 1.0
        DispositionPreference pref = new DispositionPreference("conservative", "strict");

        AgentDisposition partial = AgentDisposition.builder()
                .riskAppetite("conservative")  // matches
                .ruleFollowing("flexible")      // mismatch
                .build();
        double multiplier = pref.computeMultiplier(partial);
        assertEquals(1.0, multiplier, 0.001, "Partial match should give 1.0 multiplier");
    }

    @Test
    void dispositionPreferenceNullAxesToleratedAsNeutral() {
        // When preference has null axis (no opinion), it contributes neutrally
        DispositionPreference onlyRisk = new DispositionPreference("conservative", null);

        AgentDisposition matching = AgentDisposition.builder()
                .riskAppetite("conservative")
                .ruleFollowing("strict")
                .build();
        double multiplier = onlyRisk.computeMultiplier(matching);
        // Only riskAppetite axis contributes: match → +0.1 on half the range
        assertEquals(1.1, multiplier, 0.001, "Single axis match should give 1.1 multiplier");
    }

    private static TrustRoutingPolicyProvider stubPolicyProvider() {
        return new TrustRoutingPolicyProvider() {
            @Override public String id() { return "test-stub"; }
            @Override public TrustRoutingPolicy forCapability(String cap) { return POLICY; }
        };
    }

    // ── Stub implementations ────────────────────────────────────────────

    /**
     * Minimal in-memory trust score source for unit testing.
     * Stores per-(actorId, capabilityTag) scores and decision counts.
     */
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

        @Override
        public OptionalDouble capabilityScore(String actorId, String capabilityTag) {
            return capScores.getOrDefault(actorId + "::" + capabilityTag, OptionalDouble.empty());
        }

        @Override public OptionalDouble dimensionScore(String actorId, String dimKey) {
            return OptionalDouble.empty();
        }

        @Override
        public OptionalDouble capabilityDimensionScore(String actorId, String capabilityTag, String dimKey) {
            return OptionalDouble.empty();
        }

        @Override
        public int decisionCount(String actorId, String capabilityTag) {
            return decisionCounts.getOrDefault(actorId + "::" + capabilityTag, 0);
        }

        @Override public Map<String, Double> allCapabilityScores(String actorId) { return Map.of(); }
        @Override public Map<String, Double> allDimensionScores(String actorId) { return Map.of(); }
        @Override public Map<String, Double> qualityScores(String actorId, String capabilityTag) { return Map.of(); }
    }
}

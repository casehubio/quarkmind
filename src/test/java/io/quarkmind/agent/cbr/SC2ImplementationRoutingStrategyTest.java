package io.quarkmind.agent.cbr;

import io.casehub.api.spi.routing.ImplementationCandidate;
import io.casehub.api.spi.routing.ImplementationRoutingContext;
import io.casehub.api.spi.routing.ImplementationSelection;
import io.casehub.api.spi.routing.RetrievedExperience;
import io.casehub.api.spi.routing.TrustRoutingPolicy;
import io.casehub.api.spi.routing.TrustRoutingPolicyProvider;
import io.casehub.ledger.api.spi.TrustScoreSource;
import io.casehub.ledger.routing.TrustCandidateClassifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SC2ImplementationRoutingStrategyTest {

    TrustCandidateClassifier classifier;
    TrustScoreSource scoreSource;
    TrustRoutingPolicyProvider policyProvider;
    SC2ImplementationRoutingStrategy strategy;

    static final TrustRoutingPolicy POLICY = new TrustRoutingPolicy(
            0.65, 10, 0.08, 0.6, Map.of(), false, "strategy.drools", Set.of(), 0.4);

    @BeforeEach
    void setUp() {
        classifier     = new TrustCandidateClassifier();
        scoreSource    = stubScoreSource(Map.of());
        policyProvider = stubPolicyProvider();
        strategy       = new SC2ImplementationRoutingStrategy(classifier, scoreSource, policyProvider);
    }

    @Test
    void id() {
        assertThat(strategy.id()).isEqualTo("sc2-cbr-routing");
    }

    @Test
    void coldStart_noExperiences_selectsFallback() {
        var candidates = List.of(
                new ImplementationCandidate("strategy.drools", "strategy.drools", "strategy"),
                new ImplementationCandidate("strategy.early-pressure", "strategy.early-pressure", "strategy"));
        var ctx = new ImplementationRoutingContext(UUID.randomUUID(), "strategy", null, "t1", List.of());

        var result = strategy.select(ctx, candidates).await().indefinitely();

        assertThat(result).isInstanceOf(ImplementationSelection.Selected.class);
        var selected = (ImplementationSelection.Selected) result;
        assertThat(selected.bindingNames()).containsExactly("strategy.drools");
    }

    @Test
    void singleCandidate_runsAll() {
        var candidates = List.of(
                new ImplementationCandidate("strategy.drools", "strategy.drools", "strategy"));
        var ctx = new ImplementationRoutingContext(UUID.randomUUID(), "strategy", null, "t1", List.of());

        var result = strategy.select(ctx, candidates).await().indefinitely();
        assertThat(result).isInstanceOf(ImplementationSelection.RunAll.class);
    }

    @Test
    void cbrExperiences_influenceSelection() {
        var experiences = List.of(
                new RetrievedExperience("vs ZERG_ROACH_RUSH (PvZ)", "strategy.early-pressure",
                        "WIN", 1.0, 0.9, Map.of(), List.of(), Map.of()),
                new RetrievedExperience("vs ZERG_ROACH_RUSH (PvZ)", "strategy.early-pressure",
                        "WIN", 1.0, 0.85, Map.of(), List.of(), Map.of()),
                new RetrievedExperience("vs ZERG_ROACH_RUSH (PvZ)", "strategy.drools",
                        "LOSS", 0.0, 0.8, Map.of(), List.of(), Map.of()));

        var candidates = List.of(
                new ImplementationCandidate("strategy.drools", "strategy.drools", "strategy"),
                new ImplementationCandidate("strategy.early-pressure", "strategy.early-pressure", "strategy"));
        var ctx = new ImplementationRoutingContext(UUID.randomUUID(), "strategy", null, "t1", experiences);

        var result = strategy.select(ctx, candidates).await().indefinitely();
        var selected = (ImplementationSelection.Selected) result;
        assertThat(selected.bindingNames()).containsExactly("strategy.early-pressure");
    }

    @Test
    void qualifiedTrust_higherScore_wins() {
        scoreSource = stubScoreSource(Map.of("strategy.drools", 0.90, "strategy.early-pressure", 0.70));
        strategy    = new SC2ImplementationRoutingStrategy(classifier, scoreSource, stubPolicyProvider());

        var candidates = List.of(
                new ImplementationCandidate("strategy.drools", "strategy.drools", "strategy"),
                new ImplementationCandidate("strategy.early-pressure", "strategy.early-pressure", "strategy"));
        var ctx = new ImplementationRoutingContext(UUID.randomUUID(), "strategy", null, "t1", List.of());

        var result   = strategy.select(ctx, candidates).await().indefinitely();
        var selected = (ImplementationSelection.Selected) result;
        assertThat(selected.bindingNames()).containsExactly("strategy.drools");
    }

    @Test
    void noCandidates_runsAll() {
        var ctx = new ImplementationRoutingContext(UUID.randomUUID(), "strategy", null, "t1", List.of());
        var result = strategy.select(ctx, List.of());
        assertThat(result).isInstanceOf(ImplementationSelection.RunAll.class);
    }

    static TrustScoreSource stubScoreSource(Map<String, Double> scores) {
        return new TrustScoreSource() {
            public OptionalDouble globalScore(String w) { return OptionalDouble.empty(); }
            public OptionalDouble capabilityScore(String w, String c) {
                return scores.containsKey(w) ? OptionalDouble.of(scores.get(w)) : OptionalDouble.empty();
            }
            public OptionalDouble dimensionScore(String w, String d) { return OptionalDouble.empty(); }
            public OptionalDouble capabilityDimensionScore(String w, String c, String d) { return OptionalDouble.empty(); }
            public int decisionCount(String w, String c) { return scores.containsKey(w) ? 20 : 0; }
            public Map<String, Double> allCapabilityScores(String w) { return Map.of(); }
            public Map<String, Double> allDimensionScores(String w) { return Map.of(); }
            public Map<String, Double> qualityScores(String w, String c) { return Map.of(); }
        };
    }

    static TrustRoutingPolicyProvider stubPolicyProvider() {
        return new TrustRoutingPolicyProvider() {
            @Override
            public String id()                                             {return "test-policy";}

            @Override
            public TrustRoutingPolicy forCapability(String capabilityName) {return POLICY;}
        };
    }

}

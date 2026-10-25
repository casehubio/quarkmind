package io.quarkmind.sc2.detector;

import io.quarkmind.domain.AssessmentSource;
import io.quarkmind.domain.GamePhase;
import io.quarkmind.domain.PatternAssessment;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.StrategyArchetype;
import io.quarkmind.plugin.scouting.EvidenceMarker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetectorOrchestratorTest {

    private DetectorOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        var config = new DetectorCatalogConfig(List.of(
            new DetectorCatalogConfig.DetectorDef(
                "rush_det", GamePhase.EARLY, FeatureTier.ECONOMY,
                Map.of(Race.PROTOSS, List.of("RUSH")),
                null)
        ));
        var registry = new DetectorRegistry(config);
        var priors = Map.of("RUSH", 0.3, "MACRO", 0.7);
        var cpts = Map.of(
            "rush_det", Map.of("RUSH", 0.85, "MACRO", 0.10)
        );
        var labelMapping = Map.of(
            "RUSH", StrategyArchetype.PROTOSS_GATEWAY_RUSH,
            "MACRO", StrategyArchetype.PROTOSS_MACRO
        );

        orchestrator = new DetectorOrchestrator(
            registry, priors, cpts, labelMapping, 10);
        orchestrator.initializeNetwork(List.of("rush_det"));
    }

    @Test
    void classifyProducesAssessmentsWithFusionSource() {
        var outputs = Map.of("rush_det", 0.85f);
        var result = orchestrator.classify(outputs, List.of(), 100);

        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertTrue(result.stream().allMatch(a -> a.source() == AssessmentSource.FUSION));
    }

    @Test
    void highRushDetectorOutputMakesRushDominant() {
        var outputs = Map.of("rush_det", 0.9f);
        var result = orchestrator.classify(outputs, List.of(), 100);

        assertFalse(result.isEmpty());
        assertEquals(StrategyArchetype.PROTOSS_GATEWAY_RUSH, result.get(0).archetype());
    }

    @Test
    void lowRushDetectorOutputLeavesMacroDominant() {
        var outputs = Map.of("rush_det", 0.1f);
        var result = orchestrator.classify(outputs, List.of(), 100);

        assertFalse(result.isEmpty());
        assertEquals(StrategyArchetype.PROTOSS_MACRO, result.get(0).archetype());
    }

    @Test
    void markersBiasTowardMarkedArchetype() {
        var markers = List.of(
            new EvidenceMarker(StrategyArchetype.PROTOSS_GATEWAY_RUSH, 0.9, "proxy_pylon")
        );
        var result = orchestrator.classify(Map.of(), markers, 100);

        assertFalse(result.isEmpty());
        assertEquals(StrategyArchetype.PROTOSS_GATEWAY_RUSH, result.get(0).archetype());
    }

    @Test
    void posteriorSumsApproximatelyToOne() {
        var outputs = Map.of("rush_det", 0.6f);
        var result = orchestrator.classify(outputs, List.of(), 100);

        double sum = result.stream().mapToDouble(PatternAssessment::confidence).sum();
        assertEquals(1.0, sum, 0.05);
    }

    @Test
    void detectsTransitionWhenDominantShifts() {
        orchestrator.classify(Map.of("rush_det", 0.9f), List.of(), 100);
        orchestrator.classify(Map.of("rush_det", 0.9f), List.of(), 200);
        assertFalse(orchestrator.detectTransition());

        orchestrator.classify(Map.of("rush_det", 0.05f), List.of(), 300);
        orchestrator.classify(Map.of("rush_det", 0.05f), List.of(), 400);
        orchestrator.classify(Map.of("rush_det", 0.05f), List.of(), 500);
        assertTrue(orchestrator.detectTransition());
    }

    @Test
    void resetClearsTrackerState() {
        orchestrator.classify(Map.of("rush_det", 0.85f), List.of(), 100);
        orchestrator.reset();

        orchestrator.classify(Map.of("rush_det", 0.1f), List.of(), 200);
        assertFalse(orchestrator.detectTransition(),
            "First observation after reset should not trigger transition");
    }

    @Test
    void emptyInputsReturnPriorBasedAssessments() {
        var result = orchestrator.classify(Map.of(), List.of(), 100);

        assertFalse(result.isEmpty());
        assertEquals(StrategyArchetype.PROTOSS_MACRO, result.get(0).archetype(),
            "With no evidence, prior (MACRO=0.7) should dominate");
    }

    @Test
    void highConfidenceDetectorUsesHardEvidence() {
        var orchestratorWithThreshold = createOrchestrator(0.90);
        orchestratorWithThreshold.initializeNetwork(List.of("rush_det"));

        var softResult = orchestrator.classify(Map.of("rush_det", 0.95f), List.of(), 100);
        var hardResult = orchestratorWithThreshold.classify(Map.of("rush_det", 0.95f), List.of(), 100);

        assertEquals(softResult.get(0).archetype(), hardResult.get(0).archetype());
        assertTrue(hardResult.get(0).confidence() >= softResult.get(0).confidence(),
                   "Hard evidence should produce equal or higher confidence for the dominant archetype");
    }

    @Test
    void hardEvidenceRetractsWhenConfidenceDrops() {
        var orch = createOrchestrator(0.90);
        orch.initializeNetwork(List.of("rush_det"));

        orch.classify(Map.of("rush_det", 0.95f), List.of(), 100);
        var result = orch.classify(Map.of("rush_det", 0.3f), List.of(), 200);

        assertFalse(result.isEmpty());
        assertTrue(result.stream().anyMatch(a -> a.archetype() == StrategyArchetype.PROTOSS_MACRO),
                   "After confidence drops below threshold, soft evidence should allow MACRO assessment");
    }

    @Test
    void getDetectorsToEvaluateReturnsAllOnFirstTick() {
        var toEvaluate = orchestrator.getDetectorsToEvaluate(java.util.Set.of("rush_det"));
        assertEquals(java.util.Set.of("rush_det"), toEvaluate,
                     "First tick should evaluate all detectors — no sensitivity data yet");
    }

    @Test
    void getDetectorsToEvaluateSkipsLowSensitivityAfterFirstTick() {
        var twoDetOrch = createTwoDetectorOrchestrator(0.001);
        twoDetOrch.classify(Map.of("rush_det", 0.9f, "eco_det", 0.1f), List.of(), 100);

        var toEvaluate = twoDetOrch.getDetectorsToEvaluate(java.util.Set.of("rush_det", "eco_det"));
        assertFalse(toEvaluate.isEmpty(), "Should evaluate at least one detector");
    }

    @Test
    void classifyMergesStaleEvidenceForSkippedDetectors() {
        var twoDetOrch = createTwoDetectorOrchestrator(0.001);
        var full = twoDetOrch.classify(
                Map.of("rush_det", 0.9f, "eco_det", 0.1f), List.of(), 100);

        var partial = twoDetOrch.classify(
                Map.of("rush_det", 0.85f), List.of(), 200);

        assertFalse(partial.isEmpty(), "Partial evidence should still produce assessments");
        assertEquals(full.get(0).archetype(), partial.get(0).archetype(),
                     "Dominant archetype should remain consistent with stale eco_det evidence");
    }

    @Test
    void getStatsReturnsNullBeforeInitialization() {
        var config = new DetectorCatalogConfig(List.of());
        var uninitOrch = new DetectorOrchestrator(
                new DetectorRegistry(config), Map.of(), Map.of(), Map.of(), 10);
        assertNull(uninitOrch.getStats());
    }

    @Test
    void getStatsReturnsStatsAfterClassify() {
        orchestrator.classify(Map.of("rush_det", 0.85f), List.of(), 100);
        var stats = orchestrator.getStats();
        assertNotNull(stats);
        assertTrue(stats.lastPropagationNanos() >= 0);
    }

    @Test
    void getSensitivitiesReturnsRankingsAfterClassify() {
        var twoDetOrch = createTwoDetectorOrchestrator(0.001);
        twoDetOrch.classify(Map.of("rush_det", 0.9f, "eco_det", 0.1f), List.of(), 100);
        var sensitivities = twoDetOrch.getSensitivities();
        assertNotNull(sensitivities);
        assertFalse(sensitivities.isEmpty());
    }

    @Test
    void mapStrategyMatchesMarginalWhenDecisive() {
        orchestrator.classify(Map.of("rush_det", 0.9f), List.of(), 100);
        var mapStrategy = orchestrator.getMapStrategy();

        assertNotNull(mapStrategy);
        assertEquals(StrategyArchetype.PROTOSS_GATEWAY_RUSH, mapStrategy,
                     "MAP should agree with marginal winner when one archetype clearly dominates");
    }

    @Test
    void mapStrategyReturnsNullBeforeClassify() {
        var config = new DetectorCatalogConfig(List.of());
        var orch = new DetectorOrchestrator(
                new DetectorRegistry(config), Map.of(), Map.of(), Map.of(), 10);
        assertNull(orch.getMapStrategy());
    }

    @Test
    void mapAssignmentContainsStrategyAndDetectors() {
        orchestrator.classify(Map.of("rush_det", 0.85f), List.of(), 100);
        var assignment = orchestrator.getMapAssignment();

        assertNotNull(assignment);
        assertTrue(assignment.containsKey("Strategy"),
                   "MAP assignment should contain Strategy variable");
        assertTrue(assignment.containsKey("rush_det"),
                   "MAP assignment should contain detector variables");
    }

    @Test
    void mapStrategyCoherentWithTwoDetectors() {
        var twoDetOrch = createTwoDetectorOrchestrator(0.001);
        twoDetOrch.classify(Map.of("rush_det", 0.9f, "eco_det", 0.1f), List.of(), 100);

        var mapStrategy = twoDetOrch.getMapStrategy();
        assertEquals(StrategyArchetype.PROTOSS_GATEWAY_RUSH, mapStrategy,
                     "MAP should pick RUSH when rush_det is high and eco_det is low");

        var assignment = twoDetOrch.getMapAssignment();
        assertEquals("RUSH", assignment.get("Strategy"));
    }

    @Test
    void explanationTraceReturnsNullBeforeClassify() {
        var config = new DetectorCatalogConfig(List.of());
        var orch = new DetectorOrchestrator(
                new DetectorRegistry(config), Map.of(), Map.of(), Map.of(), 10);
        assertNull(orch.getExplanationTrace());
    }

    @Test
    void explanationTraceListsInfluentialDetectors() {
        orchestrator.classify(Map.of("rush_det", 0.85f), List.of(), 100);
        var trace = orchestrator.getExplanationTrace();

        assertNotNull(trace);
        assertEquals("Strategy", trace.targetVar());
        assertFalse(trace.influences().isEmpty());
        assertEquals("rush_det", trace.influences().get(0).variableName());
    }

    @Test
    void explanationSummaryIsHumanReadable() {
        var twoDetOrch = createTwoDetectorOrchestrator(0.001);
        twoDetOrch.classify(Map.of("rush_det", 0.9f, "eco_det", 0.1f), List.of(), 100);
        var summary = twoDetOrch.getExplanationSummary();

        assertNotNull(summary);
        assertTrue(summary.contains("rush_det"), "Summary should name the detector");
    }

    @Test
    void threeOutcomeLearnedNetworkProducesAssessments() {
        var builder = io.quarkmind.inference.bayes.BayesNetworkBuilder.create();
        builder.variable("Strategy", "RUSH", "MACRO").prior(0.3, 0.7);
        builder.variable("rush_det", "HIGH", "MEDIUM", "LOW")
               .given("Strategy").cpt(0.8, 0.15, 0.05, 0.1, 0.2, 0.7);
        var learnedNetwork = builder.build();

        var learnedNetworks = Map.of("EARLY_vs_protoss", learnedNetwork);
        var config = new DetectorCatalogConfig(List.of(
                new DetectorCatalogConfig.DetectorDef("rush_det", GamePhase.EARLY,
                                                      FeatureTier.ECONOMY, Map.of(Race.PROTOSS, List.of("RUSH")), null)));
        var registry = new DetectorRegistry(config);
        var priors   = Map.of("RUSH", 0.3, "MACRO", 0.7);
        var cpts     = Map.<String, Map<String, Double>>of();
        var labelMapping = Map.of(
                "RUSH", StrategyArchetype.PROTOSS_GATEWAY_RUSH,
                "MACRO", StrategyArchetype.PROTOSS_MACRO);

        var orch = new DetectorOrchestrator(registry, priors, cpts,
                                            labelMapping, 10, 0.95, 0.01, learnedNetworks);
        orch.initializeNetwork(List.of("rush_det"), GamePhase.EARLY, Race.PROTOSS);

        var result = orch.classify(Map.of("rush_det", 0.9f), List.of(), 100);
        assertFalse(result.isEmpty());
        assertEquals(StrategyArchetype.PROTOSS_GATEWAY_RUSH, result.get(0).archetype());
    }

    @Test
    void fallbackToHandTunedWhenNoLearnedNetwork() {
        var learnedNetworks = Map.<String, io.quarkmind.inference.bayes.BayesNetwork>of();
        var config = new DetectorCatalogConfig(List.of(
                new DetectorCatalogConfig.DetectorDef("rush_det", GamePhase.EARLY,
                                                      FeatureTier.ECONOMY, Map.of(Race.PROTOSS, List.of("RUSH")), null)));
        var registry = new DetectorRegistry(config);
        var priors   = Map.of("RUSH", 0.3, "MACRO", 0.7);
        var cpts     = Map.of("rush_det", Map.of("RUSH", 0.85, "MACRO", 0.10));
        var labelMapping = Map.of(
                "RUSH", StrategyArchetype.PROTOSS_GATEWAY_RUSH,
                "MACRO", StrategyArchetype.PROTOSS_MACRO);

        var orch = new DetectorOrchestrator(registry, priors, cpts,
                                            labelMapping, 10, 0.95, 0.01, learnedNetworks);
        orch.initializeNetwork(List.of("rush_det"), GamePhase.MID, Race.PROTOSS);

        var result = orch.classify(Map.of("rush_det", 0.85f), List.of(), 100);
        assertFalse(result.isEmpty());
        assertEquals(StrategyArchetype.PROTOSS_GATEWAY_RUSH, result.get(0).archetype());
    }

    @Test
    void toThreeElementLikelihoodBoundaryValues() {
        var high = DetectorOrchestrator.toThreeElementLikelihood(0.95f);
        assertEquals(1.0, high[0], 0.001);
        assertEquals(0.0, high[1], 0.001);
        assertEquals(0.0, high[2], 0.001);

        var low = DetectorOrchestrator.toThreeElementLikelihood(0.05f);
        assertEquals(0.0, low[0], 0.001);
        assertEquals(0.0, low[1], 0.001);
        assertEquals(1.0, low[2], 0.001);

        var midpoint = DetectorOrchestrator.toThreeElementLikelihood(0.55f);
        assertEquals(0.0, midpoint[0], 0.001);
        assertTrue(midpoint[1] > 0.5, "MEDIUM should dominate at score 0.55");
        assertTrue(midpoint[2] < 0.5);

        var sum = midpoint[0] + midpoint[1] + midpoint[2];
        assertEquals(1.0, sum, 0.001);
    }


    private DetectorOrchestrator createTwoDetectorOrchestrator(double sensitivityThreshold) {
        var config = new DetectorCatalogConfig(List.of(
                new DetectorCatalogConfig.DetectorDef(
                        "rush_det", GamePhase.EARLY, FeatureTier.ECONOMY,
                        Map.of(Race.PROTOSS, List.of("RUSH")),
                        null),
                new DetectorCatalogConfig.DetectorDef(
                        "eco_det", GamePhase.EARLY, FeatureTier.ECONOMY,
                        Map.of(Race.PROTOSS, List.of("MACRO")),
                        null)
                                                      ));
        var registry = new DetectorRegistry(config);
        var priors   = Map.of("RUSH", 0.3, "MACRO", 0.7);
        var cpts = Map.of(
                "rush_det", Map.of("RUSH", 0.85, "MACRO", 0.10),
                "eco_det", Map.of("RUSH", 0.10, "MACRO", 0.90)
                         );
        var labelMapping = Map.of(
                "RUSH", StrategyArchetype.PROTOSS_GATEWAY_RUSH,
                "MACRO", StrategyArchetype.PROTOSS_MACRO
                                 );
        var orch = new DetectorOrchestrator(
                registry, priors, cpts, labelMapping, 10, 0.95, sensitivityThreshold);
        orch.initializeNetwork(List.of("rush_det", "eco_det"));
        return orch;
    }


    private DetectorOrchestrator createOrchestrator(double hardEvidenceThreshold) {
        var config = new DetectorCatalogConfig(List.of(
                new DetectorCatalogConfig.DetectorDef(
                        "rush_det", GamePhase.EARLY, FeatureTier.ECONOMY,
                        Map.of(Race.PROTOSS, List.of("RUSH")),
                        null)
                                                      ));
        var registry = new DetectorRegistry(config);
        var priors   = Map.of("RUSH", 0.3, "MACRO", 0.7);
        var cpts = Map.of(
                "rush_det", Map.of("RUSH", 0.85, "MACRO", 0.10)
                         );
        var labelMapping = Map.of(
                "RUSH", StrategyArchetype.PROTOSS_GATEWAY_RUSH,
                "MACRO", StrategyArchetype.PROTOSS_MACRO
                                 );
        return new DetectorOrchestrator(
                registry, priors, cpts, labelMapping, 10, hardEvidenceThreshold);
    }

}

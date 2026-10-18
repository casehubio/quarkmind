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

import static org.junit.jupiter.api.Assertions.*;

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
}

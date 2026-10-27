package io.quarkmind.sc2.detector;

import io.quarkmind.domain.GamePhase;
import io.quarkmind.domain.PatternAssessment;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.ScoutingObservation;
import io.quarkmind.domain.StrategyArchetype;
import io.quarkmind.plugin.scouting.EvidenceMarker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class HypothesisTrackerFusionTest {

    private HypothesisTrackerFusion fusion;

    @BeforeEach
    void setUp() {
        Map<String, Double> priors = HypothesisPriorLoader.loadOpponentPriors(
            GamePhase.EARLY, Race.TERRAN);
        Map<String, Map<String, Double>> detectorCpts = Map.of();
        HypothesisPriorLoader.ObservationCpts obsCpts =
            HypothesisPriorLoader.loadObservationCpts(GamePhase.EARLY, Race.TERRAN);

        fusion = new HypothesisTrackerFusion(
            null, priors, detectorCpts, obsCpts, Map.of(), null);
        fusion.initialize(List.of(), GamePhase.EARLY, Race.TERRAN);
    }

    @Test
    void classify_withNoEvidence_producesAssessments() {
        List<PatternAssessment> assessments = fusion.classify(
            Map.of(), List.of(), 100L);
        assertNotNull(assessments);
        assertFalse(assessments.isEmpty());
        double totalConfidence = assessments.stream()
            .mapToDouble(PatternAssessment::confidence).sum();
        assertTrue(totalConfidence > 0.5, "Total confidence should be meaningful");
    }

    @Test
    void observeScouting_shiftsWeights() {
        Map<String, Double> before = fusion.opponentWeights();
        HypothesisPriorLoader.ObservationCpts obsCpts =
            HypothesisPriorLoader.loadObservationCpts(GamePhase.EARLY, Race.TERRAN);
        String firstVar = obsCpts.stateIndex().keySet().iterator().next();
        String firstState = obsCpts.stateIndex().get(firstVar)[0];

        fusion.observeScouting(new ScoutingObservation(firstVar, firstState, 100L));

        Map<String, Double> after = fusion.opponentWeights();
        assertNotEquals(before, after, "Weights should change after scouting observation");
    }

    @Test
    void classify_withDroolsMarkers_producesAssessments() {
        List<PatternAssessment> assessments = fusion.classify(
            Map.of(),
            List.of(new EvidenceMarker(StrategyArchetype.TERRAN_MARINE_RUSH, 0.8, "test")),
            300L);
        assertNotNull(assessments);
        assertFalse(assessments.isEmpty());
    }

    @Test
    void getMapStrategy_returnsArchetype() {
        fusion.classify(Map.of(), List.of(), 100L);
        StrategyArchetype strategy = fusion.getMapStrategy();
        assertNotNull(strategy);
    }

    @Test
    void getExplanationSummary_containsLeaderInfo() {
        fusion.classify(Map.of(), List.of(), 100L);
        String summary = fusion.getExplanationSummary();
        assertNotNull(summary);
        assertTrue(summary.contains("Viable:"));
        assertTrue(summary.contains("Leader:"));
    }

    @Test
    void getDetectorsToEvaluate_returnsSubset() {
        Set<String> available = Set.of("det1", "det2");
        Set<String> needed = fusion.getDetectorsToEvaluate(available);
        assertNotNull(needed);
    }

    @Test
    void reset_clearsState() {
        fusion.observeScouting(new ScoutingObservation("first_building", "barracks", 100L));
        fusion.reset();
        assertFalse(fusion.isOpponentResolved());
    }

    @Test
    void phaseTransition_seedsPriorsFromTerminal() {
        fusion.observeScouting(new ScoutingObservation("first_building", "barracks", 100L));

        fusion.initialize(List.of(), GamePhase.MID, Race.TERRAN);
        Map<String, Double> midWeights = fusion.opponentWeights();

        assertFalse(midWeights.isEmpty());
    }

    @Test
    void setPerTickEvidence_doesNotCumulateWeights() {
        Map<String, Double> before = fusion.opponentWeights();
        fusion.classify(Map.of(), List.of(), 100L);
        fusion.classify(Map.of(), List.of(), 200L);
        fusion.classify(Map.of(), List.of(), 300L);
        Map<String, Double> after = fusion.opponentWeights();

        assertEquals(before, after,
            "Per-tick evidence must not change persistent weights");
    }
}

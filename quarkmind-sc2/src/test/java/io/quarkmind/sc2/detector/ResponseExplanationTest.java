package io.quarkmind.sc2.detector;

import io.quarkmind.domain.GamePhase;
import io.quarkmind.domain.Race;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponseExplanationTest {

    private HypothesisTrackerFusion fusion;

    @BeforeEach
    void setUp() {
        var config = new DetectorCatalogConfig(List.of(
            new DetectorCatalogConfig.DetectorDef(
                "rush_det", GamePhase.EARLY, FeatureTier.ECONOMY,
                Map.of(Race.TERRAN, List.of("TERRAN_MARINE_RUSH")),
                null),
            new DetectorCatalogConfig.DetectorDef(
                "gas_det", GamePhase.EARLY, FeatureTier.ECONOMY,
                Map.of(Race.TERRAN, List.of("TERRAN_MACRO")),
                null)
        ));
        var registry = new DetectorRegistry(config);

        Map<String, Double> priors = Map.of(
            "TERRAN_MARINE_RUSH", 0.5,
            "TERRAN_MACRO", 0.5
        );
        Map<String, Map<String, Double>> detCpts = Map.of(
            "rush_det", Map.of("TERRAN_MARINE_RUSH", 0.9, "TERRAN_MACRO", 0.1),
            "gas_det", Map.of("TERRAN_MARINE_RUSH", 0.2, "TERRAN_MACRO", 0.8)
        );
        Map<String, Map<String, Double>> responsePriors = Map.of(
            "TERRAN_MARINE_RUSH", Map.of("GATEWAY_RUSH", 0.7, "MACRO", 0.3),
            "TERRAN_MACRO", Map.of("GATEWAY_RUSH", 0.3, "MACRO", 0.7)
        );

        fusion = new HypothesisTrackerFusion(
            registry, priors, detCpts,
            new HypothesisPriorLoader.ObservationCpts(Map.of(), Map.of()),
            Map.of(), null,
            responsePriors, List.of()
        );
        fusion.initialize(List.of("rush_det", "gas_det"), GamePhase.EARLY, Race.TERRAN);
        fusion.setResponseConfidenceGate(0.3);
    }

    @Test
    void selectResponseWithExplanation_producesStructuredTrace() {
        fusion.classify(Map.of("rush_det", 0.9f, "gas_det", 0.2f), List.of(), 100);

        var payload = fusion.selectResponseWithExplanation(
            Race.PROTOSS, Map.of(
                "our_economy_state", "even",
                "our_army_state", "even",
                "our_tech_state", "even"
            ));

        assertNotNull(payload);
        assertFalse(payload.responses().isEmpty());

        var explanation = payload.explanation();
        assertNotNull(explanation);

        assertNotNull(explanation.opponent());
        assertTrue(explanation.opponent().weight() > 0.0);
        assertFalse(explanation.opponent().keyEvidence().isEmpty());

        assertNotNull(explanation.response());
        assertTrue(explanation.response().confidence() > 0.0);
        assertTrue(explanation.response().cptValue() > 0.0);

        assertNotNull(explanation.context());
        assertEquals("even", explanation.context().state().get("our_economy_state"));
    }

    @Test
    void keyEvidence_sortedByAbsoluteInfluence() {
        fusion.classify(Map.of("rush_det", 0.9f, "gas_det", 0.2f), List.of(), 100);

        var payload = fusion.selectResponseWithExplanation(
            Race.PROTOSS, Map.of(
                "our_economy_state", "even",
                "our_army_state", "even",
                "our_tech_state", "even"
            ));

        var evidence = payload.explanation().opponent().keyEvidence();
        for (int i = 1; i < evidence.size(); i++) {
            assertTrue(
                Math.abs(evidence.get(i - 1).influence()) >= Math.abs(evidence.get(i).influence()),
                "Evidence should be sorted by absolute influence descending");
        }
    }

    @Test
    void contextEffect_shifted_whenNotAllEven() {
        fusion.classify(Map.of("rush_det", 0.9f), List.of(), 100);

        var payload = fusion.selectResponseWithExplanation(
            Race.PROTOSS, Map.of(
                "our_economy_state", "behind",
                "our_army_state", "smaller",
                "our_tech_state", "behind"
            ));

        assertTrue(payload.explanation().context().shifted());
    }

    @Test
    void contextEffect_notShifted_whenAllEven() {
        fusion.classify(Map.of("rush_det", 0.9f), List.of(), 100);

        var payload = fusion.selectResponseWithExplanation(
            Race.PROTOSS, Map.of(
                "our_economy_state", "even",
                "our_army_state", "even",
                "our_tech_state", "even"
            ));

        assertFalse(payload.explanation().context().shifted());
    }

    @Test
    void expertOverride_reportedWhenActive() {
        var overrides = HypothesisPriorLoader.loadExpertOverrides(Race.TERRAN);
        assertFalse(overrides.isEmpty(), "Need at least one override for vs_terran");

        var targetOverride = overrides.stream()
            .filter(ov -> "MARINE_RUSH".equals(ov.whenOpponent()))
            .findFirst().orElseThrow();

        var priors = HypothesisPriorLoader.loadOpponentPriors(GamePhase.EARLY, Race.TERRAN);
        var responsePriors = HypothesisPriorLoader.loadResponsePriors(Race.TERRAN);
        var obsCpts = HypothesisPriorLoader.loadObservationCpts(GamePhase.EARLY, Race.TERRAN);

        var detectorNames = new java.util.ArrayList<String>();
        Map<String, Map<String, Double>> detCpts = new java.util.HashMap<>();
        for (String arch : priors.keySet()) {
            String detName = arch.toLowerCase() + "_det";
            detectorNames.add(detName);
            detCpts.put(detName, Map.of("MARINE_RUSH", "MARINE_RUSH".equals(arch) ? 0.99 : 0.01));
        }

        var config = new DetectorCatalogConfig(detectorNames.stream()
            .map(d -> new DetectorCatalogConfig.DetectorDef(
                d, GamePhase.EARLY, FeatureTier.ECONOMY,
                Map.of(Race.TERRAN, List.of("MARINE_RUSH")), null))
            .toList());
        var registry = new DetectorRegistry(config);

        var fusionWithOverride = new HypothesisTrackerFusion(
            registry, priors, detCpts, obsCpts,
            Map.of(), null,
            responsePriors, overrides
        );
        fusionWithOverride.initialize(detectorNames, GamePhase.EARLY, Race.TERRAN);
        fusionWithOverride.setResponseConfidenceGate(0.01);

        Map<String, Float> detectorOutputs = new java.util.HashMap<>();
        for (String det : detectorNames) {
            detectorOutputs.put(det, det.startsWith("marine_rush") ? 0.99f : 0.01f);
        }
        fusionWithOverride.classify(detectorOutputs, List.of(), 100);

        io.quarkmind.domain.ScoutingObservation obs = new io.quarkmind.domain.ScoutingObservation(
            "first_building", "barracks", 500);
        fusionWithOverride.observeScouting(obs);

        var payload = fusionWithOverride.selectResponseWithExplanation(
            Race.PROTOSS, Map.of(
                "our_economy_state", "even",
                "our_army_state", "even",
                "our_tech_state", "even"
            ));

        assertNotNull(payload.explanation(), "Explanation must not be null");
        String leader = payload.explanation().opponent().archetype();
        if ("MARINE_RUSH".equals(leader)) {
            assertEquals(targetOverride.preferredResponse(), payload.explanation().expertOverride());
        } else {
            var matchingOverride = overrides.stream()
                .filter(ov -> leader.equals(ov.whenOpponent())).findFirst();
            if (matchingOverride.isPresent()) {
                assertEquals(matchingOverride.get().preferredResponse(), payload.explanation().expertOverride());
            } else {
                assertNull(payload.explanation().expertOverride(),
                    "No override should match leader " + leader);
            }
        }
    }

    @Test
    void noExplanation_whenConfidenceBelowGate() {
        fusion.setResponseConfidenceGate(0.99);
        fusion.classify(Map.of("rush_det", 0.5f), List.of(), 100);

        var payload = fusion.selectResponseWithExplanation(
            Race.PROTOSS, Map.of(
                "our_economy_state", "even",
                "our_army_state", "even",
                "our_tech_state", "even"
            ));

        assertTrue(payload.responses().isEmpty());
        assertNull(payload.explanation());
    }

    @Test
    void responseCptValue_matchesLeaderMarginal() {
        fusion.classify(Map.of("rush_det", 0.9f, "gas_det", 0.2f), List.of(), 100);

        var payload = fusion.selectResponseWithExplanation(
            Race.PROTOSS, Map.of(
                "our_economy_state", "even",
                "our_army_state", "even",
                "our_tech_state", "even"
            ));

        var explanation = payload.explanation();
        assertTrue(explanation.response().cptValue() > 0.0,
            "CPT value must be positive for the selected response");
        assertTrue(explanation.response().cptValue() <= 1.0,
            "CPT value must not exceed 1.0");
    }

    @Test
    void scoutingObservation_appearsInKeyEvidence_withObservedState() {
        fusion.classify(Map.of("rush_det", 0.9f, "gas_det", 0.2f), List.of(), 100);
        fusion.observeScouting(new io.quarkmind.domain.ScoutingObservation(
                "first_building", "barracks", 500));

        var payload = fusion.selectResponseWithExplanation(
                Race.PROTOSS, Map.of(
                        "our_economy_state", "even",
                        "our_army_state", "even",
                        "our_tech_state", "even"
                                    ));

        var evidence = payload.explanation().opponent().keyEvidence();
        var scoutingEvidence = evidence.stream()
                                       .filter(e -> e.observedState() != null)
                                       .toList();

        assertFalse(scoutingEvidence.isEmpty(),
                    "Scouting observations should appear in key evidence");
        var firstScouting = scoutingEvidence.getFirst();
        assertEquals("first_building", firstScouting.variable());
        assertEquals("barracks", firstScouting.observedState());
    }

    @Test
    void detectorEvidence_hasNullObservedState() {
        fusion.classify(Map.of("rush_det", 0.9f), List.of(), 100);

        var payload = fusion.selectResponseWithExplanation(
                Race.PROTOSS, Map.of(
                        "our_economy_state", "even",
                        "our_army_state", "even",
                        "our_tech_state", "even"
                                    ));

        var detectorEvidence = payload.explanation().opponent().keyEvidence().stream()
                                      .filter(e -> e.observedState() == null)
                                      .toList();

        assertFalse(detectorEvidence.isEmpty(),
                    "Detector evidence should have null observedState");
    }

    @Test
    void format_rendersObservedStateAsKeyValue() {
        var explanation = new ResponseExplanation(
                new ResponseExplanation.OpponentRationale("MARINE_RUSH", 0.82,
                                                          List.of(new EvidenceContribution("first_building", 0.31, "barracks"),
                                                                  new EvidenceContribution("rush_det", 0.25))),
                new ResponseExplanation.ResponseRationale("GATEWAY_RUSH", 0.48, 0.70),
                null,
                new ResponseExplanation.ContextEffect(
                        Map.of("our_economy_state", "even"), false)
        );

        String formatted = explanation.format();
        assertTrue(formatted.contains("first_building=barracks (+0.31)"),
                   "Scouting observation should render as variable=state: " + formatted);
        assertTrue(formatted.contains("rush_det (+0.25)"),
                   "Detector should render without state: " + formatted);
    }


    @Test
    void format_producesReadableOutput() {
        var explanation = new ResponseExplanation(
                new ResponseExplanation.OpponentRationale("MARINE_RUSH", 0.82,
                                                          List.of(new EvidenceContribution("rush_det", 0.31),
                                                                  new EvidenceContribution("gas_det", -0.18))),
                new ResponseExplanation.ResponseRationale("GATEWAY_RUSH", 0.48, 0.70),
                null,
                new ResponseExplanation.ContextEffect(
                        Map.of("our_economy_state", "even", "our_army_state", "even", "our_tech_state", "even"),
                        false)
        );

        String formatted = explanation.format();
        assertTrue(formatted.contains("GATEWAY_RUSH (0.48)"));
        assertTrue(formatted.contains("MARINE_RUSH (0.82 weight)"));
        assertTrue(formatted.contains("rush_det (+0.31)"));
        assertTrue(formatted.contains("gas_det (-0.18)"));
        assertTrue(formatted.contains("P(GATEWAY_RUSH | MARINE_RUSH) = 0.70"));
        assertTrue(formatted.contains("none active"));
        assertTrue(formatted.contains("no effect"));
    }
}

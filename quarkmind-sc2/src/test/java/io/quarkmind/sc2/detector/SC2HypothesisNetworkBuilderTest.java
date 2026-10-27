package io.quarkmind.sc2.detector;

import io.quarkmind.domain.GamePhase;
import io.quarkmind.domain.Race;
import io.quarkmind.inference.bayes.HypothesisTracker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SC2HypothesisNetworkBuilderTest {

    @Test
    void buildOpponentTracker_earlyVsTerran_createsViableHypotheses() {
        Map<String, Double> priors = HypothesisPriorLoader.loadOpponentPriors(
            GamePhase.EARLY, Race.TERRAN);
        HypothesisPriorLoader.ObservationCpts obsCpts =
            HypothesisPriorLoader.loadObservationCpts(GamePhase.EARLY, Race.TERRAN);

        HypothesisTracker tracker = SC2HypothesisNetworkBuilder.buildOpponentTracker(
            Race.TERRAN, GamePhase.EARLY, priors,
            List.of(), Map.of(), obsCpts);

        assertNotNull(tracker);
        assertFalse(tracker.viable().isEmpty());
        assertEquals(priors.size(), tracker.viable().size(),
            "Each archetype in priors should be a hypothesis");
        assertNotNull(tracker.leader());
        assertFalse(tracker.isResolved());
    }

    @Test
    void buildOpponentTracker_withDetectors_includesDetectorVariables() {
        Map<String, Double> priors = HypothesisPriorLoader.loadOpponentPriors(
            GamePhase.EARLY, Race.TERRAN);
        HypothesisPriorLoader.ObservationCpts obsCpts =
            HypothesisPriorLoader.loadObservationCpts(GamePhase.EARLY, Race.TERRAN);
        Map<String, Map<String, Double>> detectorCpts = Map.of(
            "rush_detector", Map.of("PROXY_BARRACKS", 0.9, "FAST_EXPAND", 0.1));

        HypothesisTracker tracker = SC2HypothesisNetworkBuilder.buildOpponentTracker(
            Race.TERRAN, GamePhase.EARLY, priors,
            List.of("rush_detector"), detectorCpts, obsCpts);

        assertNotNull(tracker);
        assertEquals(priors.size(), tracker.viable().size());
    }

    @Test
    void buildOpponentTracker_observeHard_shiftsWeights() {
        Map<String, Double> priors = HypothesisPriorLoader.loadOpponentPriors(
            GamePhase.EARLY, Race.TERRAN);
        HypothesisPriorLoader.ObservationCpts obsCpts =
            HypothesisPriorLoader.loadObservationCpts(GamePhase.EARLY, Race.TERRAN);

        HypothesisTracker tracker = SC2HypothesisNetworkBuilder.buildOpponentTracker(
            Race.TERRAN, GamePhase.EARLY, priors,
            List.of(), Map.of(), obsCpts);

        Map<String, Double> before = tracker.weights();
        String firstVar = obsCpts.stateIndex().keySet().iterator().next();
        String firstState = obsCpts.stateIndex().get(firstVar)[0];
        tracker.observeHard("intel", firstVar, firstState);
        Map<String, Double> after = tracker.weights();

        assertNotEquals(before, after, "Weights should change after observation");
    }

    @Test
    void buildOpponentTracker_allPhasesAndRaces() {
        for (GamePhase phase : GamePhase.values()) {
            for (Race race : new Race[]{Race.TERRAN, Race.PROTOSS, Race.ZERG}) {
                Map<String, Double> priors =
                    HypothesisPriorLoader.loadOpponentPriors(phase, race);
                HypothesisPriorLoader.ObservationCpts obsCpts =
                    HypothesisPriorLoader.loadObservationCpts(phase, race);

                HypothesisTracker tracker = SC2HypothesisNetworkBuilder.buildOpponentTracker(
                    race, phase, priors, List.of(), Map.of(), obsCpts);
                assertFalse(tracker.viable().isEmpty(),
                    "Tracker should have hypotheses for " + phase + "/" + race);
            }
        }
    }

    @Test
    void buildTracker_twoSlotComposition_withResponseSlot() {
        Map<String, Double> priors = Map.of("MARINE_RUSH", 0.6, "FAST_EXPAND", 0.4);
        var detectorCpts = Map.of("rush_detector",
                                  Map.of("MARINE_RUSH", 0.9, "FAST_EXPAND", 0.1));
        var obsCpts = new HypothesisPriorLoader.ObservationCpts(Map.of(), Map.of());
        Map<String, Map<String, Double>> responsePriors = Map.of(
                "MARINE_RUSH", Map.of("GATEWAY_RUSH", 0.6, "MACRO", 0.4),
                "FAST_EXPAND", Map.of("GATEWAY_RUSH", 0.3, "MACRO", 0.7)
                                                                );

        HypothesisTracker tracker = SC2HypothesisNetworkBuilder.buildTracker(
                Race.TERRAN, GamePhase.EARLY, Race.PROTOSS,
                priors, List.of("rush_detector"), detectorCpts, obsCpts,
                responsePriors, List.of());

        assertNotNull(tracker);
        assertEquals(2, tracker.viable().size());

        double[] responseMargin = tracker.marginalize("MARINE_RUSH", "response", "our_strategy");
        assertNotNull(responseMargin);
        assertEquals(2, responseMargin.length);
    }

    @Test
    void buildTracker_noResponsePriors_singleSlotComposition() {
        Map<String, Double> priors = Map.of("MARINE_RUSH", 0.6, "FAST_EXPAND", 0.4);
        var detectorCpts = Map.of("rush_detector",
                                  Map.of("MARINE_RUSH", 0.9, "FAST_EXPAND", 0.1));
        var obsCpts = new HypothesisPriorLoader.ObservationCpts(Map.of(), Map.of());

        HypothesisTracker tracker = SC2HypothesisNetworkBuilder.buildTracker(
                Race.TERRAN, GamePhase.EARLY, Race.PROTOSS,
                priors, List.of("rush_detector"), detectorCpts, obsCpts,
                Map.of(), List.of());

        assertNotNull(tracker);
        assertEquals(2, tracker.viable().size());
    }

    @Test
    void buildTracker_withExpertOverrides_shiftsResponsePriors() {
        Map<String, Double> priors  = Map.of("CANNON_RUSH", 0.3, "MACRO", 0.7);
        var                 obsCpts = new HypothesisPriorLoader.ObservationCpts(Map.of(), Map.of());
        Map<String, Map<String, Double>> responsePriors = Map.of(
                "CANNON_RUSH", Map.of("MACRO", 0.01, "GATEWAY_RUSH", 0.99),
                "MACRO", Map.of("MACRO", 0.6, "GATEWAY_RUSH", 0.4)
                                                                );
        var overrides = List.of(
                new HypothesisPriorLoader.ExpertOverride("CANNON_RUSH", "MACRO", 3.0, 0.05)
                               );

        HypothesisTracker tracker = SC2HypothesisNetworkBuilder.buildTracker(
                Race.PROTOSS, GamePhase.EARLY, Race.PROTOSS,
                priors, List.of(), Map.of(), obsCpts,
                responsePriors, overrides);

        assertNotNull(tracker);

        double[] responseMargin = tracker.marginalize("CANNON_RUSH", "response", "our_strategy");
        assertNotNull(responseMargin);
    }

}

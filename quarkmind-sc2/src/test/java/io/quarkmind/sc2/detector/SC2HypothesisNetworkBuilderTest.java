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
import static org.junit.jupiter.api.Assertions.assertTrue;

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


    @Test
    void buildContextResponseCpt_differentContexts_differentRows() {
        String[] enemies = {"FAST_EXPAND", "MARINE_RUSH"};
        String[] ours    = {"GATEWAY_RUSH", "MACRO"};

        var contextPriors = Map.of(
                "MARINE_RUSH", Map.of(
                        "behind:even:even", Map.of("GATEWAY_RUSH", 0.8, "MACRO", 0.2),
                        "ahead:even:even", Map.of("GATEWAY_RUSH", 0.3, "MACRO", 0.7)),
                "FAST_EXPAND", Map.of(
                        "even:even:even", Map.of("GATEWAY_RUSH", 0.5, "MACRO", 0.5)));
        var fallback = Map.of(
                "MARINE_RUSH", Map.of("GATEWAY_RUSH", 0.5, "MACRO", 0.5),
                "FAST_EXPAND", Map.of("GATEWAY_RUSH", 0.5, "MACRO", 0.5));

        double[] cpt = SC2HypothesisNetworkBuilder.buildContextResponseCpt(
                enemies, ours, contextPriors, fallback, List.of());

        // MARINE_RUSH is at index 1 (sorted: FAST_EXPAND=0, MARINE_RUSH=1)
        // behind:even:even → economy=0, army=1, tech=1 → 0*9 + 1*3 + 1 = 4
        int behindIdx = (1 * 27 + 4) * 2;
        // ahead:even:even → economy=2, army=1, tech=1 → 2*9 + 1*3 + 1 = 22
        int aheadIdx = (1 * 27 + 22) * 2;

        assertTrue(cpt[behindIdx] > cpt[behindIdx + 1],
                   "behind context: GATEWAY_RUSH should dominate");
        assertTrue(cpt[aheadIdx + 1] > cpt[aheadIdx],
                   "ahead context: MACRO should dominate");
        assertNotEquals(cpt[behindIdx], cpt[aheadIdx]);
    }

    @Test
    void buildContextResponseCpt_missingContext_fallsBackToUnconditional() {
        String[] enemies = {"MARINE_RUSH"};
        String[] ours    = {"GATEWAY_RUSH", "MACRO"};

        var contextPriors = Map.of(
                "MARINE_RUSH", Map.of(
                        "even:even:even", Map.of("GATEWAY_RUSH", 0.9, "MACRO", 0.1)));
        var fallback = Map.of(
                "MARINE_RUSH", Map.of("GATEWAY_RUSH", 0.4, "MACRO", 0.6));

        double[] cpt = SC2HypothesisNetworkBuilder.buildContextResponseCpt(
                enemies, ours, contextPriors, fallback, List.of());

        // even:even:even = 1*9 + 1*3 + 1 = 13
        int evenIdx = 13 * 2;
        // behind:smaller:behind = 0*9 + 0*3 + 0 = 0
        int fallbackIdx = 0 * 2;

        assertTrue(cpt[evenIdx] > cpt[evenIdx + 1],
                   "even context: GATEWAY_RUSH should dominate from context priors");
        assertTrue(cpt[fallbackIdx + 1] > cpt[fallbackIdx],
                   "missing context: MACRO should dominate from fallback priors");
    }

    @Test
    void buildContextResponseCpt_expertOverrides_applied() {
        String[] enemies = {"CANNON_RUSH"};
        String[] ours    = {"GATEWAY_RUSH", "MACRO"};

        var contextPriors = Map.of(
                "CANNON_RUSH", Map.of(
                        "even:even:even", Map.of("GATEWAY_RUSH", 0.9, "MACRO", 0.1)));
        var fallback = Map.of(
                "CANNON_RUSH", Map.of("GATEWAY_RUSH", 0.5, "MACRO", 0.5));
        var overrides = List.of(
                new HypothesisPriorLoader.ExpertOverride("CANNON_RUSH", "MACRO", 3.0, 0.05));

        double[] cpt = SC2HypothesisNetworkBuilder.buildContextResponseCpt(
                enemies, ours, contextPriors, fallback, overrides);

        int evenIdx = 13 * 2;
        assertTrue(cpt[evenIdx + 1] > 0.1,
                   "Expert override should boost MACRO above context prior");
    }

    @Test
    void buildTracker_withContextPriors_differentContextsProduceDifferentMarginals() {
        Map<String, Double> priors  = Map.of("MARINE_RUSH", 0.6, "FAST_EXPAND", 0.4);
        var                 obsCpts = new HypothesisPriorLoader.ObservationCpts(Map.of(), Map.of());
        Map<String, Map<String, Double>> responsePriors = Map.of(
                "MARINE_RUSH", Map.of("GATEWAY_RUSH", 0.5, "MACRO", 0.5),
                "FAST_EXPAND", Map.of("GATEWAY_RUSH", 0.5, "MACRO", 0.5));
        var contextPriors = Map.of(
                "MARINE_RUSH", Map.of(
                        "behind:even:even", Map.of("GATEWAY_RUSH", 0.9, "MACRO", 0.1),
                        "ahead:even:even", Map.of("GATEWAY_RUSH", 0.2, "MACRO", 0.8)),
                "FAST_EXPAND", Map.of(
                        "behind:even:even", Map.of("GATEWAY_RUSH", 0.4, "MACRO", 0.6),
                        "ahead:even:even", Map.of("GATEWAY_RUSH", 0.6, "MACRO", 0.4)));

        HypothesisTracker tracker = SC2HypothesisNetworkBuilder.buildTracker(
                Race.TERRAN, GamePhase.EARLY, Race.PROTOSS,
                priors, List.of(), Map.of(), obsCpts,
                responsePriors, List.of(), contextPriors);

        assertNotNull(tracker);

        tracker.setPerTickEvidence("response", "our_economy_state", new double[]{1.0, 0.0, 0.0});
        tracker.setPerTickEvidence("response", "our_army_state", new double[]{0.0, 1.0, 0.0});
        tracker.setPerTickEvidence("response", "our_tech_state", new double[]{0.0, 1.0, 0.0});
        double[] behindMarginal = tracker.marginalize("MARINE_RUSH", "response", "our_strategy");

        tracker.setPerTickEvidence("response", "our_economy_state", new double[]{0.0, 0.0, 1.0});
        double[] aheadMarginal = tracker.marginalize("MARINE_RUSH", "response", "our_strategy");

        assertNotEquals(behindMarginal[0], aheadMarginal[0], 0.01,
                        "Behind vs ahead should produce different response marginals");
    }
}

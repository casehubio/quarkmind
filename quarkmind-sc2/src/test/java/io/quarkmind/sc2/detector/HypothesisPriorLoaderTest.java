package io.quarkmind.sc2.detector;

import io.quarkmind.domain.GamePhase;
import io.quarkmind.domain.Race;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HypothesisPriorLoaderTest {

    @Test
    void loadOpponentPriors_earlyVsTerran() {
        Map<String, Double> priors = HypothesisPriorLoader.loadOpponentPriors(
            GamePhase.EARLY, Race.TERRAN);
        assertFalse(priors.isEmpty());
        assertTrue(priors.values().stream().allMatch(v -> v > 0));
    }

    @Test
    void loadObservationCpts_earlyVsTerran() {
        HypothesisPriorLoader.ObservationCpts cpts =
            HypothesisPriorLoader.loadObservationCpts(GamePhase.EARLY, Race.TERRAN);
        assertFalse(cpts.stateIndex().isEmpty());
        assertFalse(cpts.cpts().isEmpty());
        assertTrue(cpts.stateIndex().containsKey("first_building"));
        assertTrue(cpts.cpts().containsKey("first_building"));
        for (var entry : cpts.cpts().entrySet()) {
            String[] states = cpts.stateIndex().get(entry.getKey());
            assertNotNull(states, "stateIndex must have entry for: " + entry.getKey());
            for (var archetypeEntry : entry.getValue().entrySet()) {
                assertEquals(states.length, archetypeEntry.getValue().length,
                    "CPT row length must match state count for " + entry.getKey()
                        + "/" + archetypeEntry.getKey());
            }
        }
    }

    @Test
    void loadOpponentPriors_allPhasesAndRaces() {
        for (GamePhase phase : GamePhase.values()) {
            for (Race race : new Race[]{Race.TERRAN, Race.PROTOSS, Race.ZERG}) {
                Map<String, Double> priors = HypothesisPriorLoader.loadOpponentPriors(phase, race);
                assertFalse(priors.isEmpty(),
                    "Priors should exist for " + phase + "/" + race);
            }
        }
    }

    @Test
    void applyExpertOverrides_multiplierWithFloor() {
        Map<String, Double> priors = new java.util.LinkedHashMap<>(Map.of(
                "GATEWAY_RUSH", 0.001,
                "MACRO", 0.50,
                "BLINK_STALKER", 0.30,
                "CANNON_RUSH", 0.189
                                                                         ));
        var overrides = java.util.List.of(
                new HypothesisPriorLoader.ExpertOverride("MARINE_RUSH", "GATEWAY_RUSH", 3.0, 0.05)
                                         );

        Map<String, Double> result = HypothesisPriorLoader.applyExpertOverrides(priors, overrides);

        assertTrue(result.get("GATEWAY_RUSH") >= 0.04,
                   "Floor guarantee should push GATEWAY_RUSH above 0.04");
        assertEquals(1.0, result.values().stream().mapToDouble(d -> d).sum(), 0.001,
                     "Distribution must sum to 1.0");
    }

    @Test
    void applyExpertOverrides_noFloorNeeded() {
        Map<String, Double> priors = new java.util.LinkedHashMap<>(Map.of(
                "MACRO", 0.50,
                "BLINK_STALKER", 0.30,
                "CANNON_RUSH", 0.20
                                                                         ));
        var overrides = java.util.List.of(
                new HypothesisPriorLoader.ExpertOverride("FAST_EXPAND", "MACRO", 2.0, 0.05)
                                         );

        Map<String, Double> result = HypothesisPriorLoader.applyExpertOverrides(priors, overrides);

        assertTrue(result.get("MACRO") > 0.50, "MACRO should increase with 2x multiplier");
        assertEquals(1.0, result.values().stream().mapToDouble(d -> d).sum(), 0.001);
    }

    @Test
    void loadExpertOverrides_parsesYaml() {
        var overrides = HypothesisPriorLoader.loadExpertOverrides(Race.TERRAN);
        assertFalse(overrides.isEmpty(), "Should have overrides for vs_terran");
        assertEquals("PROXY_BARRACKS", overrides.getFirst().whenOpponent());
        assertTrue(overrides.getFirst().priorMultiplier() > 0);
    }

    @Test
    void applyExpertOverrides_emptyOverrides_noChange() {
        Map<String, Double> priors = new java.util.LinkedHashMap<>(Map.of(
                "MACRO", 0.60,
                "GATEWAY_RUSH", 0.40
                                                                         ));

        Map<String, Double> result = HypothesisPriorLoader.applyExpertOverrides(priors, java.util.List.of());

        assertEquals(0.60, result.get("MACRO"), 0.001);
        assertEquals(0.40, result.get("GATEWAY_RUSH"), 0.001);
    }

}

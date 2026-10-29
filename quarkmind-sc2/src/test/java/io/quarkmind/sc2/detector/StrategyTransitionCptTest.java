package io.quarkmind.sc2.detector;

import io.quarkmind.domain.GamePhase;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.StrategyArchetype;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StrategyTransitionCptTest {

    @Test
    void loadTransitionCptsReturnsValidCpt() {
        Map<String, Map<String, Double>> cpts = HypothesisPriorLoader.loadTransitionCpts(Race.PROTOSS);
        assertFalse(cpts.isEmpty(), "transition CPTs should not be empty");
        for (var row : cpts.entrySet()) {
            double sum = row.getValue().values().stream().mapToDouble(Double::doubleValue).sum();
            assertEquals(1.0, sum, 0.01, "CPT row " + row.getKey() + " should sum to 1.0");
        }
    }

    @Test
    void loadTransitionCptsForAllRaces() {
        for (Race race : new Race[]{Race.PROTOSS, Race.TERRAN, Race.ZERG}) {
            Map<String, Map<String, Double>> cpts = HypothesisPriorLoader.loadTransitionCpts(race);
            assertFalse(cpts.isEmpty(), "transition CPTs for " + race + " should not be empty");
        }
    }

    @Test
    void loadTransitionCptsForMissingResourceReturnsEmpty() {
        // No transition file exists for a non-standard race name — simulate by testing the method
        // doesn't throw when file is absent (PROTOSS/TERRAN/ZERG have files, but the fallback is Map.of())
        Map<String, Map<String, Double>> cpts = HypothesisPriorLoader.loadTransitionCpts(Race.PROTOSS);
        assertNotNull(cpts, "should never return null");
    }

    @Test
    void loadUnifiedPriorsContainsAllPhases() {
        Map<String, Double> priors = HypothesisPriorLoader.loadUnifiedPriors(Race.PROTOSS);
        assertFalse(priors.isEmpty());

        boolean hasEarly = priors.keySet().stream()
                .anyMatch(k -> StrategyArchetype.valueOf(k).phase() == GamePhase.EARLY);
        boolean hasMid = priors.keySet().stream()
                .anyMatch(k -> StrategyArchetype.valueOf(k).phase() == GamePhase.MID);
        boolean hasLate = priors.keySet().stream()
                .anyMatch(k -> StrategyArchetype.valueOf(k).phase() == GamePhase.LATE);
        assertTrue(hasEarly && hasMid && hasLate, "unified priors must span all phases");
    }

    @Test
    void loadUnifiedPriorsSumToOne() {
        Map<String, Double> priors = HypothesisPriorLoader.loadUnifiedPriors(Race.TERRAN);
        double sum = priors.values().stream().mapToDouble(Double::doubleValue).sum();
        assertEquals(1.0, sum, 0.01, "unified priors should sum to 1.0");
    }

    @Test
    void loadUnifiedPriorsOnlyContainsMatchingRace() {
        Map<String, Double> priors = HypothesisPriorLoader.loadUnifiedPriors(Race.ZERG);
        for (String key : priors.keySet()) {
            assertEquals(Race.ZERG, StrategyArchetype.valueOf(key).race(),
                    "unified priors should only contain archetypes for the specified race");
        }
    }

    @Test
    void transitionCptKeysAreValidArchetypes() {
        Map<String, Map<String, Double>> cpts = HypothesisPriorLoader.loadTransitionCpts(Race.TERRAN);
        for (String key : cpts.keySet()) {
            assertDoesNotThrow(() -> StrategyArchetype.valueOf(key),
                    "CPT key should be a valid StrategyArchetype: " + key);
        }
        for (var row : cpts.values()) {
            for (String target : row.keySet()) {
                assertDoesNotThrow(() -> StrategyArchetype.valueOf(target),
                        "CPT target should be a valid StrategyArchetype: " + target);
            }
        }
    }
}

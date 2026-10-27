package io.quarkmind.sc2.detector;

import io.quarkmind.domain.GamePhase;
import io.quarkmind.domain.Race;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

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
}

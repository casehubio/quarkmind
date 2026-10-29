package io.quarkmind.sc2.detector;

import io.quarkmind.agent.StrategyTaxonomy;
import io.quarkmind.domain.StrategyArchetype;

import java.util.LinkedHashMap;
import java.util.Map;

public final class PhaseWindowDecay {

    static final double FLOOR = 0.01;

    private PhaseWindowDecay() {}

    public static double weight(double gameTimeMinutes, double windowMin, double windowMax, double ramp) {
        if (ramp <= 0.0) {
            return (gameTimeMinutes >= windowMin && gameTimeMinutes <= windowMax) ? 1.0 : FLOOR;
        }
        if (gameTimeMinutes < windowMin - ramp) {
            return FLOOR;
        } else if (gameTimeMinutes < windowMin) {
            return FLOOR + (1.0 - FLOOR) * sigmoid((gameTimeMinutes - windowMin + ramp / 2) / (ramp / 4));
        } else if (gameTimeMinutes <= windowMax) {
            return 1.0;
        } else if (gameTimeMinutes < windowMax + ramp) {
            return FLOOR + (1.0 - FLOOR) * sigmoid((windowMax + ramp / 2 - gameTimeMinutes) / (ramp / 4));
        } else {
            return FLOOR;
        }
    }

    public static Map<String, Double> computePhaseWeights(
            String[] archetypes, StrategyTaxonomy taxonomy, double gameTimeMinutes) {
        Map<String, Double> weights = new LinkedHashMap<>();
        for (String label : archetypes) {
            var archetype = StrategyArchetype.valueOf(label);
            var entry = taxonomy.lookup(archetype);
            double[] window = entry.phaseWindow();
            weights.put(label, weight(gameTimeMinutes, window[0], window[1], 1.0));
        }
        return weights;
    }

    private static double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }
}

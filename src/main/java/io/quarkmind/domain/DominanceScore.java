package io.quarkmind.domain;

import java.util.Map;

public record DominanceScore(double overall, Map<String, Double> factors) {
    public DominanceScore {
        factors = Map.copyOf(factors);
    }
}

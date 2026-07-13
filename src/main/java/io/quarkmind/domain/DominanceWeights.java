package io.quarkmind.domain;

public record DominanceWeights(double economy, double army, double tech, double bases) {
    public DominanceWeights {
        double sum = economy + army + tech + bases;
        if (Math.abs(sum - 1.0) > 0.001) {
            throw new IllegalArgumentException("Weights must sum to 1.0, got " + sum);
        }
    }
}

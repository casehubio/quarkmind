package io.quarkmind.agent;

import io.quarkmind.domain.DominanceWeights;

public interface DominanceWeightStrategy {
    double MINIMUM_WEIGHT = 0.05;

    String id();
    DominanceWeights resolve(WeightContext context);
}

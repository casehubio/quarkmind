package io.quarkmind.agent;

import io.quarkmind.domain.DominanceWeights;

public interface DominanceWeightStrategy {
    String id();
    DominanceWeights resolve(WeightContext context);
}

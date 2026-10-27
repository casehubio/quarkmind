package io.quarkmind.plugin.drools;

import io.quarkmind.domain.StrategyArchetype;

public record ResponseRecommendationFact(
    StrategyArchetype archetype,
    double confidence
) {}

package io.quarkmind.agent.cbr;

import io.quarkmind.domain.StrategyArchetype;

public record StrategySelectionPublished(String strategyId, StrategyArchetype archetype, double confidence, int pivotCount) {}

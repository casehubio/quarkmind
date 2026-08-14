package io.quarkmind.qa.workbench;

import io.quarkmind.domain.StrategyArchetype;

public record StrategyPayload(String strategyId, StrategyArchetype archetype, double confidence, int pivotCount) implements WorkbenchPayload {}

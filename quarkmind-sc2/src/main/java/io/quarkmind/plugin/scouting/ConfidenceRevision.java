package io.quarkmind.plugin.scouting;

import io.quarkmind.domain.StrategyArchetype;

public record ConfidenceRevision(StrategyArchetype archetype, double dampingFactor, String reason) {}

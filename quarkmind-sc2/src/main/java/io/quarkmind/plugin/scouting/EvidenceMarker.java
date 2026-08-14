package io.quarkmind.plugin.scouting;

import io.quarkmind.domain.StrategyArchetype;

public record EvidenceMarker(StrategyArchetype archetype, double weight, String signal) {}

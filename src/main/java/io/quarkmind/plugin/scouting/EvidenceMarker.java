package io.quarkmind.plugin.scouting;

import io.quarkmind.domain.EnemyArchetype;

public record EvidenceMarker(EnemyArchetype archetype, double weight, String signal) {}

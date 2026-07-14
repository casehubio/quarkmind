package io.quarkmind.plugin.scouting;

import io.quarkmind.domain.EnemyArchetype;

public record ConfidenceRevision(EnemyArchetype archetype, double dampingFactor, String reason) {}

package io.quarkmind.domain;

public record EnemyPatternAssessment(
    EnemyArchetype archetype,
    double confidence,
    long detectedAtFrame,
    String rationale
) {}

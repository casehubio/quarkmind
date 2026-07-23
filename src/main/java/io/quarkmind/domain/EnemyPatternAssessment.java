package io.quarkmind.domain;

public record EnemyPatternAssessment(
    StrategyArchetype archetype,
    double confidence,
    long detectedAtFrame,
    String rationale
) {}

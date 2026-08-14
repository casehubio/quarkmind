package io.quarkmind.domain;

public record PatternAssessment(
    StrategyArchetype archetype,
    double confidence,
    long detectedAtFrame,
    String rationale
) {}

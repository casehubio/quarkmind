package io.quarkmind.domain;

public record StrategyTransition(
    StrategyArchetype from,
    StrategyArchetype to,
    double fromConfidence,
    double toConfidence,
    long detectedAtFrame,
    TransitionPath path
) {}

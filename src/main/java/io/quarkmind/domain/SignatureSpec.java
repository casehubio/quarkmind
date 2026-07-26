package io.quarkmind.domain;

public record SignatureSpec(
    StrategyArchetype archetype,
    UnitType unitType,
    int minCount,
    double windowStart,
    double windowEnd,
    double weight,
    boolean noExpansion,
    Race race
) {}

package io.quarkmind.agent;

public record WeightModifier(
    double economyDelta,
    double armyDelta,
    double techDelta,
    double basesDelta,
    double mapControlDelta,
    String reason
) {}

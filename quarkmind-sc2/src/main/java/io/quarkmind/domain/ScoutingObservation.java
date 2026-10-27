package io.quarkmind.domain;

public record ScoutingObservation(
    String variable,
    String state,
    long detectedAtLoop
) {}

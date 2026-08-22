package io.quarkmind.plugin.scouting;

public record WindowSnapshot(
    float[] playerFeatures,
    float[] opponentFeatures,
    float scoutingVisibility
) {}

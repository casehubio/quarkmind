package io.quarkmind.plugin.commentary;

public record FewShotExample(
    String id,
    double qualityScore,
    String matchup,
    String gameStateSummary,
    String commentary
) {}

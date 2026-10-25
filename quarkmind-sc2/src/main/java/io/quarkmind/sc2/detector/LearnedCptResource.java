package io.quarkmind.sc2.detector;

import io.quarkmind.inference.bayes.NetworkSpec;

public record LearnedCptResource(
    String matchup,
    String phase,
    LearnedFrom learnedFrom,
    NetworkSpec network
) {
    public record LearnedFrom(
        int replayCount,
        int observationCount,
        String extractorVersion,
        String generatedAt
    ) {}
}

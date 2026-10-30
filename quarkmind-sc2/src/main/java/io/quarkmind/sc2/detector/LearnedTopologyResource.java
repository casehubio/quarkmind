package io.quarkmind.sc2.detector;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkmind.inference.bayes.NetworkSpec;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LearnedTopologyResource(
    String matchup,
    String phase,
    String topology,
    double improvement,
    boolean switchToLearned,
    List<VariantSummary> allVariants,
    TopologyProvenance learnedFrom,
    NetworkSpec network
) {
    public record VariantSummary(
        String name,
        double logLikelihood,
        double bicScore,
        int edges
    ) {}

    public record TopologyProvenance(
        int replayCount,
        int observationCount,
        String extractorVersion,
        String generatedAt,
        String winningVariant,
        double winningBic,
        double winningLogLikelihood,
        double starBic,
        double improvementMargin,
        boolean thresholdMet,
        double pcAlpha,
        int hcRestarts,
        long hcSeed,
        double improvementThreshold
    ) {}
}

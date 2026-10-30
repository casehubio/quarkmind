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
    List<Map<String, Object>> allVariants,
    NetworkSpec network
) {}

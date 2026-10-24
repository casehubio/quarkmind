package io.quarkmind.agent.plugin;

import java.util.Map;

public record DetectorStatsPublished(
        int dirtyVariableCount,
        long propagationNanos,
        int retractionCount,
        int evidenceVariableCount,
        Map<String, Double> sensitivities
) {}

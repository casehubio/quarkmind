package io.quarkmind.qa.workbench;

import java.util.Map;

public record DetectorStatsPayload(
        int dirtyVariableCount,
        long propagationNanos,
        int retractionCount,
        int evidenceVariableCount,
        Map<String, Double> sensitivities,
        String mapStrategy,
        Map<String, Object> mapAssignment,
        String explanationSummary
) implements WorkbenchPayload {}

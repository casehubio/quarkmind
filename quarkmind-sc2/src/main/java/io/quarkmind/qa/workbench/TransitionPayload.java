package io.quarkmind.qa.workbench;

record TransitionPayload(
    String from, String to,
    double fromConfidence, double toConfidence,
    long detectedAtFrame,
    String displayName, String coachingAdvice
) implements WorkbenchPayload {}

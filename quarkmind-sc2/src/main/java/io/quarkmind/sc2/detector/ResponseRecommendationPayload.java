package io.quarkmind.sc2.detector;

import io.quarkmind.domain.PatternAssessment;

import java.util.List;

public record ResponseRecommendationPayload(
    List<PatternAssessment> responses,
    ResponseExplanation explanation
) {}

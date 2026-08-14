package io.quarkmind.agent;

import io.quarkmind.domain.PatternAssessment;

import java.util.List;

public record WeightContext(
        long gameFrame,
        String currentPhase,
        List<PatternAssessment> patternAssessments
) {}

package io.quarkmind.agent;

import io.quarkmind.domain.EnemyPatternAssessment;

import java.util.List;

public record WeightContext(
        long gameFrame,
        String currentPhase,
        List<EnemyPatternAssessment> patternAssessments
) {}

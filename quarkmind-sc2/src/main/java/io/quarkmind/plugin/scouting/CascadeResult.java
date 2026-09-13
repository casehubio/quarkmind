package io.quarkmind.plugin.scouting;

import io.quarkmind.domain.PatternAssessment;
import io.quarkmind.domain.StrategyTransition;
import java.util.List;

public record CascadeResult(List<PatternAssessment> assessments, boolean llmTriggered, StrategyTransition transition) {
    public CascadeResult {
        assessments = List.copyOf(assessments);
    }
}

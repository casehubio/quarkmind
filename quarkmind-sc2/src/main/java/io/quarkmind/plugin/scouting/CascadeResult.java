package io.quarkmind.plugin.scouting;

import io.quarkmind.domain.PatternAssessment;
import io.quarkmind.domain.StrategyArchetype;
import io.quarkmind.domain.StrategyTransition;
import java.util.List;

public record CascadeResult(List<PatternAssessment> assessments, boolean llmTriggered,
                            StrategyTransition transition, StrategyArchetype mapStrategy) {
    public CascadeResult {
        assessments = List.copyOf(assessments);
    }

    public CascadeResult(List<PatternAssessment> assessments, boolean llmTriggered, StrategyTransition transition) {
        this(assessments, llmTriggered, transition, null);
    }
}

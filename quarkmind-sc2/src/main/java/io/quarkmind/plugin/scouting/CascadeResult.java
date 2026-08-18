package io.quarkmind.plugin.scouting;

import io.quarkmind.domain.PatternAssessment;
import java.util.List;

public record CascadeResult(List<PatternAssessment> assessments, boolean llmTriggered) {
    public CascadeResult {
        assessments = List.copyOf(assessments);
    }
}

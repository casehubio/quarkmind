package io.quarkmind.qa.workbench;

import io.quarkmind.domain.CounterInfo;
import io.quarkmind.domain.PatternAssessment;

public record EnrichedAssessment(PatternAssessment assessment, CounterInfo counters) {}

package io.quarkmind.qa.workbench;

import java.util.List;

public record PatternPayload(List<EnrichedAssessment> assessments) implements WorkbenchPayload {}

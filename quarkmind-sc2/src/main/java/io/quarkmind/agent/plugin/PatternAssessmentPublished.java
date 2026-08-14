package io.quarkmind.agent.plugin;

import io.quarkmind.domain.PatternAssessment;

import java.util.List;

public record PatternAssessmentPublished(List<PatternAssessment> assessments) {}

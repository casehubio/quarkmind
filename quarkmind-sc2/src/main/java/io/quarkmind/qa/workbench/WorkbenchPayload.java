package io.quarkmind.qa.workbench;

public sealed interface WorkbenchPayload permits PatternPayload, CoachingPayload, CoachingCompliancePayload, StrategyPayload {}

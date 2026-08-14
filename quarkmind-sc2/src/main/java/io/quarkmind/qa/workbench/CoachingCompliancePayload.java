package io.quarkmind.qa.workbench;

import io.quarkmind.plugin.coaching.CoachingDomain;

public record CoachingCompliancePayload(long gameFrame, CoachingDomain domain, String status, String correlationId) implements WorkbenchPayload {}

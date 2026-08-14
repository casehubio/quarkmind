package io.quarkmind.qa.workbench;

import io.quarkmind.plugin.coaching.CoachingDomain;
import io.quarkmind.plugin.coaching.CoachingUrgencyTier;

public record CoachingPayload(String advice, CoachingDomain domain, CoachingUrgencyTier urgency, long gameFrame, String correlationId) implements WorkbenchPayload {}

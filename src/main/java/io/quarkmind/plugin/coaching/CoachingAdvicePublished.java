package io.quarkmind.plugin.coaching;

public record CoachingAdvicePublished(CoachingAdvice advice, CoachingUrgencyTier urgencyTier, long gameFrame) {}

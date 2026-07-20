package io.quarkmind.plugin.coaching;

public record CoachingCompleted(
        String workerId,
        String capability,
        long gameFrame,
        CoachingAdvice advice,
        CoachingUrgencyTier urgencyTier,
        long latencyMs,
        io.quarkmind.domain.GameState triggerState
) {}

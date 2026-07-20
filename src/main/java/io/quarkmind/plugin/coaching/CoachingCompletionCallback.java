package io.quarkmind.plugin.coaching;

@FunctionalInterface
public interface CoachingCompletionCallback {
    void onCompleted(String workerId, String capability, long gameFrame,
                     CoachingAdvice advice, CoachingUrgencyTier urgencyTier, long latencyMs,
                     io.quarkmind.domain.GameState triggerState);
}

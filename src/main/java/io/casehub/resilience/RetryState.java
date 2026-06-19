package io.casehub.resilience;

import io.casehub.error.ErrorInfo;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Tracks the retry history for a specific TaskDefinition or Task execution.
 * Records each attempt with its timestamp, error details, and backoff applied.
 * Used by CaseEngine and TaskScheduler to determine whether retries remain,
 * and by {@link DeadLetterQueue} to preserve the full failure history.
 *
 * @see RetryPolicy
 */
public class RetryState {
    private String targetId;
    private int attemptNumber;
    private int maxAttempts;
    private List<RetryAttempt> history;

    public RetryState(String targetId, int maxAttempts) {
        this.targetId = targetId;
        this.attemptNumber = 0;
        this.maxAttempts = maxAttempts;
        this.history = new ArrayList<>();
    }

    public RetryAttempt recordAttempt(ErrorInfo error, Duration backoffApplied) {
        attemptNumber++;
        RetryAttempt attempt = new RetryAttempt(attemptNumber, error, backoffApplied);
        history.add(attempt);
        return attempt;
    }

    public boolean hasAttemptsRemaining() {
        return attemptNumber < maxAttempts;
    }

    public String getTargetId() { return targetId; }
    public int getAttemptNumber() { return attemptNumber; }
    public int getMaxAttempts() { return maxAttempts; }
    public List<RetryAttempt> getHistory() { return history; }

    /**
     * Immutable snapshot of a single retry attempt, capturing when it ran, why it
     * failed, and how long the subsequent backoff lasted.
     */
    public static class RetryAttempt {
        private int attemptNumber;
        private Instant startedAt;
        private Instant failedAt;
        private ErrorInfo error;
        private Duration backoffApplied;

        public RetryAttempt(int attemptNumber, ErrorInfo error, Duration backoffApplied) {
            this.attemptNumber = attemptNumber;
            this.startedAt = Instant.now();
            this.failedAt = Instant.now();
            this.error = error;
            this.backoffApplied = backoffApplied;
        }

        public int getAttemptNumber() { return attemptNumber; }
        public Instant getStartedAt() { return startedAt; }
        public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
        public Instant getFailedAt() { return failedAt; }
        public void setFailedAt(Instant failedAt) { this.failedAt = failedAt; }
        public ErrorInfo getError() { return error; }
        public Duration getBackoffApplied() { return backoffApplied; }
    }
}

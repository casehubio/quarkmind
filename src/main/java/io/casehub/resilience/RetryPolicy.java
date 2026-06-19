package io.casehub.resilience;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/**
 * Configurable retry policy for TaskDefinition contributions and Task executions.
 * Supports three backoff strategies (FIXED, EXPONENTIAL, EXPONENTIAL_WITH_JITTER) and
 * provides convenience factories ({@link #none()}, {@link #defaults()}, {@link #of}) as
 * well as a {@link RetryPolicyBuilder} for custom configuration. Retry attempts consume
 * the parent's timeout budget and never extend past the {@code PropagationContext} deadline.
 *
 * @see RetryState
 * @see DeadLetterQueue
 */
public class RetryPolicy {
    private int maxAttempts;
    private BackoffStrategy backoffStrategy;
    private Duration maxRetryDuration;
    private Set<String> retryableErrorCodes;
    private Set<String> nonRetryableErrorCodes;
    private Duration initialDelay;
    private Duration maxBackoff;
    private double jitterFactor;

    /**
     * Backoff strategies for spacing retry attempts. EXPONENTIAL_WITH_JITTER is the
     * recommended default to prevent thundering-herd effects across concurrent retries.
     */
    public enum BackoffStrategy {
        FIXED,
        EXPONENTIAL,
        EXPONENTIAL_WITH_JITTER
    }

    private RetryPolicy() {
        this.retryableErrorCodes = new HashSet<>();
        this.nonRetryableErrorCodes = new HashSet<>();
    }

    public static RetryPolicy none() {
        RetryPolicy policy = new RetryPolicy();
        policy.maxAttempts = 0;
        policy.backoffStrategy = BackoffStrategy.FIXED;
        policy.initialDelay = Duration.ZERO;
        policy.maxBackoff = Duration.ZERO;
        policy.maxRetryDuration = Duration.ZERO;
        policy.jitterFactor = 0.0;
        return policy;
    }

    public static RetryPolicy defaults() {
        RetryPolicy policy = new RetryPolicy();
        policy.maxAttempts = 3;
        policy.backoffStrategy = BackoffStrategy.EXPONENTIAL_WITH_JITTER;
        policy.initialDelay = Duration.ofSeconds(1);
        policy.maxBackoff = Duration.ofSeconds(30);
        policy.maxRetryDuration = Duration.ofMinutes(2);
        policy.jitterFactor = 0.1;
        return policy;
    }

    public static RetryPolicy of(int maxAttempts, BackoffStrategy strategy) {
        RetryPolicy policy = defaults();
        policy.maxAttempts = maxAttempts;
        policy.backoffStrategy = strategy;
        return policy;
    }

    public Duration computeDelay(int attemptNumber) {
        return switch (backoffStrategy) {
            case FIXED -> initialDelay;
            case EXPONENTIAL -> {
                long delayMs = initialDelay.toMillis() * (long) Math.pow(2, attemptNumber - 1);
                yield Duration.ofMillis(Math.min(delayMs, maxBackoff.toMillis()));
            }
            case EXPONENTIAL_WITH_JITTER -> {
                long baseMs = initialDelay.toMillis() * (long) Math.pow(2, attemptNumber - 1);
                long cappedMs = Math.min(baseMs, maxBackoff.toMillis());
                long jitterMs = (long) (cappedMs * jitterFactor * Math.random());
                yield Duration.ofMillis(cappedMs + jitterMs);
            }
        };
    }

    public boolean shouldRetry(int attemptNumber, String errorCode) {
        if (attemptNumber >= maxAttempts) return false;
        if (!nonRetryableErrorCodes.isEmpty() && nonRetryableErrorCodes.contains(errorCode)) return false;
        if (!retryableErrorCodes.isEmpty() && !retryableErrorCodes.contains(errorCode)) return false;
        return true;
    }

    public static RetryPolicyBuilder builder() {
        return new RetryPolicyBuilder();
    }

    // Getters
    public int getMaxAttempts() { return maxAttempts; }
    public BackoffStrategy getBackoffStrategy() { return backoffStrategy; }
    public Duration getMaxRetryDuration() { return maxRetryDuration; }
    public Set<String> getRetryableErrorCodes() { return retryableErrorCodes; }
    public Set<String> getNonRetryableErrorCodes() { return nonRetryableErrorCodes; }
    public Duration getInitialDelay() { return initialDelay; }
    public Duration getMaxBackoff() { return maxBackoff; }
    public double getJitterFactor() { return jitterFactor; }

    /**
     * Fluent builder for constructing a {@link RetryPolicy} with custom parameters.
     * Starts from the {@link RetryPolicy#defaults()} configuration.
     */
    public static class RetryPolicyBuilder {
        private final RetryPolicy policy = RetryPolicy.defaults();

        public RetryPolicyBuilder maxAttempts(int maxAttempts) {
            policy.maxAttempts = maxAttempts;
            return this;
        }

        public RetryPolicyBuilder backoffStrategy(BackoffStrategy strategy) {
            policy.backoffStrategy = strategy;
            return this;
        }

        public RetryPolicyBuilder initialDelay(Duration delay) {
            policy.initialDelay = delay;
            return this;
        }

        public RetryPolicyBuilder maxBackoff(Duration maxBackoff) {
            policy.maxBackoff = maxBackoff;
            return this;
        }

        public RetryPolicyBuilder maxRetryDuration(Duration duration) {
            policy.maxRetryDuration = duration;
            return this;
        }

        public RetryPolicyBuilder jitterFactor(double factor) {
            policy.jitterFactor = factor;
            return this;
        }

        public RetryPolicyBuilder retryableErrorCodes(Set<String> codes) {
            policy.retryableErrorCodes = new HashSet<>(codes);
            return this;
        }

        public RetryPolicyBuilder nonRetryableErrorCodes(Set<String> codes) {
            policy.nonRetryableErrorCodes = new HashSet<>(codes);
            return this;
        }

        public RetryPolicy build() {
            return policy;
        }
    }
}

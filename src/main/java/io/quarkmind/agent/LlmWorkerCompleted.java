package io.quarkmind.agent;

/**
 * CDI event fired when any LLM Worker completes (advisory or commentary).
 *
 * <p>Carries only the genuinely shared fields across all LLM worker types.
 * Domain-specific observers continue to observe their domain events
 * ({@link AdvisoryCompleted}, {@code CommentaryCompleted}).
 *
 * <p>Observed by {@link LlmWorkerLatencyRecorder} for latency trust scoring.
 *
 * <p>Refs #181
 *
 * @param workerId    agent identifier (e.g., "claude:crisis-aggressive@v1", "claude:commentator-energetic@v1")
 * @param capability  capability name (e.g., "advisory-crisis", "commentary-reactive")
 * @param gameFrame   game frame when the worker completed
 * @param latencyMs   LLM call latency in milliseconds
 */
public record LlmWorkerCompleted(
    String workerId,
    String capability,
    long gameFrame,
    long latencyMs
) {}

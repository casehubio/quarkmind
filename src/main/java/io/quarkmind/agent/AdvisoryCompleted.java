package io.quarkmind.agent;

/**
 * CDI event fired when an advisory Worker completes.
 *
 * <p>Observed by {@link AdvisoryCompletionObserver}, which records the invocation
 * and makes the event available to downstream consumers (trust scoring, channel publishing).
 *
 * <p>Refs #180
 *
 * @param advisorId          agent identifier (e.g., "claude:crisis-aggressive@v1")
 * @param capability         capability name (e.g., "advisory-crisis")
 * @param gameFrame          game frame when the advisory completed
 * @param recommendation     LLM-generated recommendation text
 * @param confidence         confidence score (0.0 to 1.0)
 * @param latencyMs          LLM call latency in milliseconds
 * @param gameStateSnapshot  game state metrics at advisory time (minerals, supply, army)
 */
public record AdvisoryCompleted(
    String advisorId,
    String capability,
    long gameFrame,
    String recommendation,
    double confidence,
    long latencyMs,
    java.util.Map<String, Double> gameStateSnapshot
) {}

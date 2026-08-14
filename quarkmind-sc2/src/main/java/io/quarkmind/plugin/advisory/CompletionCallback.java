package io.quarkmind.plugin.advisory;

import java.util.Map;

/**
 * Callback invoked when an advisory Worker completes successfully.
 *
 * <p>Passed to {@link AdvisoryWorkerFactory#createWorkers} to be notified of
 * advisory completion events. Used by {@link io.quarkmind.agent.QuarkMindCaseHub}
 * to fire {@link io.quarkmind.agent.AdvisoryCompleted} CDI events.
 *
 * <p>Refs #180
 */
@FunctionalInterface
public interface CompletionCallback {
    /**
     * Called after an advisory Worker successfully completes.
     *
     * @param advisorId          agent identifier (e.g., "claude:crisis-aggressive@v1")
     * @param capability         capability name (e.g., "advisory-crisis")
     * @param gameFrame          game frame when the advisory completed
     * @param recommendation     LLM-generated recommendation text
     * @param confidence         confidence score (0.0 to 1.0)
     * @param latencyMs          LLM call latency in milliseconds
     * @param gameStateSnapshot  game state metrics at advisory time (minerals, supply, army)
     */
    void onCompleted(String advisorId, String capability, long gameFrame,
                    String recommendation, double confidence, long latencyMs,
                    Map<String, Double> gameStateSnapshot);
}

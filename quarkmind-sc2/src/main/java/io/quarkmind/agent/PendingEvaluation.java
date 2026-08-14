package io.quarkmind.agent;

import java.util.Map;

/**
 * Pending advisory evaluation — holds advisory metadata and game state snapshot
 * for deferred outcome assessment.
 *
 * <p>Created by {@link DeferredAdvisoryEvaluator} when {@link AdvisoryCompleted}
 * fires. Evaluated when frame delta >= {@code EVALUATION_DELAY_FRAMES}.
 *
 * <p>Refs #180
 *
 * @param advisorId          agent identifier (e.g., "claude:crisis-aggressive@v1")
 * @param capability         capability name (e.g., "advisory-crisis")
 * @param advisoryFrame      game frame when the advisory was completed
 * @param recommendation     LLM-generated recommendation text
 * @param confidence         advisory confidence score (0.0 to 1.0)
 * @param gameStateSnapshot  game state metrics at advisory time (minerals, supply, army)
 */
public record PendingEvaluation(
    String advisorId,
    String capability,
    long advisoryFrame,
    String recommendation,
    double confidence,
    Map<String, Double> gameStateSnapshot
) {}

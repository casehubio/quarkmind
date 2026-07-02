package io.quarkmind.plugin.drools;

/**
 * Advisory fact for Drools strategy rules — simplified view of advisory output.
 *
 * <p>Fed to {@link StrategyRuleUnit#getAdvisoryStore()} from
 * {@link io.quarkmind.plugin.DroolsStrategyTask}.
 *
 * <p>Refs #180
 *
 * @param role           advisory role (e.g., "crisis", "economic", "strategic")
 * @param recommendation LLM-generated recommendation text
 * @param confidence     confidence score (0.0 to 1.0)
 * @param agentId        agent identifier (e.g., "claude:crisis-aggressive@v1")
 * @param ageFrames      how many frames old this advisory is
 */
public record AdvisoryFact(
    String role,
    String recommendation,
    double confidence,
    String agentId,
    long ageFrames
) {}

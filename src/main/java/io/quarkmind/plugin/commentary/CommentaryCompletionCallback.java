package io.quarkmind.plugin.commentary;

/**
 * Callback invoked when a commentary Worker completes successfully.
 *
 * <p>Passed to commentary Worker factory to be notified of commentary completion events.
 * Used by {@link io.quarkmind.agent.QuarkMindCaseHub} to fire {@link CommentaryCompleted}
 * CDI events.
 *
 * <p>Refs #181
 */
@FunctionalInterface
public interface CommentaryCompletionCallback {
    /**
     * Called after a commentary Worker successfully completes.
     *
     * @param workerId         agent identifier (e.g., "claude:commentator-energetic@v1")
     * @param capability       capability name (e.g., "commentary-reactive")
     * @param gameFrame        game frame when the commentary completed
     * @param text             LLM-generated commentary text
     * @param commentaryType   type of commentary (REACTIVE or NARRATIVE)
     * @param latencyMs        LLM call latency in milliseconds
     */
    void onCompleted(String workerId, String capability, long gameFrame,
                    String text, CommentaryType commentaryType, long latencyMs);
}

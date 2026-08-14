package io.quarkmind.plugin.commentary;

/**
 * CDI event fired when a commentary Worker completes successfully.
 *
 * <p>Observed by {@link CommentaryChannelBroker} for audit trail dispatch.
 *
 * <p>Refs #181
 */
public record CommentaryCompleted(
    String workerId,
    String capability,
    long gameFrame,
    String text,
    CommentaryType commentaryType,
    long latencyMs
) {
}

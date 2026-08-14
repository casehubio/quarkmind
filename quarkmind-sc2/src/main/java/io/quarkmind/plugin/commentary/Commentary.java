package io.quarkmind.plugin.commentary;

/**
 * Commentary output record.
 *
 * <p>Refs #181
 */
public record Commentary(String text, long gameFrame, CommentaryType type) {
}

package io.quarkmind.plugin.commentary;

/**
 * Commentary type — reactive (immediate) or narrative (contextual).
 *
 * <p>Refs #181
 */
public enum CommentaryType {
    /** Reactive commentary — immediate reactions to dramatic moments. */
    REACTIVE,

    /** Narrative commentary — periodic strategic summaries with temporal context. */
    NARRATIVE
}

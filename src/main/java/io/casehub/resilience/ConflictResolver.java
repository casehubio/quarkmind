package io.casehub.resilience;

import io.casehub.core.CaseFileItem;

/**
 * Strategy interface for resolving concurrent writes to the same CaseFile key by
 * different KnowledgeSources (potentially on different instances in distributed mode).
 * Implementations receive the contested key, the existing versioned value, and the
 * incoming value, and must return the winning result.
 *
 * @see Strategy
 */
public interface ConflictResolver {
    CaseFileItem resolve(String key, CaseFileItem existing, CaseFileItem incoming);

    /** Built-in conflict resolution strategies. LAST_WRITER_WINS is the default. */
    enum Strategy {
        LAST_WRITER_WINS,
        FIRST_WRITER_WINS,
        MERGE,
        FAIL
    }
}

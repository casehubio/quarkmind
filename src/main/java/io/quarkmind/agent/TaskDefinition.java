package io.quarkmind.agent;

import io.casehub.api.context.CaseContext;

import java.util.Set;
import java.util.function.Predicate;

/**
 * QuarkMind's plugin contract for casehub-engine API.
 *
 * <p>Replaces the poc's {@code io.casehub.core.TaskDefinition} in plugin implementations.
 * Seam interfaces extend both this and the poc's {@code TaskDefinition} during Phase 1
 * (bridge period); the poc interface is dropped entirely in Phase 2.
 *
 * <p>Refs #193
 */
public interface TaskDefinition {

    String getId();

    String getName();

    /** Entry keys that must be present in the context before this plugin activates. */
    default Set<String> requires() { return Set.of(); }

    /** Additional activation gate beyond key presence. Evaluated after {@link #requires()}. */
    default Predicate<CaseContext> activateIf() { return ctx -> true; }

    void execute(CaseContext ctx);

    /** Keys this plugin writes to the context. Documentation only — not enforced. */
    default Set<String> produces() { return Set.of(); }
}

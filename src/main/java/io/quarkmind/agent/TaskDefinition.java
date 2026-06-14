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

    /**
     * Phase 1 bridge helper — evaluates the full activation contract against a {@link CaseContext}.
     *
     * <p>Mirrors what Phase 2's SequenceWorker will do: {@link #requires()} key-presence check
     * first, then {@link #activateIf()} extra gates. The {@code canActivate(CaseFile)} bridge in
     * each plugin delegates here so that the Phase 1 poc dispatch path is semantically equivalent
     * to the Phase 2 path.
     */
    default boolean testActivation(CaseContext ctx) {
        return requires().stream().allMatch(ctx::contains) && activateIf().test(ctx);
    }
}

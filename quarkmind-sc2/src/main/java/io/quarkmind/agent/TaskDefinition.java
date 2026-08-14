package io.quarkmind.agent;

import io.casehub.api.context.CaseContext;

import java.util.Set;
import java.util.function.Predicate;

/**
 * QuarkMind's plugin contract for casehub-engine API.
 *
 * <p>Each plugin seam interface ({@code StrategyTask}, {@code EconomicsTask}, etc.) extends
 * this interface. Implementations provide {@link #execute(CaseContext)}, activation gates,
 * and key declarations.
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
     * Evaluates the full activation contract against a {@link CaseContext}.
     *
     * <p>Checks {@link #requires()} key-presence first, then {@link #activateIf()} extra gates.
     * Used by PluginDispatchBroker for pre-engine activation evaluation.
     */
    default boolean testActivation(CaseContext ctx) {
        return requires().stream().allMatch(ctx::contains) && activateIf().test(ctx);
    }
}

package io.quarkmind.agent.plugin;

/**
 * Plugin seam for summarisation lifecycle ticking.
 *
 * <p>Implemented by {@link io.quarkmind.plugin.summarisation.SummarisationLifecycle}.
 * Injected by {@link io.quarkmind.agent.GameTickExecutor} to avoid direct dependency
 * on concrete summarisation plugin classes.
 *
 * <p>Refs #182
 */
public interface SummarisationTickable {
    void tick(long gameFrame);
}

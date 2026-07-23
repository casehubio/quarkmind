package io.quarkmind.agent;

import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Factory for the tick-orchestrator {@link WorkerFunction.Sync} that chains plugin execution
 * within a single game tick.
 *
 * <p>Execution order: scouting → strategy → tactics → economics (or whatever order the
 * caller provides). Each plugin is a {@link TaskDefinition} whose activation is gated by
 * {@link TaskDefinition#testActivation(io.casehub.api.context.CaseContext)} — the combined
 * check of {@code requires()} key-presence and {@code activateIf()} predicate.
 *
 * <h3>Execution model</h3>
 *
 * <p>The returned {@code WorkerFunction.Sync} receives a {@code Map<String,Object>} — the
 * flattened game state from the CaseContext working panel, as provided by the engine's
 * {@code SyncAgentWorkerFunctionHandler}. The function:
 * <ol>
 *   <li>Wraps the input map in a {@link MutableMapCaseContext}</li>
 *   <li>For each plugin: checks {@link TaskDefinition#testActivation}; if true, calls
 *       {@link TaskDefinition#execute}</li>
 *   <li>Returns {@code WorkerResult.of(mutations)} — only the keys written by plugins</li>
 * </ol>
 *
 * <p>Plugins read game state and write agent state to the same mutable context. A later plugin
 * sees keys set by an earlier plugin (e.g. scouting writes {@code game.enemy.army_size},
 * strategy reads it).
 *
 * <h3>Error handling</h3>
 *
 * <p>If any plugin throws, the chain stops and the function returns {@code WorkerResult.failed()}.
 * Partial mutations from earlier plugins are included in the failed result's output.
 *
 * <p>Refs #207
 */
public final class TickOrchestratorWorker {

    private static final Logger log = Logger.getLogger(TickOrchestratorWorker.class);

    private TickOrchestratorWorker() {} // static factory only

    /**
     * Creates a {@link WorkerFunction.Sync} that chains the given plugins in order.
     *
     * @param plugins ordered list of plugins to execute per tick
     * @return a sync function suitable for {@code Worker.builder().function(...)}
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static WorkerFunction.Sync createFunction(List<TaskDefinition> plugins) {
        List<TaskDefinition> chain = List.copyOf(plugins); // defensive copy
        return new WorkerFunction.Sync<>(Map.class, Map.class, (input, scope) -> executeChain(chain, input));
    }

    private static WorkerResult executeChain(List<TaskDefinition> chain, Map<String, Object> input) {
        MutableMapCaseContext ctx = new MutableMapCaseContext(input);

        for (TaskDefinition plugin : chain) {
            if (!plugin.testActivation(ctx)) {
                log.debugf("[TICK] Skipped %s — activation check failed", plugin.getId());
                continue;
            }

            try {
                log.debugf("[TICK] Executing %s", plugin.getId());
                plugin.execute(ctx);
            } catch (Exception e) {
                log.errorf(e, "[TICK] Plugin %s failed: %s", plugin.getId(), e.getMessage());
                return WorkerResult.failed(
                    "Plugin " + plugin.getId() + " failed: " + e.getMessage(),
                    ctx.mutations());
            }
        }

        return WorkerResult.of(ctx.mutations());
    }
}

package io.quarkmind.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.context.CaseContext;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerOutcome;
import io.casehub.worker.api.WorkerResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import io.quarkmind.agency.task.TaskDefinition;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link TickOrchestratorWorker}.
 *
 * <p>Verifies plugin chaining, activation gating, key propagation, and skip semantics.
 * Uses stub TaskDefinitions — no CDI.
 *
 * <p>Refs #207
 */
class TickOrchestratorWorkerTest {

    @Test
    void plugins_executeInOrder() {
        List<String> executionOrder = Collections.synchronizedList(new ArrayList<>());

        List<TaskDefinition> plugins = List.of(
            recordingPlugin("scouting", executionOrder),
            recordingPlugin("strategy", executionOrder),
            recordingPlugin("tactics", executionOrder),
            recordingPlugin("economics", executionOrder)
                                              );

        WorkerFunction.Sync fn = TickOrchestratorWorker.createFunction(plugins);
        WorkerResult result = (WorkerResult) fn.fn().apply(Map.of("game.frame", 1), null);

        assertThat(executionOrder).containsExactly("scouting", "strategy", "tactics", "economics");
        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Success.class);
    }

    @Test
    void plugin_skippedWhenActivateIfReturnsFalse() {
        List<String> executionOrder = Collections.synchronizedList(new ArrayList<>());

        List<TaskDefinition> plugins = List.of(
            recordingPlugin("scouting", executionOrder),
            inactivePlugin("strategy", executionOrder),
            recordingPlugin("tactics", executionOrder),
            recordingPlugin("economics", executionOrder)
        );

        WorkerFunction.Sync fn = TickOrchestratorWorker.createFunction(plugins);
        fn.fn().apply(Map.of("game.frame", 1), null);

        assertThat(executionOrder).containsExactly("scouting", "tactics", "economics");
    }

    @Test
    void plugin_skippedWhenRequiredKeysAbsent() {
        List<String> executionOrder = Collections.synchronizedList(new ArrayList<>());

        TaskDefinition needsEnemyData = new StubTaskDefinition("strategy", "Strategy") {
            @Override public Set<String> requires() { return Set.of("game.enemy.army_size"); }
            @Override public void execute(CaseContext ctx) { executionOrder.add("strategy"); }
        };

        List<TaskDefinition> plugins = List.of(
            recordingPlugin("scouting", executionOrder),
            needsEnemyData,
            recordingPlugin("tactics", executionOrder),
            recordingPlugin("economics", executionOrder)
        );

        WorkerFunction.Sync fn = TickOrchestratorWorker.createFunction(plugins);
        // Input does NOT contain "game.enemy.army_size"
        fn.fn().apply(Map.of("game.frame", 1), null);

        assertThat(executionOrder).containsExactly("scouting", "tactics", "economics");
    }

    @Test
    void plugin_executesWhenRequiredKeysPresent() {
        List<String> executionOrder = Collections.synchronizedList(new ArrayList<>());

        TaskDefinition needsEnemyData = new StubTaskDefinition("strategy", "Strategy") {
            @Override public Set<String> requires() { return Set.of("game.enemy.army_size"); }
            @Override public void execute(CaseContext ctx) { executionOrder.add("strategy"); }
        };

        List<TaskDefinition> plugins = List.of(
            recordingPlugin("scouting", executionOrder),
            needsEnemyData,
            recordingPlugin("tactics", executionOrder)
        );

        WorkerFunction.Sync fn = TickOrchestratorWorker.createFunction(plugins);
        fn.fn().apply(Map.of("game.frame", 1, "game.enemy.army_size", 5), null);

        assertThat(executionOrder).containsExactly("scouting", "strategy", "tactics");
    }

    @Test
    void pluginWrites_propagateToSubsequentPlugins() {
        // Scouting writes enemy data; strategy reads it
        TaskDefinition scouting = new StubTaskDefinition("scouting", "Scouting") {
            @Override
            public void execute(CaseContext ctx) {
                ctx.set("game.enemy.army_size", 10);
            }
        };

        TaskDefinition strategy = new StubTaskDefinition("strategy", "Strategy") {
            @Override public Set<String> requires() { return Set.of("game.enemy.army_size"); }
            @Override
            public void execute(CaseContext ctx) {
                int enemySize = ctx.getOrDefault("game.enemy.army_size", 0);
                ctx.set("agent.strategy", enemySize > 5 ? "DEFEND" : "ATTACK");
            }
        };

        List<TaskDefinition> plugins = List.of(scouting, strategy);

        WorkerFunction.Sync fn = TickOrchestratorWorker.createFunction(plugins);
        WorkerResult result = (WorkerResult) fn.fn().apply(Map.of("game.frame", 1), null);

        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Success.class);
        assertThat((Map<String, Object>) result.output()).containsEntry("game.enemy.army_size", 10);
        assertThat((Map<String, Object>) result.output()).containsEntry("agent.strategy", "DEFEND");
    }

    @Test
    void pluginWrites_includedInOutputMap() {
        TaskDefinition writer = new StubTaskDefinition("scouting", "Scouting") {
            @Override
            public void execute(CaseContext ctx) {
                ctx.set("agent.scouting.result", "enemy_rush");
                ctx.set("agent.scouting.confidence", 0.85);
            }
        };

        WorkerFunction.Sync fn = TickOrchestratorWorker.createFunction(List.of(writer));
        WorkerResult result = (WorkerResult) fn.fn().apply(Map.of("game.frame", 1), null);

        assertThat((Map<String, Object>) result.output())
            .containsEntry("agent.scouting.result", "enemy_rush")
            .containsEntry("agent.scouting.confidence", 0.85);
    }

    @Test
    void emptyPluginList_returnsEmptySuccess() {
        WorkerFunction.Sync fn = TickOrchestratorWorker.createFunction(List.of());
        WorkerResult result = (WorkerResult) fn.fn().apply(Map.of("game.frame", 1), null);

        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Success.class);
        assertThat((Map<String, Object>) result.output()).isEmpty();
    }

    @Test
    void pluginException_returnsFailed() {
        TaskDefinition failing = new StubTaskDefinition("scouting", "Scouting") {
            @Override
            public void execute(CaseContext ctx) {
                throw new RuntimeException("rule engine exploded");
            }
        };

        List<String> executionOrder = Collections.synchronizedList(new ArrayList<>());
        List<TaskDefinition> plugins = List.of(
            failing,
            recordingPlugin("strategy", executionOrder)
        );

        WorkerFunction.Sync fn = TickOrchestratorWorker.createFunction(plugins);
        WorkerResult result = (WorkerResult) fn.fn().apply(Map.of("game.frame", 1), null);

        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Failed.class);
        // Strategy should NOT execute after scouting failure
        assertThat(executionOrder).isEmpty();
    }

    @Test
    void pluginReadsInputState() {
        List<Object> captured = new ArrayList<>();

        TaskDefinition reader = new StubTaskDefinition("scouting", "Scouting") {
            @Override
            public void execute(CaseContext ctx) {
                captured.add(ctx.getAs("game.frame", Integer.class));
                captured.add(ctx.getAs("game.resources.minerals", Integer.class));
            }
        };

        WorkerFunction.Sync fn = TickOrchestratorWorker.createFunction(List.of(reader));
        fn.fn().apply(Map.of("game.frame", 42, "game.resources.minerals", 350), null);

        assertThat(captured).containsExactly(42, 350);
    }

    @Test
    void allPluginsInactive_returnsEmptySuccess() {
        List<TaskDefinition> plugins = List.of(
            inactivePlugin("scouting", new ArrayList<>()),
            inactivePlugin("strategy", new ArrayList<>()),
            inactivePlugin("tactics", new ArrayList<>())
        );

        WorkerFunction.Sync fn = TickOrchestratorWorker.createFunction(plugins);
        WorkerResult result = (WorkerResult) fn.fn().apply(Map.of("game.frame", 1), null);

        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Success.class);
        assertThat((Map<String, Object>) result.output()).isEmpty();
    }

    @Test
    void pluginWritesOverwrite_existingInput() {
        TaskDefinition writer = new StubTaskDefinition("strategy", "Strategy") {
            @Override
            public void execute(CaseContext ctx) {
                // Overwrite an input key — this simulates agent state replacing game state
                ctx.set("game.frame", 999);
            }
        };

        WorkerFunction.Sync fn = TickOrchestratorWorker.createFunction(List.of(writer));
        WorkerResult result = (WorkerResult) fn.fn().apply(Map.of("game.frame", 1), null);

        // The output should contain only mutations
        assertThat((Map<String, Object>) result.output()).containsEntry("game.frame", 999);
    }

    // ------------------------------------------------------------------
    // Test helpers
    // ------------------------------------------------------------------

    private static TaskDefinition recordingPlugin(String id, List<String> log) {
        return new StubTaskDefinition(id, id) {
            @Override
            public void execute(CaseContext ctx) {
                log.add(id);
            }
        };
    }

    private static TaskDefinition inactivePlugin(String id, List<String> log) {
        return new StubTaskDefinition(id, id) {
            @Override
            public Predicate<CaseContext> activateIf() {
                return ctx -> false;
            }

            @Override
            public void execute(CaseContext ctx) {
                log.add(id);
            }
        };
    }

    private static class StubTaskDefinition implements TaskDefinition {
        private final String id;
        private final String name;

        StubTaskDefinition(String id, String name) {
            this.id = id;
            this.name = name;
        }

        @Override public String getId() { return id; }
        @Override public String getName() { return name; }
        @Override public void execute(CaseContext ctx) { /* no-op */ }
    }
}

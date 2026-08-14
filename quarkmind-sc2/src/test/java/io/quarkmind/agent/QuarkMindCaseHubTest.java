package io.quarkmind.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.context.CaseContext;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerOutcome;
import io.casehub.worker.api.WorkerResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import io.quarkmind.agency.task.TaskDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link QuarkMindCaseHub#getDefinition()}.
 *
 * <p>Verifies the programmatic CaseDefinition is correctly assembled from
 * discovered TaskDefinition implementations. Uses stub TaskDefinitions — no CDI.
 *
 * <p>Refs #207
 */
class QuarkMindCaseHubTest {

    private QuarkMindCaseHub hub;

    @BeforeEach
    void setUp() {
        List<TaskDefinition> plugins = List.of(
            stubPlugin("scouting.drools-cep", "Drools CEP Scouting", false),
            stubPlugin("trust-routing", "Strategy Trust Router", false),
            stubPlugin("strategy.drools", "Drools Strategy", true),
            stubPlugin("strategy.early-pressure", "Early Pressure Strategy", true),
            stubPlugin("strategy.economic-expansion", "Economic Expansion Strategy", true),
            stubPlugin("tactics.drools-goap", "Drools GOAP Tactics", false),
            stubPlugin("economics.flow", "Flow Economics", false),
            stubPlugin("summarisation.moment-detection", "Moment Detection", false)
                                              );

        hub = new QuarkMindCaseHub(plugins);
    }

    @Test
    void definition_hasCorrectIdentity() {
        CaseDefinition def = hub.getDefinition();

        assertThat(def.getNamespace()).isEqualTo("quarkmind");
        assertThat(def.getName()).isEqualTo("starcraft-game");
        assertThat(def.getVersion()).isEqualTo("1.0");
    }

    @Test
    void definition_hasTickDecisionCapability() {
        CaseDefinition def = hub.getDefinition();

        assertThat(def.getCapabilities())
            .extracting(Capability::name)
            .contains("tick-decision");
    }

    @Test
    void definition_hasStrategyCapability() {
        CaseDefinition def = hub.getDefinition();

        assertThat(def.getCapabilities())
            .extracting(Capability::name)
            .contains("strategy");
    }

    @Test
    void definition_hasTickOrchestratorWorker() {
        CaseDefinition def = hub.getDefinition();

        assertThat(def.getWorkers())
            .extracting(Worker::name)
            .contains("tick-orchestrator");

        Worker tickOrchestrator = def.getWorkers().stream()
            .filter(w -> w.name().equals("tick-orchestrator"))
            .findFirst().orElseThrow();
        assertThat(tickOrchestrator.capabilityNames()).containsExactly("tick-decision");
    }

    @Test
    void definition_strategyWorkersDeferred() {
        // Strategy workers are deferred to Phase 2 when ImplementationRoutingStrategy is wired.
        // For now, strategy plugins participate via the tick orchestrator chain (activateIf gates).
        CaseDefinition def = hub.getDefinition();

        List<Worker> strategyWorkers = def.getWorkers().stream()
            .filter(w -> w.capabilityNames().contains("strategy"))
            .toList();

        assertThat(strategyWorkers).isEmpty();
    }

    @Test
    void definition_hasTickDecisionBinding() {
        CaseDefinition def = hub.getDefinition();

        assertThat(def.getBindings()).hasSize(1);

        Binding binding = def.getBindings().get(0);
        assertThat(binding.getName()).isEqualTo("tick-decision");
        assertThat(binding.getOn()).isInstanceOf(ContextChangeTrigger.class);
    }

    @Test
    void definition_tickDecisionBindingTargetsTickDecisionCapability() {
        CaseDefinition def = hub.getDefinition();

        Binding binding = def.getBindings().get(0);
        Capability tickDecision = def.getCapabilities().stream()
            .filter(c -> c.name().equals("tick-decision"))
            .findFirst().orElseThrow();

        // The binding's target wraps the tick-decision capability
        assertThat(binding.target())
            .isInstanceOf(io.casehub.api.model.CapabilityTarget.class);
        io.casehub.api.model.CapabilityTarget target =
            (io.casehub.api.model.CapabilityTarget) binding.target();
        assertThat(target.capability()).isSameAs(tickDecision);
    }

    @Test
    void definition_totalWorkerCount() {
        CaseDefinition def = hub.getDefinition();

        // 1 tick-orchestrator only — strategy workers deferred to Phase 2
        assertThat(def.getWorkers()).hasSize(1);
    }

    @Test
    void tickOrchestratorFunction_isNotPlaceholder() {
        CaseDefinition def = hub.getDefinition();

        Worker tickOrchestrator = def.getWorkers().stream()
            .filter(w -> w.name().equals("tick-orchestrator"))
            .findFirst().orElseThrow();

        // The function should be a Sync that actually chains plugins
        assertThat(tickOrchestrator.function()).isInstanceOf(WorkerFunction.Sync.class);

        WorkerFunction.Sync fn = (WorkerFunction.Sync) tickOrchestrator.function();
        WorkerResult result = (WorkerResult) fn.fn().apply(Map.of("game.frame", 1), null);

        // Stub plugins are no-ops, but the function should still return Success
        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Success.class);
    }

    @Test
    void resolveTickChain_ordersPluginsByPhase() {
        List<TaskDefinition> chain = hub.resolveTickChain();

        List<String> ids = chain.stream().map(TaskDefinition::getId).toList();

        // Scouting comes before strategy, strategy before tactics, tactics before economics
        assertThat(ids.indexOf("scouting.drools-cep"))
            .isLessThan(ids.indexOf("strategy.drools"));
        assertThat(ids.indexOf("strategy.drools"))
            .isLessThan(ids.indexOf("tactics.drools-goap"));
        assertThat(ids.indexOf("tactics.drools-goap"))
            .isLessThan(ids.indexOf("economics.flow"));
    }

    @Test
    void resolveTickChain_includesAllPlugins() {
        List<TaskDefinition> chain = hub.resolveTickChain();

        assertThat(chain).hasSize(8); // all 8 plugins participate
    }

    @Test
    void tickOrchestratorFunction_executesPluginsInOrder() {
        List<String> executionOrder = Collections.synchronizedList(new ArrayList<>());

        List<TaskDefinition> recordingPlugins = List.of(
            recordingPlugin("scouting.test", "Scouting", executionOrder),
            recordingPlugin("strategy.test", "Strategy", executionOrder),
            recordingPlugin("tactics.test", "Tactics", executionOrder),
            recordingPlugin("economics.test", "Economics", executionOrder)
        );

        QuarkMindCaseHub testHub = new QuarkMindCaseHub(recordingPlugins);
        CaseDefinition def = testHub.getDefinition();

        Worker tickOrchestrator = def.getWorkers().stream()
            .filter(w -> w.name().equals("tick-orchestrator"))
            .findFirst().orElseThrow();

        WorkerFunction.Sync fn = (WorkerFunction.Sync) tickOrchestrator.function();
        fn.fn().apply(Map.of("game.frame", 1), null);

        assertThat(executionOrder)
            .containsExactly("scouting.test", "strategy.test", "tactics.test", "economics.test");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static TaskDefinition stubPlugin(String id, String name, boolean isStrategy) {
        return new TaskDefinition() {
            @Override public String getId() { return id; }
            @Override public String getName() { return name; }
            @Override public void execute(CaseContext ctx) { /* stub */ }
        };
    }

    private static TaskDefinition recordingPlugin(String id, String name, List<String> log) {
        return new TaskDefinition() {
            @Override public String getId() { return id; }
            @Override public String getName() { return name; }
            @Override public void execute(CaseContext ctx) { log.add(id); }
        };
    }
}

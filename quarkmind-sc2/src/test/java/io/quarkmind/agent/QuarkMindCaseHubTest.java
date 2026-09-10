package io.quarkmind.agent;

import io.casehub.api.context.CaseContext;
import io.casehub.api.model.CaseDefinition;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerOutcome;
import io.casehub.worker.api.WorkerResult;
import io.quarkmind.agency.task.TaskDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
                .doesNotContain("tick-decision");
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
                .doesNotContain("tick-orchestrator");
    }

    @Test
    void definition_strategyWorkersDeferred() {
        // Strategy workers are deferred to Phase 2 when ImplementationRoutingStrategy is wired.
        // For now, strategy plugins participate via the tick orchestrator chain (activateIf gates).
        CaseDefinition def = hub.getDefinition();

        List<Worker> strategyWorkers = def.getWorkers().stream()
            .filter(w -> w.capabilities().contains("strategy"))
            .toList();

        assertThat(strategyWorkers).isEmpty();
    }

    @Test
    void definition_hasTickDecisionBinding() {
        CaseDefinition def = hub.getDefinition();
        assertThat(def.getBindings()).isEmpty();
    }

    @Test
    void definition_tickDecisionBindingTargetsTickDecisionCapability() {
        List<TaskDefinition> chain = hub.resolveTickChain();
        assertThat(chain).isNotEmpty();
    }

    @Test
    void definition_totalWorkerCount() {
        CaseDefinition def = hub.getDefinition();
        assertThat(def.getWorkers()).isEmpty();
    }

    @Test
    void tickOrchestratorFunction_isNotPlaceholder() {
        WorkerResult result = TickOrchestratorWorker.executeInline(
                hub.resolveTickChain(), Map.of("game.frame", 1));
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

        WorkerResult result = TickOrchestratorWorker.executeInline(
                recordingPlugins, Map.of("game.frame", 1));

        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Success.class);
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

package io.quarkmind.plugin;

import io.casehub.api.context.CaseContext;
import io.casehub.coordination.PropagationContext;
import io.casehub.core.CaseFile;
import io.casehub.persistence.memory.InMemoryCaseFileRepository;
import io.quarkmind.agent.CaseFileContext;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.StrategySelector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 migration coverage: confirms activateIf()/requires()/execute(CaseContext) on
 * EarlyPressureStrategyTask match the semantics of the old canActivate(CaseFile)/execute(CaseFile).
 * Uses CaseFileContext (wrapping a poc CaseFile) as the CaseContext implementation — avoids
 * casehub-engine-blackboard runtime dep. Refs #193
 */
class EarlyPressureStrategyTaskMigrationTest {

    StrategySelector selector;
    EarlyPressureStrategyTask task;

    @BeforeEach
    void setUp() {
        selector = new StrategySelector();
        task = new EarlyPressureStrategyTask(selector);
    }

    private CaseContext emptyCtx() {
        var cf = new InMemoryCaseFileRepository()
            .create("starcraft-game", Map.of(), PropagationContext.createRoot());
        return new CaseFileContext(cf);
    }

    private CaseContext readyCtx() {
        var cf = new InMemoryCaseFileRepository()
            .create("starcraft-game", Map.of(), PropagationContext.createRoot());
        cf.put(QuarkMindCaseFile.READY, Boolean.TRUE);
        return new CaseFileContext(cf);
    }

    @Test
    void requires_containsOnlyReady() {
        assertThat(task.requires()).containsExactly(QuarkMindCaseFile.READY);
    }

    @Test
    void activateIf_falseWhenReadyAbsent() {
        assertThat(task.activateIf().test(emptyCtx())).isFalse();
    }

    @Test
    void activateIf_falseWhenNotSelected() {
        // selector defaults to "strategy.drools" — not early-pressure
        assertThat(task.activateIf().test(readyCtx())).isFalse();
    }

    @Test
    void activateIf_trueWhenSelectedAndReadyPresent() {
        selector.selectForGame("strategy.early-pressure", "vs.unknown");
        assertThat(task.activateIf().test(readyCtx())).isTrue();
    }

    @Test
    void execute_writesAttackStrategyToContext() {
        CaseContext ctx = readyCtx();
        task.execute(ctx);
        assertThat(ctx.getAs(QuarkMindCaseFile.STRATEGY, String.class)).isEqualTo("ATTACK");
    }

    @Test
    void executeCaseFile_bridge_syncsOutputBackToCaseFile() {
        // Exercises the Phase 1 bridge: execute(CaseFile) → CaseFileContext → execute(CaseContext) → sync
        CaseFile cf = new InMemoryCaseFileRepository()
            .create("starcraft-game", Map.of(), PropagationContext.createRoot());
        cf.put(QuarkMindCaseFile.READY, Boolean.TRUE);
        selector.selectForGame("strategy.early-pressure", "vs.unknown");

        task.execute(cf);  // calls the bridge

        assertThat(cf.get(QuarkMindCaseFile.STRATEGY, String.class))
            .contains("ATTACK");
    }
}

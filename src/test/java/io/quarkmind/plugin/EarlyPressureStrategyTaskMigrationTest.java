package io.quarkmind.plugin;

import io.casehub.api.context.CaseContext;
import io.quarkmind.agent.MapCaseContext;
import io.quarkmind.agent.MutableMapCaseContext;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.StrategySelector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Migration coverage: confirms activateIf()/requires()/execute(CaseContext) on
 * EarlyPressureStrategyTask. Uses MapCaseContext/MutableMapCaseContext — no database
 * or repository setup needed. Refs #193
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
        return new MapCaseContext(Map.of());
    }

    private CaseContext readyCtx() {
        return new MutableMapCaseContext(Map.of(
            QuarkMindCaseFile.READY, Boolean.TRUE));
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
}

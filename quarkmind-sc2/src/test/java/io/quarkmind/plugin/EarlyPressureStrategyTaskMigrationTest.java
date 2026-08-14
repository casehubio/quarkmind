package io.quarkmind.plugin;

import io.casehub.api.context.CaseContext;
import io.quarkmind.agency.context.MapCaseContext;
import io.quarkmind.agency.context.MutableMapCaseContext;
import io.quarkmind.agent.QuarkMindCaseFile;
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

    EarlyPressureStrategyTask task;

    @BeforeEach
    void setUp() {
        task = new EarlyPressureStrategyTask();
    }

    private CaseContext emptyCtx() {
        return new MapCaseContext(Map.of());
    }

    private CaseContext readyCtx() {
        return new MutableMapCaseContext(new java.util.HashMap<>(Map.of(
            QuarkMindCaseFile.READY, Boolean.TRUE)));
    }

    private CaseContext readyCtxWithStrategy(String strategyId) {
        return new MutableMapCaseContext(new java.util.HashMap<>(Map.of(
            QuarkMindCaseFile.READY, Boolean.TRUE,
            QuarkMindCaseFile.STRATEGY_SELECTED_ID, strategyId)));
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
        assertThat(task.activateIf().test(readyCtx())).isFalse();
    }

    @Test
    void activateIf_trueWhenSelectedAndReadyPresent() {
        assertThat(task.activateIf().test(readyCtxWithStrategy("strategy.early-pressure"))).isTrue();
    }

    @Test
    void execute_writesAttackStrategyToContext() {
        CaseContext ctx = readyCtx();
        task.execute(ctx);
        assertThat(ctx.getAs(QuarkMindCaseFile.STRATEGY, String.class)).isEqualTo("ATTACK");
    }
}

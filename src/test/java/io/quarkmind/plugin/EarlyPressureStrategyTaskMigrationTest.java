package io.quarkmind.plugin;

import io.casehub.engine.internal.context.CaseContextImpl;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.StrategySelector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 migration coverage: confirms activateIf()/requires()/execute(CaseContext) on
 * EarlyPressureStrategyTask match the semantics of the old canActivate(CaseFile)/execute(CaseFile).
 * Refs #193
 */
class EarlyPressureStrategyTaskMigrationTest {

    StrategySelector selector;
    EarlyPressureStrategyTask task;

    @BeforeEach
    void setUp() {
        selector = new StrategySelector();
        task = new EarlyPressureStrategyTask(selector);
    }

    @Test
    void requires_containsOnlyReady() {
        assertThat(task.requires()).containsExactly(QuarkMindCaseFile.READY);
    }

    @Test
    void activateIf_falseWhenReadyAbsent() {
        var ctx = new CaseContextImpl(Map.of());
        assertThat(task.activateIf().test(ctx)).isFalse();
    }

    @Test
    void activateIf_falseWhenNotSelected() {
        // selector defaults to "strategy.drools" — not early-pressure
        var ctx = new CaseContextImpl(Map.of(QuarkMindCaseFile.READY, Boolean.TRUE));
        assertThat(task.activateIf().test(ctx)).isFalse();
    }

    @Test
    void activateIf_trueWhenSelectedAndReadyPresent() {
        selector.selectForGame("strategy.early-pressure", "vs.unknown");
        var ctx = new CaseContextImpl(Map.of(QuarkMindCaseFile.READY, Boolean.TRUE));
        assertThat(task.activateIf().test(ctx)).isTrue();
    }

    @Test
    void execute_writesAttackStrategyToContext() {
        var ctx = new CaseContextImpl(Map.of(QuarkMindCaseFile.READY, Boolean.TRUE));
        task.execute(ctx);
        assertThat(ctx.getAs(QuarkMindCaseFile.STRATEGY, String.class)).isEqualTo("ATTACK");
    }
}

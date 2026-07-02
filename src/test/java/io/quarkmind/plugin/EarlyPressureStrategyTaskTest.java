package io.quarkmind.plugin;

import io.quarkmind.agent.MapCaseContext;
import io.quarkmind.agent.MutableMapCaseContext;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.StrategySelector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EarlyPressureStrategyTaskTest {

    StrategySelector selector;
    EarlyPressureStrategyTask task;

    @BeforeEach
    void setUp() {
        selector = new StrategySelector();
        task = new EarlyPressureStrategyTask(selector);
    }

    private MutableMapCaseContext readyContext() {
        return new MutableMapCaseContext(Map.of(
            QuarkMindCaseFile.READY, Boolean.TRUE));
    }

    @Test
    void getId_returnsEarlyPressureId() {
        assertThat(task.getId()).isEqualTo("strategy.early-pressure");
    }

    @Test
    void requires_containsOnlyReady() {
        assertThat(task.requires()).containsExactly(QuarkMindCaseFile.READY);
    }

    @Test
    void testActivation_falseWhenNotSelected() {
        // selector defaults to "strategy.drools" — not early-pressure
        assertThat(task.testActivation(readyContext())).isFalse();
    }

    @Test
    void testActivation_trueWhenSelectedAndReadyPresent() {
        selector.selectForGame("strategy.early-pressure", "vs.unknown");
        assertThat(task.testActivation(readyContext())).isTrue();
    }

    @Test
    void testActivation_falseWhenSelectedButReadyAbsent() {
        selector.selectForGame("strategy.early-pressure", "vs.unknown");
        var ctx = new MapCaseContext(Map.of());
        // READY not present
        assertThat(task.testActivation(ctx)).isFalse();
    }

    @Test
    void execute_writesAttackStrategy() {
        var ctx = readyContext();
        task.execute(ctx);
        assertThat(ctx.getAs(QuarkMindCaseFile.STRATEGY, String.class)).isEqualTo("ATTACK");
    }

    @Test
    void produces_containsStrategy() {
        assertThat(task.produces()).contains(QuarkMindCaseFile.STRATEGY);
    }
}

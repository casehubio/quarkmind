package io.quarkmind.plugin;

import io.quarkmind.agent.MapCaseContext;
import io.quarkmind.agent.MutableMapCaseContext;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.StrategySelector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EconomicExpansionStrategyTaskTest {

    StrategySelector selector;
    EconomicExpansionStrategyTask task;

    @BeforeEach
    void setUp() {
        selector = new StrategySelector();
        task = new EconomicExpansionStrategyTask(selector);
    }

    private MutableMapCaseContext readyContext() {
        return new MutableMapCaseContext(Map.of(
            QuarkMindCaseFile.READY, Boolean.TRUE));
    }

    @Test
    void getId_returnsEconomicExpansionId() {
        assertThat(task.getId()).isEqualTo("strategy.economic-expansion");
    }

    @Test
    void requires_containsOnlyReady() {
        assertThat(task.requires()).containsExactly(QuarkMindCaseFile.READY);
    }

    @Test
    void testActivation_falseWhenNotSelected() {
        assertThat(task.testActivation(readyContext())).isFalse();
    }

    @Test
    void testActivation_trueWhenSelectedAndReadyPresent() {
        selector.selectForGame("strategy.economic-expansion", "vs.unknown");
        assertThat(task.testActivation(readyContext())).isTrue();
    }

    @Test
    void testActivation_falseWhenSelectedButReadyAbsent() {
        selector.selectForGame("strategy.economic-expansion", "vs.unknown");
        var ctx = new MapCaseContext(Map.of());
        assertThat(task.testActivation(ctx)).isFalse();
    }

    @Test
    void execute_writesExpandStrategy() {
        var ctx = readyContext();
        task.execute(ctx);
        assertThat(ctx.getAs(QuarkMindCaseFile.STRATEGY, String.class)).isEqualTo("EXPAND");
    }

    @Test
    void produces_containsStrategy() {
        assertThat(task.produces()).contains(QuarkMindCaseFile.STRATEGY);
    }
}

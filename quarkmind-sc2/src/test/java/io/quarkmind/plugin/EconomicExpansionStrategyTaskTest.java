package io.quarkmind.plugin;

import io.quarkmind.agent.MapCaseContext;
import io.quarkmind.agent.MutableMapCaseContext;
import io.quarkmind.agent.QuarkMindCaseFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EconomicExpansionStrategyTaskTest {

    EconomicExpansionStrategyTask task;

    @BeforeEach
    void setUp() {
        task = new EconomicExpansionStrategyTask();
    }

    private MutableMapCaseContext readyContext() {
        return new MutableMapCaseContext(new java.util.HashMap<>(Map.of(
            QuarkMindCaseFile.READY, Boolean.TRUE)));
    }

    private MutableMapCaseContext readyContextWithStrategy(String strategyId) {
        return new MutableMapCaseContext(new java.util.HashMap<>(Map.of(
            QuarkMindCaseFile.READY, Boolean.TRUE,
            QuarkMindCaseFile.STRATEGY_SELECTED_ID, strategyId)));
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
        assertThat(task.testActivation(readyContextWithStrategy("strategy.economic-expansion"))).isTrue();
    }

    @Test
    void testActivation_falseWhenSelectedButReadyAbsent() {
        var ctx = new MapCaseContext(Map.of(
            QuarkMindCaseFile.STRATEGY_SELECTED_ID, "strategy.economic-expansion"));
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

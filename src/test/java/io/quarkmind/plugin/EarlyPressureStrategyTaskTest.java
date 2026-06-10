package io.quarkmind.plugin;

import io.casehub.coordination.PropagationContext;
import io.casehub.core.CaseFile;
import io.casehub.persistence.memory.InMemoryCaseFileRepository;
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

    private CaseFile readyCaseFile() {
        var cf = new InMemoryCaseFileRepository()
            .create("starcraft-game", Map.of(), PropagationContext.createRoot());
        cf.put(QuarkMindCaseFile.READY, Boolean.TRUE);
        return cf;
    }

    @Test
    void getId_returnsEarlyPressureId() {
        assertThat(task.getId()).isEqualTo("strategy.early-pressure");
    }

    @Test
    void entryCriteria_containsOnlyReady() {
        assertThat(task.entryCriteria()).containsExactly(QuarkMindCaseFile.READY);
    }

    @Test
    void canActivate_falseWhenNotSelected() {
        // selector defaults to "strategy.drools" — not early-pressure
        assertThat(task.canActivate(readyCaseFile())).isFalse();
    }

    @Test
    void canActivate_trueWhenSelectedAndReadyPresent() {
        selector.selectForGame("strategy.early-pressure", "vs.unknown");
        assertThat(task.canActivate(readyCaseFile())).isTrue();
    }

    @Test
    void canActivate_falseWhenSelectedButReadyAbsent() {
        selector.selectForGame("strategy.early-pressure", "vs.unknown");
        var cf = new InMemoryCaseFileRepository()
            .create("starcraft-game", Map.of(), PropagationContext.createRoot());
        // READY not present
        assertThat(task.canActivate(cf)).isFalse();
    }

    @Test
    void execute_writesAttackStrategy() {
        CaseFile cf = readyCaseFile();
        task.execute(cf);
        assertThat(cf.get(QuarkMindCaseFile.STRATEGY, String.class)).contains("ATTACK");
    }

    @Test
    void producedKeys_containsStrategy() {
        assertThat(task.producedKeys()).contains(QuarkMindCaseFile.STRATEGY);
    }
}

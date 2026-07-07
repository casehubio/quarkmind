package io.quarkmind.plugin.summarisation;

import io.casehub.blocks.summarisation.EventLevel;
import io.casehub.blocks.summarisation.EventStreamBus;
import io.casehub.blocks.summarisation.LevelEvent;
import io.casehub.blocks.summarisation.SummarisationRunner;
import io.casehub.blocks.summarisation.WindowPolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test: verifies the L2→L3 pipeline outside CDI.
 */
class SummarisationPipelineTest {

    private static final EventLevel LEVEL_2 = new EventLevel("moment", 2);
    private static final EventLevel LEVEL_3 = new EventLevel("phase", 3);

    @Test
    void phaseRunner_emitsPhase_afterCountThreshold() {
        var momentBus = new EventStreamBus<GameMoment>();
        var phaseBus = new EventStreamBus<GamePhase>();

        var runner = new SummarisationRunner<>(
            new WindowPolicy(672, 5),
            new GamePhaseSummariser(), phaseBus, LEVEL_3);

        // Wire: moments feed the runner
        momentBus.subscribe(m -> true, e -> runner.collect(e));

        // Capture phases
        List<LevelEvent<GamePhase>> receivedPhases = new ArrayList<>();
        phaseBus.subscribe(p -> true, receivedPhases::add);

        // Publish 5 moments (count threshold)
        for (int i = 0; i < 5; i++) {
            momentBus.publish(new LevelEvent<>(
                new GameMoment(GameMomentType.BATTLE_STARTED, 100 + i, Map.of()),
                100 + i, LEVEL_2));
        }

        // Verify runner collected
        assertThat(runner.size()).isEqualTo(5);

        // Tick — should trigger
        runner.tick(200);

        assertThat(receivedPhases).hasSize(1);
        assertThat(receivedPhases.get(0).payload().phase()).isEqualTo("MID_SKIRMISH");
    }
}

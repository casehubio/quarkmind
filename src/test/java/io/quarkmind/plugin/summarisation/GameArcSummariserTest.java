package io.quarkmind.plugin.summarisation;

import io.casehub.blocks.summarisation.EventLevel;
import io.casehub.blocks.summarisation.LevelEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GameArcSummariserTest {

    private static final EventLevel L3 = new EventLevel("phase", 3);

    private final GameArcSummariser summariser = new GameArcSummariser();

    @Test
    void producesNarrative_fromPhaseSequence() {
        var batch = List.of(
            new LevelEvent<>(new TacticalPosture("EARLY_MACRO", 0, "expanding"), 100, L3),
            new LevelEvent<>(new TacticalPosture("MID_SKIRMISH", 100, "battles"), 200, L3));
        var arcs = summariser.summarise(batch).toCompletableFuture().join();
        assertThat(arcs).hasSize(1);
        assertThat(arcs.get(0).narrative()).isNotBlank();
    }

    @Test
    void singlePhase_producesNarrative() {
        var batch = List.of(
            new LevelEvent<>(new TacticalPosture("DEFENSIVE_HOLD", 50, "under attack"), 100, L3));
        var arcs = summariser.summarise(batch).toCompletableFuture().join();
        assertThat(arcs).hasSize(1);
        assertThat(arcs.get(0).narrative()).contains("DEFENSIVE_HOLD");
    }

    @Test
    void emptyBatch_returnsEmpty() {
        assertThat(summariser.summarise(List.of()).toCompletableFuture().join()).isEmpty();
    }
}

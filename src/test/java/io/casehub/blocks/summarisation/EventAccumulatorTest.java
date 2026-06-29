package io.casehub.blocks.summarisation;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EventAccumulatorTest {

    private static final EventLevel LEVEL = new EventLevel("test", 0);

    @Test
    void shouldEmit_timestampTrigger_firesWhenMaxAgeExceeded() {
        var acc = new EventAccumulator<String>(new WindowPolicy(100, 0));
        acc.collect(new LevelEvent<>("a", 10, LEVEL));
        assertThat(acc.shouldEmit(50)).isFalse();
        assertThat(acc.shouldEmit(111)).isTrue();
    }

    @Test
    void shouldEmit_countTrigger_firesWhenMaxCountReached() {
        var acc = new EventAccumulator<String>(new WindowPolicy(0, 3));
        acc.collect(new LevelEvent<>("a", 1, LEVEL));
        acc.collect(new LevelEvent<>("b", 2, LEVEL));
        assertThat(acc.shouldEmit(999)).isFalse();
        acc.collect(new LevelEvent<>("c", 3, LEVEL));
        assertThat(acc.shouldEmit(999)).isTrue();
    }

    @Test
    void shouldEmit_dualTrigger_firesOnEitherCondition() {
        var acc = new EventAccumulator<String>(new WindowPolicy(100, 5));
        acc.collect(new LevelEvent<>("a", 10, LEVEL));
        assertThat(acc.shouldEmit(111)).as("timestamp trigger").isTrue();

        var acc2 = new EventAccumulator<String>(new WindowPolicy(100, 2));
        acc2.collect(new LevelEvent<>("a", 10, LEVEL));
        acc2.collect(new LevelEvent<>("b", 11, LEVEL));
        assertThat(acc2.shouldEmit(12)).as("count trigger").isTrue();
    }

    @Test
    void drain_returnsAndClears() {
        var acc = new EventAccumulator<String>(new WindowPolicy(0, 1));
        acc.collect(new LevelEvent<>("a", 1, LEVEL));
        acc.collect(new LevelEvent<>("b", 2, LEVEL));
        var drained = acc.drain();
        assertThat(drained).hasSize(2);
        assertThat(acc.size()).isZero();
        assertThat(acc.shouldEmit(999)).isFalse();
    }

    @Test
    void clear_resetsAllState() {
        var acc = new EventAccumulator<String>(new WindowPolicy(0, 1));
        acc.collect(new LevelEvent<>("a", 1, LEVEL));
        acc.clear();
        assertThat(acc.size()).isZero();
        assertThat(acc.drain()).isEmpty();
    }

    @Test
    void shouldEmit_emptyAccumulator_neverFires() {
        var acc = new EventAccumulator<String>(new WindowPolicy(100, 5));
        assertThat(acc.shouldEmit(999)).isFalse();
    }
}

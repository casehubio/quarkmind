package io.casehub.blocks.summarisation;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class SummarisationRunnerTest {

    private static final EventLevel INPUT_LEVEL = new EventLevel("input", 0);
    private static final EventLevel OUTPUT_LEVEL = new EventLevel("output", 1);

    @Test
    void tick_emitsWhenWindowMet_publishesToBus() {
        Summariser<String, Integer> summariser = batch -> List.of(batch.size());
        var acc = new EventAccumulator<String>(new WindowPolicy(0, 2));
        var outputBus = new EventStreamBus<Integer>();
        var runner = new SummarisationRunner<>(acc, summariser, outputBus, OUTPUT_LEVEL);

        List<Integer> received = new ArrayList<>();
        outputBus.subscribe(i -> true, e -> received.add(e.payload()));

        acc.collect(new LevelEvent<>("a", 1, INPUT_LEVEL));
        runner.tick(5);
        assertThat(received).as("not enough events yet").isEmpty();

        acc.collect(new LevelEvent<>("b", 2, INPUT_LEVEL));
        runner.tick(5);
        assertThat(received).as("count threshold met").containsExactly(2);
    }

    @Test
    void tick_doesNotEmitWhenWindowNotMet() {
        Summariser<String, Integer> summariser = batch -> List.of(batch.size());
        var acc = new EventAccumulator<String>(new WindowPolicy(100, 0));
        var outputBus = new EventStreamBus<Integer>();
        var runner = new SummarisationRunner<>(acc, summariser, outputBus, OUTPUT_LEVEL);

        List<Integer> received = new ArrayList<>();
        outputBus.subscribe(i -> true, e -> received.add(e.payload()));

        acc.collect(new LevelEvent<>("a", 50, INPUT_LEVEL));
        runner.tick(60);
        assertThat(received).isEmpty();
    }

    @Test
    void tick_wrapsOutputInLevelEvent_withCorrectLevelAndTimestamp() {
        Summariser<String, String> summariser = batch -> List.of("summary");
        var acc = new EventAccumulator<String>(new WindowPolicy(0, 1));
        var outputBus = new EventStreamBus<String>();
        var runner = new SummarisationRunner<>(acc, summariser, outputBus, OUTPUT_LEVEL);

        List<LevelEvent<String>> received = new ArrayList<>();
        outputBus.subscribe(s -> true, received::add);

        acc.collect(new LevelEvent<>("a", 10, INPUT_LEVEL));
        runner.tick(42);
        assertThat(received).hasSize(1);
        assertThat(received.get(0).level()).isEqualTo(OUTPUT_LEVEL);
        assertThat(received.get(0).timestamp()).isEqualTo(42);
        assertThat(received.get(0).payload()).isEqualTo("summary");
    }

    @Test
    void clear_resetsAccumulator() {
        Summariser<String, Integer> summariser = batch -> List.of(batch.size());
        var acc = new EventAccumulator<String>(new WindowPolicy(0, 2));
        var outputBus = new EventStreamBus<Integer>();
        var runner = new SummarisationRunner<>(acc, summariser, outputBus, OUTPUT_LEVEL);

        acc.collect(new LevelEvent<>("a", 1, INPUT_LEVEL));
        runner.clear();
        assertThat(acc.size()).isZero();
    }
}

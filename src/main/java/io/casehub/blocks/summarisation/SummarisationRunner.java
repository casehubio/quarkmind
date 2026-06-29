package io.casehub.blocks.summarisation;

public class SummarisationRunner<IN, OUT> {

    private final EventAccumulator<IN> accumulator;
    private final Summariser<IN, OUT> summariser;
    private final EventStreamBus<OUT> outputBus;
    private final EventLevel outputLevel;

    public SummarisationRunner(EventAccumulator<IN> accumulator,
                               Summariser<IN, OUT> summariser,
                               EventStreamBus<OUT> outputBus,
                               EventLevel outputLevel) {
        this.accumulator = accumulator;
        this.summariser = summariser;
        this.outputBus = outputBus;
        this.outputLevel = outputLevel;
    }

    public void tick(long now) {
        if (!accumulator.shouldEmit(now)) return;
        var batch = accumulator.drain();
        var results = summariser.summarise(batch);
        for (var payload : results) {
            outputBus.publish(new LevelEvent<>(payload, now, outputLevel));
        }
    }

    public void clear() {
        accumulator.clear();
    }
}

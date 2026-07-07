package io.casehub.blocks.summarisation;

public class SummarisationRunner<IN, OUT> {

    private final EventAccumulator<IN> accumulator;
    private final Summariser<IN, OUT> summariser;
    private final EventStreamBus<OUT> outputBus;
    private final EventLevel outputLevel;

    public SummarisationRunner(WindowPolicy policy,
                               Summariser<IN, OUT> summariser,
                               EventStreamBus<OUT> outputBus,
                               EventLevel outputLevel) {
        this.accumulator = new EventAccumulator<>(policy);
        this.summariser = summariser;
        this.outputBus = outputBus;
        this.outputLevel = outputLevel;
    }

    public void collect(LevelEvent<IN> event) {
        accumulator.collect(event);
    }

    public void tick(long now) {
        if (!accumulator.shouldEmit(now)) return;
        var batch = accumulator.drain();
        summariser.summarise(batch).thenAccept(results -> {
            for (var payload : results) {
                outputBus.publish(new LevelEvent<>(payload, now, outputLevel));
            }
        });
    }

    public void clear() {
        accumulator.clear();
    }

    public int size() {
        return accumulator.size();
    }
}

package io.casehub.blocks.summarisation;

import java.util.List;

@FunctionalInterface
public interface Summariser<IN, OUT> {
    List<OUT> summarise(List<LevelEvent<IN>> batch);
}

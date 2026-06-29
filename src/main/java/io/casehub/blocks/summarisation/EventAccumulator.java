package io.casehub.blocks.summarisation;

import java.util.ArrayList;
import java.util.List;

public class EventAccumulator<E> {

    private final WindowPolicy policy;
    private final List<LevelEvent<E>> buffer = new ArrayList<>();

    public EventAccumulator(WindowPolicy policy) {
        this.policy = policy;
    }

    public void collect(LevelEvent<E> event) {
        buffer.add(event);
    }

    public boolean shouldEmit(long now) {
        if (buffer.isEmpty()) return false;
        if (policy.maxCount() > 0 && buffer.size() >= policy.maxCount()) return true;
        if (policy.maxAge() > 0) {
            long oldest = buffer.get(0).timestamp();
            return (now - oldest) >= policy.maxAge();
        }
        return false;
    }

    public List<LevelEvent<E>> drain() {
        var result = List.copyOf(buffer);
        buffer.clear();
        return result;
    }

    public void clear() {
        buffer.clear();
    }

    public int size() {
        return buffer.size();
    }
}

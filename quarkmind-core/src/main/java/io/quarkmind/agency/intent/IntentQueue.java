package io.quarkmind.agency.intent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

public class IntentQueue<I extends Intent> {

    private final Deque<I> queue = new ArrayDeque<>();

    public void enqueue(I intent) {
        queue.addLast(intent);
    }

    public I dequeue() {
        return queue.pollFirst();
    }

    public List<I> drainAll() {
        var result = List.copyOf(queue);
        queue.clear();
        return result;
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    public int size() {
        return queue.size();
    }
}

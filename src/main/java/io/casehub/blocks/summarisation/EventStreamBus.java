package io.casehub.blocks.summarisation;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Not thread-safe. Subscriptions use CopyOnWriteArrayList for safe iteration during publish,
 * but concurrent publish calls or concurrent publish+subscribe are not safe. Caller must
 * synchronize if accessed from multiple threads.
 */
public class EventStreamBus<E> {

    private record Subscription<E>(Predicate<E> filter, Consumer<LevelEvent<E>> callback) {}

    private final List<Subscription<E>> subscriptions = new CopyOnWriteArrayList<>();

    public void subscribe(Predicate<E> filter, Consumer<LevelEvent<E>> callback) {
        subscriptions.add(new Subscription<>(filter, callback));
    }

    public void publish(LevelEvent<E> event) {
        for (var sub : subscriptions) {
            if (sub.filter().test(event.payload())) {
                sub.callback().accept(event);
            }
        }
    }

    public void clear() {
        subscriptions.clear();
    }
}

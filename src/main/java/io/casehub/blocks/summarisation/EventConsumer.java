package io.casehub.blocks.summarisation;

import java.util.function.Predicate;

public interface EventConsumer<E> {
    Predicate<E> eventFilter();
}

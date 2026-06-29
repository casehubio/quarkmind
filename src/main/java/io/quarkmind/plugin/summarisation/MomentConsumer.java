package io.quarkmind.plugin.summarisation;

import io.casehub.blocks.summarisation.EventConsumer;

import java.util.Set;
import java.util.function.Predicate;

public interface MomentConsumer extends EventConsumer<GameMoment> {

    Set<GameMomentType> subscribedMomentTypes();

    @Override
    default Predicate<GameMoment> eventFilter() {
        return m -> subscribedMomentTypes().contains(m.type());
    }
}

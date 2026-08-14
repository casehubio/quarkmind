package io.quarkmind.plugin.summarisation;

import java.util.Set;
import java.util.function.Predicate;

public interface MomentConsumer {

    Set<GameMomentType> subscribedMomentTypes();

    default Predicate<GameMoment> eventFilter() {
        return m -> subscribedMomentTypes().contains(m.type());
    }
}

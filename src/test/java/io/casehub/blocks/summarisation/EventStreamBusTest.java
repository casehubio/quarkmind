package io.casehub.blocks.summarisation;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class EventStreamBusTest {

    private static final EventLevel LEVEL = new EventLevel("test", 0);

    @Test
    void publish_dispatchesToMatchingSubscribers() {
        var bus = new EventStreamBus<String>();
        List<String> received = new ArrayList<>();
        bus.subscribe(s -> s.startsWith("A"), e -> received.add(e.payload()));
        bus.publish(new LevelEvent<>("Alpha", 1, LEVEL));
        bus.publish(new LevelEvent<>("Beta", 2, LEVEL));
        bus.publish(new LevelEvent<>("Apex", 3, LEVEL));
        assertThat(received).containsExactly("Alpha", "Apex");
    }

    @Test
    void publish_multipleSubscribers_allReceive() {
        var bus = new EventStreamBus<String>();
        List<String> sub1 = new ArrayList<>();
        List<String> sub2 = new ArrayList<>();
        bus.subscribe(s -> true, e -> sub1.add(e.payload()));
        bus.subscribe(s -> true, e -> sub2.add(e.payload()));
        bus.publish(new LevelEvent<>("X", 1, LEVEL));
        assertThat(sub1).containsExactly("X");
        assertThat(sub2).containsExactly("X");
    }

    @Test
    void clear_removesAllSubscribers() {
        var bus = new EventStreamBus<String>();
        List<String> received = new ArrayList<>();
        bus.subscribe(s -> true, e -> received.add(e.payload()));
        bus.clear();
        bus.publish(new LevelEvent<>("X", 1, LEVEL));
        assertThat(received).isEmpty();
    }

    @Test
    void publish_noSubscribers_noError() {
        var bus = new EventStreamBus<String>();
        bus.publish(new LevelEvent<>("X", 1, LEVEL));
    }
}

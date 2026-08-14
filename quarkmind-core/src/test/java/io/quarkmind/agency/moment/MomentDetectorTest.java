package io.quarkmind.agency.moment;

import io.quarkmind.agency.AgencyContext;
import io.quarkmind.agency.needs.NeedState;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MomentDetectorTest {

    @Test
    void momentEvent_isImmutable() {
        var event = new MomentEvent("battle_start", 500L, Map.of("units", 12));
        assertEquals("battle_start", event.type());
        assertEquals(500L, event.tick());
        assertThrows(UnsupportedOperationException.class, () -> event.data().put("x", "y"));
    }

    @Test
    void momentEvent_handlesNullData() {
        var event = new MomentEvent("first_contact", 100L, null);
        assertNotNull(event.data());
        assertTrue(event.data().isEmpty());
    }

    @Test
    void detector_returnsEmptyWhenNoMoments() {
        MomentDetector detector = ctx -> List.of();
        var ctx = new AgencyContext(new NeedState());
        assertTrue(detector.detect(ctx).isEmpty());
    }

    @Test
    void detector_returnsEventsWhenConditionsMet() {
        MomentDetector detector = ctx -> {
            if (ctx.contains("supply_blocked")) {
                return List.of(new MomentEvent("supply_block", ctx.tick(), Map.of()));
            }
            return List.of();
        };

        var ctx = new AgencyContext(new NeedState());
        ctx.setTick(200L);
        ctx.put("supply_blocked", true);

        var events = detector.detect(ctx);
        assertEquals(1, events.size());
        assertEquals("supply_block", events.get(0).type());
        assertEquals(200L, events.get(0).tick());
    }
}

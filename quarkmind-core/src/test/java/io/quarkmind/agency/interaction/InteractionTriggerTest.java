package io.quarkmind.agency.interaction;

import io.quarkmind.agency.AgencyContext;
import io.quarkmind.agency.needs.NeedState;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class InteractionTriggerTest {

    @Test
    void triggerEvent_isImmutable() {
        var event = new TriggerEvent("crisis", Map.of("severity", "high"));
        assertEquals("crisis", event.type());
        assertEquals("high", event.data().get("severity"));
        assertThrows(UnsupportedOperationException.class, () -> event.data().put("x", "y"));
    }

    @Test
    void trigger_evaluatesContext_andFiresWhenConditionMet() {
        InteractionTrigger trigger = ctx -> {
            if (ctx.contains("crisis")) {
                return Optional.of(new TriggerEvent("crisis", Map.of("frame", ctx.tick())));
            }
            return Optional.empty();
        };

        var ctx = new AgencyContext(new NeedState());
        ctx.setTick(100L);

        assertTrue(trigger.evaluate(ctx).isEmpty());

        ctx.put("crisis", true);
        var event = trigger.evaluate(ctx);
        assertTrue(event.isPresent());
        assertEquals("crisis", event.get().type());
        assertEquals(100L, event.get().data().get("frame"));
    }

    @Test
    void pipeline_evaluatesContext() {
        var fired = new boolean[]{false};
        InteractionPipeline pipeline = ctx -> fired[0] = true;

        var ctx = new AgencyContext(new NeedState());
        pipeline.evaluate(ctx);
        assertTrue(fired[0]);
    }
}

package io.quarkmind.agency;

import io.quarkmind.agency.needs.NeedState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgencyContextTest {

    @Test
    void tickDefaultsToZero() {
        var ctx = new AgencyContext(new NeedState());
        assertEquals(0L, ctx.tick());
    }

    @Test
    void setTickUpdatesValue() {
        var ctx = new AgencyContext(new NeedState());
        ctx.setTick(42L);
        assertEquals(42L, ctx.tick());
    }

    @Test
    void putAndGet() {
        var ctx = new AgencyContext(new NeedState());
        ctx.put("minerals", 150);
        assertEquals(150, ctx.get("minerals"));
    }

    @Test
    void getAsReturnsTypedValue() {
        var ctx = new AgencyContext(new NeedState());
        ctx.put("minerals", 150);
        assertEquals(150, ctx.getAs("minerals", Integer.class));
    }

    @Test
    void getAsReturnsNullForWrongType() {
        var ctx = new AgencyContext(new NeedState());
        ctx.put("minerals", 150);
        assertNull(ctx.getAs("minerals", String.class));
    }

    @Test
    void containsReturnsTrueForPresentKey() {
        var ctx = new AgencyContext(new NeedState());
        ctx.put("ready", true);
        assertTrue(ctx.contains("ready"));
    }

    @Test
    void containsReturnsFalseForAbsentKey() {
        var ctx = new AgencyContext(new NeedState());
        assertFalse(ctx.contains("missing"));
    }

    @Test
    void needStateIsAccessible() {
        var needs = new NeedState();
        needs.set("hunger", 80.0);
        var ctx = new AgencyContext(needs);
        assertEquals(80.0, ctx.needState().get("hunger"), 0.01);
    }
}

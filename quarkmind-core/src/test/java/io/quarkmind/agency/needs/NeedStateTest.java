package io.quarkmind.agency.needs;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NeedStateTest {

    @Test
    void needDecaysOverTime() {
        var state = new NeedState();
        state.set("hunger", 100.0);
        state.decay("hunger", 2.0);
        assertEquals(98.0, state.get("hunger"), 0.01);
    }

    @Test
    void needClampsBetweenZeroAndMax() {
        var state = new NeedState();
        state.set("hunger", 1.0);
        state.decay("hunger", 5.0);
        assertEquals(0.0, state.get("hunger"), 0.01);
    }

    @Test
    void satisfyIncreasesNeed() {
        var state = new NeedState();
        state.set("hunger", 50.0);
        state.satisfy("hunger", 30.0);
        assertEquals(80.0, state.get("hunger"), 0.01);
    }

    @Test
    void satisfyClampsAtMax() {
        var state = new NeedState();
        state.set("hunger", 90.0);
        state.satisfy("hunger", 20.0);
        assertEquals(100.0, state.get("hunger"), 0.01);
    }

    @Test
    void unknownNeedDefaultsToZero() {
        var state = new NeedState();
        assertEquals(0.0, state.get("nonexistent"), 0.01);
    }
}

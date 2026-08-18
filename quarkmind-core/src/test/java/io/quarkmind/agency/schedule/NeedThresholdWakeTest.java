package io.quarkmind.agency.schedule;

import io.quarkmind.agency.needs.NeedState;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NeedThresholdWakeTest {

    @Test
    void detectsWhenNeedBelowThreshold() {
        var wake = new NeedThresholdWake(Map.of("SOCIAL", 30.0));
        var needs = new NeedState();
        needs.set("SOCIAL", 25.0);
        assertTrue(wake.anyNeedCrossed(needs));
    }

    @Test
    void noWakeWhenNeedsAboveThresholds() {
        var wake = new NeedThresholdWake(Map.of("SOCIAL", 30.0));
        var needs = new NeedState();
        needs.set("SOCIAL", 50.0);
        assertFalse(wake.anyNeedCrossed(needs));
    }

    @Test
    void mostUrgentNeedReturnsMostDeficient() {
        var wake = new NeedThresholdWake(Map.of("SOCIAL", 50.0, "CURIOSITY", 40.0));
        var needs = new NeedState();
        needs.set("SOCIAL", 10.0);
        needs.set("CURIOSITY", 35.0);
        assertEquals("SOCIAL", wake.mostUrgentNeed(needs));
    }
}

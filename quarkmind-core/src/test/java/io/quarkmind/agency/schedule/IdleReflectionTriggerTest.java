package io.quarkmind.agency.schedule;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IdleReflectionTriggerTest {

    @Test
    void doesNotReflectBelowThreshold() {
        var trigger = new IdleReflectionTrigger(3.0, 5);
        trigger.accumulate(0.5);
        trigger.accumulate(0.5);
        assertFalse(trigger.shouldReflect(10));
    }

    @Test
    void reflectsWhenThresholdCrossedAndIdle() {
        var trigger = new IdleReflectionTrigger(3.0, 5);
        trigger.accumulate(1.0);
        trigger.accumulate(1.0);
        trigger.accumulate(1.0);
        assertFalse(trigger.shouldReflect(3));
        assertTrue(trigger.shouldReflect(5));
    }

    @Test
    void resetClearsAccumulator() {
        var trigger = new IdleReflectionTrigger(3.0, 5);
        trigger.accumulate(1.0);
        trigger.accumulate(1.0);
        trigger.accumulate(1.0);
        trigger.reset();
        assertFalse(trigger.shouldReflect(10));
    }

    @Test
    void doesNotReflectWhenNotIdle() {
        var trigger = new IdleReflectionTrigger(3.0, 5);
        trigger.accumulate(5.0);
        assertFalse(trigger.shouldReflect(2));
    }
}

package io.quarkmind.agency.schedule;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OutputGovernorTest {

    @Test
    void allowsFirstAction() {
        var governor = new OutputGovernor(300_000, 30_000, 1);
        assertTrue(governor.allow());
    }

    @Test
    void blocksActionWithinMinInterval() {
        var governor = new OutputGovernor(300_000, 30_000, 1);
        governor.recordAction();
        assertFalse(governor.allow());
    }

    @Test
    void blocksWhenWindowMaxReached() {
        var governor = new OutputGovernor(300_000, 0, 1);
        governor.recordAction();
        assertFalse(governor.allow());
    }
}

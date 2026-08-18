package io.quarkmind.agency.personality;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProactiveDecisionGateTest {

    @Test
    void allowsWhenQuietAndSufficientTimePassed() {
        var gate = new ProactiveDecisionGate(60_000, 5);
        assertTrue(gate.shouldAct(120_000, 0, false));
    }

    @Test
    void blocksWhenTooSoon() {
        var gate = new ProactiveDecisionGate(60_000, 5);
        assertFalse(gate.shouldAct(10_000, 0, false));
    }

    @Test
    void blocksWhenOthersTyping() {
        var gate = new ProactiveDecisionGate(60_000, 5);
        assertFalse(gate.shouldAct(120_000, 0, true));
    }

    @Test
    void blocksWhenChannelTooActive() {
        var gate = new ProactiveDecisionGate(60_000, 5);
        assertFalse(gate.shouldAct(120_000, 10, false));
    }
}

package io.quarkmind.chat.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatChannelPacingTest {

    @Test
    void quietChannelAllowsPost() {
        var pacing = new ChatChannelPacing(5, 60_000);
        assertTrue(pacing.allowUnprompted(0, 120_000));
    }

    @Test
    void busyChannelBlocksPost() {
        var pacing = new ChatChannelPacing(5, 60_000);
        assertFalse(pacing.allowUnprompted(10, 120_000));
    }

    @Test
    void tooSoonBlocksRegardlessOfActivity() {
        var pacing = new ChatChannelPacing(5, 60_000);
        assertFalse(pacing.allowUnprompted(0, 30_000));
    }

    @Test
    void exactThresholdAllows() {
        var pacing = new ChatChannelPacing(5, 60_000);
        assertTrue(pacing.allowUnprompted(5, 60_000));
    }
}

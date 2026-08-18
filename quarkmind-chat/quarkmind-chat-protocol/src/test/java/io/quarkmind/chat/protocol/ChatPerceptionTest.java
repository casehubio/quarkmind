package io.quarkmind.chat.protocol;

import io.casehub.connectors.chat.model.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChatPerceptionTest {

    @Test
    void constructsWithDeltasAndReason() {
        var msg = dummyMessage("hello");
        var deltas = Map.of("ch-1", List.of(msg));
        var perception = new ChatPerception(deltas, Map.of(), WakeReason.MESSAGE);
        assertEquals(1, perception.channelDeltas().get("ch-1").size());
        assertEquals(WakeReason.MESSAGE, perception.reason());
    }

    @Test
    void emptyPerceptionForHeartbeat() {
        var perception = new ChatPerception(Map.of(), Map.of(), WakeReason.HEARTBEAT);
        assertTrue(perception.channelDeltas().isEmpty());
        assertEquals(WakeReason.HEARTBEAT, perception.reason());
    }

    @Test
    void hasActivityReturnsTrueWhenMessagesExist() {
        var perception = new ChatPerception(Map.of(), Map.of(), WakeReason.MESSAGE);
        assertFalse(perception.hasActivity());
        var withDeltas = new ChatPerception(
                Map.of("ch", List.of(dummyMessage("hi"))), Map.of(), WakeReason.MESSAGE);
        assertTrue(withDeltas.hasActivity());
    }

    @Test
    void totalMessageCountSumsAcrossChannels() {
        var perception = new ChatPerception(
                Map.of("ch1", List.of(dummyMessage("a")),
                       "ch2", List.of(dummyMessage("b"), dummyMessage("c"))),
                Map.of(), WakeReason.MESSAGE);
        assertEquals(3, perception.totalMessageCount());
    }

    @Test
    void wakeReasonFromDriverSource() {
        assertEquals(WakeReason.HEARTBEAT, WakeReason.fromDriverSource("timer"));
        assertEquals(WakeReason.MESSAGE, WakeReason.fromDriverSource("discord"));
        assertEquals(WakeReason.MESSAGE, WakeReason.fromDriverSource("slack"));
    }

    private ReceivedMessage dummyMessage(String text) {
        return new ReceivedMessage("discord", new ChatChannelRef("ch"),
                new ChatMessageRef(new ChatChannelRef("ch"), "m1"), null,
                new MemberRef("u1"),
                new ChatContent(text, null, List.of(), List.of()),
                Instant.now());
    }
}

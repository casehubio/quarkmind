package io.quarkmind.chat.agent.discord;

import io.casehub.connectors.chat.model.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class DiscordGatewayMessageHistoryTest {

    @Test
    void accumulatesMessages() {
        var history = new DiscordGatewayMessageHistory();
        var msg = new ReceivedMessage("discord", new ChatChannelRef("ch-1"),
                new ChatMessageRef(new ChatChannelRef("ch-1"), "m1"), null,
                new MemberRef("user-1"), new ChatContent("hello"), Instant.now());
        history.accumulate(msg);

        var result = history.messages(new ChatChannelRef("ch-1"), Instant.EPOCH);
        assertEquals(1, result.size());
        assertEquals("hello", result.get(0).content().text());
    }

    @Test
    void onlyReturnsMessagesSinceTimestamp() {
        var history = new DiscordGatewayMessageHistory();
        var old = new ReceivedMessage("discord", new ChatChannelRef("ch-1"),
                new ChatMessageRef(new ChatChannelRef("ch-1"), "m1"), null,
                new MemberRef("user-1"), new ChatContent("old"),
                Instant.parse("2026-01-01T00:00:00Z"));
        var recent = new ReceivedMessage("discord", new ChatChannelRef("ch-1"),
                new ChatMessageRef(new ChatChannelRef("ch-1"), "m2"), null,
                new MemberRef("user-1"), new ChatContent("recent"),
                Instant.parse("2026-08-01T00:00:00Z"));
        history.accumulate(old);
        history.accumulate(recent);

        var result = history.messages(new ChatChannelRef("ch-1"),
                Instant.parse("2026-06-01T00:00:00Z"));
        assertEquals(1, result.size());
        assertEquals("recent", result.get(0).content().text());
    }

    @Test
    void emptyForUnknownChannel() {
        var history = new DiscordGatewayMessageHistory();
        var result = history.messages(new ChatChannelRef("unknown"), Instant.EPOCH);
        assertTrue(result.isEmpty());
    }

    @Test
    void separatesMessagesByChannel() {
        var history = new DiscordGatewayMessageHistory();
        history.accumulate(new ReceivedMessage("discord", new ChatChannelRef("ch-1"),
                new ChatMessageRef(new ChatChannelRef("ch-1"), "m1"), null,
                new MemberRef("user-1"), new ChatContent("in ch-1"), Instant.now()));
        history.accumulate(new ReceivedMessage("discord", new ChatChannelRef("ch-2"),
                new ChatMessageRef(new ChatChannelRef("ch-2"), "m2"), null,
                new MemberRef("user-1"), new ChatContent("in ch-2"), Instant.now()));

        assertEquals(1, history.messages(new ChatChannelRef("ch-1"), Instant.EPOCH).size());
        assertEquals(1, history.messages(new ChatChannelRef("ch-2"), Instant.EPOCH).size());
    }
}

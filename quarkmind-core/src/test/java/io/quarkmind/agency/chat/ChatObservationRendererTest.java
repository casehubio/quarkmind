package io.quarkmind.agency.chat;

import io.casehub.connectors.chat.model.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ChatObservationRendererTest {

    private final BotIdentityDetector mentionDetector = new BotIdentityDetector() {
        @Override public boolean isMention(ReceivedMessage msg) {
            return msg.content().text().contains("@bot");
        }
        @Override public boolean isReplyToBot(ReceivedMessage msg) { return false; }
        @Override public String botUserId() { return "bot-id"; }
    };

    private final BotIdentityDetector noMentionDetector = new BotIdentityDetector() {
        @Override public boolean isMention(ReceivedMessage msg) { return false; }
        @Override public boolean isReplyToBot(ReceivedMessage msg) { return false; }
        @Override public String botUserId() { return "bot-id"; }
    };

    @Test
    void directMessagesAlwaysVerbatim() {
        var msg = msg("@bot help me please", "m1");
        var report = ChatDeltaReport.build(Map.of("ch", List.of(msg)), mentionDetector, Set.of());
        var renderer = new ChatObservationRenderer(10);
        String result = renderer.renderDelta(report);
        assertTrue(result.contains("@bot help me please"));
    }

    @Test
    void ambientMessagesCompressedWhenOverThreshold() {
        var messages = new ArrayList<ReceivedMessage>();
        for (int i = 0; i < 15; i++) {
            messages.add(msg("ambient message " + i, "m" + i));
        }
        var report = ChatDeltaReport.build(Map.of("ch", messages), noMentionDetector, Set.of());
        var renderer = new ChatObservationRenderer(5);
        String result = renderer.renderDelta(report);
        assertFalse(result.contains("ambient message 0"));
        assertTrue(result.contains("ambient"));
    }

    @Test
    void fewAmbientMessagesRenderedVerbatim() {
        var messages = List.of(msg("hello world", "m1"), msg("nice day", "m2"));
        var report = ChatDeltaReport.build(Map.of("ch", messages), noMentionDetector, Set.of());
        var renderer = new ChatObservationRenderer(10);
        String result = renderer.renderDelta(report);
        assertTrue(result.contains("hello world"));
        assertTrue(result.contains("nice day"));
    }

    @Test
    void emptyReportRendersEmpty() {
        var report = ChatDeltaReport.build(Map.of(), noMentionDetector, Set.of());
        var renderer = new ChatObservationRenderer(10);
        String result = renderer.renderDelta(report);
        assertTrue(result.isEmpty());
    }

    private ReceivedMessage msg(String text, String id) {
        return new ReceivedMessage("discord", new ChatChannelRef("ch"),
                new ChatMessageRef(new ChatChannelRef("ch"), id), null,
                new MemberRef("user-1"), new ChatContent(text), Instant.now());
    }
}

package io.quarkmind.agency.chat;

import io.casehub.connectors.chat.model.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ChatDeltaReportTest {

    private final BotIdentityDetector detector = new BotIdentityDetector() {
        @Override public boolean isMention(ReceivedMessage msg) { return false; }
        @Override public boolean isReplyToBot(ReceivedMessage msg) { return false; }
        @Override public String botUserId() { return "bot-id"; }
    };

    @Test
    void groupsMessagesByThread() {
        var root = msg("hello", null, "m1");
        var reply = msg("hi back", ref("m1"), "m2");
        var standalone = msg("unrelated", null, "m3");

        var report = ChatDeltaReport.build(
                Map.of("ch", List.of(root, reply, standalone)),
                detector, Set.of());

        assertEquals(2, report.threads("ch").size());
    }

    @Test
    void directMessagesGetHighestPriority() {
        var mentionDetector = new BotIdentityDetector() {
            @Override public boolean isMention(ReceivedMessage msg) {
                return msg.content().text().contains("@bot");
            }
            @Override public boolean isReplyToBot(ReceivedMessage msg) { return false; }
            @Override public String botUserId() { return "bot-id"; }
        };

        var msg = msg("@bot help", null, "m1");
        var report = ChatDeltaReport.build(Map.of("ch", List.of(msg)), mentionDetector, Set.of());
        assertEquals(AttentionPriority.DIRECT, report.threads("ch").get(0).highestPriority());
    }

    @Test
    void emptyDeltaProducesEmptyReport() {
        var report = ChatDeltaReport.build(Map.of(), detector, Set.of());
        assertTrue(report.allChannels().isEmpty());
    }

    @Test
    void directMessagesReturnsOnlyDirectPriorityMessages() {
        var mentionDetector = new BotIdentityDetector() {
            @Override public boolean isMention(ReceivedMessage msg) {
                return msg.content().text().contains("@bot");
            }
            @Override public boolean isReplyToBot(ReceivedMessage msg) { return false; }
            @Override public String botUserId() { return "bot-id"; }
        };

        var direct = msg("@bot help me", null, "m1");
        var ambient = msg("random chat", null, "m2");
        var report = ChatDeltaReport.build(
                Map.of("ch", List.of(direct, ambient)), mentionDetector, Set.of());

        assertEquals(1, report.directMessages().size());
        assertEquals("@bot help me", report.directMessages().get(0).message().content().text());
    }

    @Test
    void totalMessageCountSumsAcrossChannels() {
        var report = ChatDeltaReport.build(
                Map.of("ch1", List.of(msg("a", null, "m1")),
                       "ch2", List.of(msg("b", null, "m2"), msg("c", null, "m3"))),
                detector, Set.of());
        assertEquals(3, report.totalMessageCount());
    }

    private ReceivedMessage msg(String text, ChatMessageRef parent, String id) {
        return new ReceivedMessage("discord", new ChatChannelRef("ch"),
                new ChatMessageRef(new ChatChannelRef("ch"), id),
                parent, new MemberRef("user-1"),
                new ChatContent(text, null, List.of(), List.of()), Instant.now());
    }

    private ChatMessageRef ref(String id) {
        return new ChatMessageRef(new ChatChannelRef("ch"), id);
    }
}

package io.quarkmind.agency.chat;

import io.casehub.connectors.chat.model.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AttentionClassifierTest {

    private final BotIdentityDetector detector = new BotIdentityDetector() {
        @Override public boolean isMention(ReceivedMessage msg) {
            return msg.content().text().contains("@bot");
        }
        @Override public boolean isReplyToBot(ReceivedMessage msg) {
            return msg.parentRef() != null
                    && msg.parentRef().messageId().equals("bot-msg-1");
        }
        @Override public String botUserId() { return "bot-id"; }
    };

    @Test
    void mentionClassifiedAsDirect() {
        var msg = msg("@bot hello", null);
        var classified = AttentionClassifier.classify(List.of(msg), detector, Set.of());
        assertEquals(AttentionPriority.DIRECT, classified.get(0).priority());
    }

    @Test
    void replyToBotClassifiedAsDirect() {
        var parent = new ChatMessageRef(new ChatChannelRef("ch"), "bot-msg-1");
        var msg = msg("sure thing", parent);
        var classified = AttentionClassifier.classify(List.of(msg), detector, Set.of());
        assertEquals(AttentionPriority.DIRECT, classified.get(0).priority());
    }

    @Test
    void messageInParticipatedThreadClassifiedAsElevated() {
        var parent = new ChatMessageRef(new ChatChannelRef("ch"), "thread-1");
        var msg = msg("continuing discussion", parent);
        var classified = AttentionClassifier.classify(
                List.of(msg), detector, Set.of("thread-1"));
        assertEquals(AttentionPriority.ELEVATED, classified.get(0).priority());
    }

    @Test
    void ordinaryMessageClassifiedAsAmbient() {
        var msg = msg("hey everyone", null);
        var classified = AttentionClassifier.classify(List.of(msg), detector, Set.of());
        assertEquals(AttentionPriority.AMBIENT, classified.get(0).priority());
    }

    @Test
    void directTakesPriorityOverElevated() {
        var parent = new ChatMessageRef(new ChatChannelRef("ch"), "thread-1");
        var msg = msg("@bot in thread", parent);
        var classified = AttentionClassifier.classify(
                List.of(msg), detector, Set.of("thread-1"));
        assertEquals(AttentionPriority.DIRECT, classified.get(0).priority());
    }

    private ReceivedMessage msg(String text, ChatMessageRef parent) {
        return new ReceivedMessage("discord", new ChatChannelRef("ch"),
                new ChatMessageRef(new ChatChannelRef("ch"), "m-" + text.hashCode()),
                parent, new MemberRef("user-1"),
                new ChatContent(text, null, List.of(), List.of()), Instant.now());
    }
}

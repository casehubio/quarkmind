package io.quarkmind.chat.agent.discord;

import io.casehub.connectors.chat.model.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class DiscordIdentityDetectorTest {

    private final DiscordIdentityDetector detector = new DiscordIdentityDetector("12345");

    @Test
    void detectsMentionWithDiscordSyntax() {
        assertTrue(detector.isMention(msg("hey <@12345> what's up")));
    }

    @Test
    void doesNotDetectMentionOfOtherUser() {
        assertFalse(detector.isMention(msg("hey <@99999> what's up")));
    }

    @Test
    void doesNotDetectMentionWhenNoMentionPresent() {
        assertFalse(detector.isMention(msg("just a regular message")));
    }

    @Test
    void detectsReplyToBot() {
        detector.recordBotMessage("bot-sent-msg");
        var parent = new ChatMessageRef(new ChatChannelRef("ch"), "bot-sent-msg");
        var reply = new ReceivedMessage("discord", new ChatChannelRef("ch"),
                new ChatMessageRef(new ChatChannelRef("ch"), "m1"),
                parent, new MemberRef("user-1"), new ChatContent("replying"), Instant.now());
        assertTrue(detector.isReplyToBot(reply));
    }

    @Test
    void doesNotDetectReplyToOtherMessage() {
        var parent = new ChatMessageRef(new ChatChannelRef("ch"), "other-msg");
        var reply = new ReceivedMessage("discord", new ChatChannelRef("ch"),
                new ChatMessageRef(new ChatChannelRef("ch"), "m1"),
                parent, new MemberRef("user-1"), new ChatContent("replying"), Instant.now());
        assertFalse(detector.isReplyToBot(reply));
    }

    @Test
    void doesNotDetectReplyWhenNoParent() {
        assertFalse(detector.isReplyToBot(msg("no parent here")));
    }

    @Test
    void returnsBotUserId() {
        assertEquals("12345", detector.botUserId());
    }

    private ReceivedMessage msg(String text) {
        return new ReceivedMessage("discord", new ChatChannelRef("ch"),
                new ChatMessageRef(new ChatChannelRef("ch"), "m1"), null,
                new MemberRef("user-1"), new ChatContent(text), Instant.now());
    }
}

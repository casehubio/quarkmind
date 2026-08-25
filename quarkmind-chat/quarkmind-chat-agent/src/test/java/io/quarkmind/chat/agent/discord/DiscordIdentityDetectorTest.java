package io.quarkmind.chat.agent.discord;

import io.casehub.connectors.chat.model.ChatChannelRef;
import io.casehub.connectors.chat.model.ChatContent;
import io.casehub.connectors.chat.model.ChatMessageRef;
import io.casehub.connectors.chat.model.MemberRef;
import io.casehub.connectors.chat.model.ReceivedMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void botMessageIdsEvictsOldestBeyondCapacity() {
        var bounded = new DiscordIdentityDetector("bot-1", 3);
        bounded.recordBotMessage("a");
        bounded.recordBotMessage("b");
        bounded.recordBotMessage("c");
        bounded.recordBotMessage("d");

        var parentA = new ChatMessageRef(new ChatChannelRef("ch"), "a");
        var replyA = new ReceivedMessage("discord", new ChatChannelRef("ch"),
                                         new ChatMessageRef(new ChatChannelRef("ch"), "r1"),
                                         parentA, new MemberRef("user"), new ChatContent("reply"), Instant.now());
        assertFalse(bounded.isReplyToBot(replyA), "oldest 'a' should be evicted");

        var parentD = new ChatMessageRef(new ChatChannelRef("ch"), "d");
        var replyD = new ReceivedMessage("discord", new ChatChannelRef("ch"),
                                         new ChatMessageRef(new ChatChannelRef("ch"), "r2"),
                                         parentD, new MemberRef("user"), new ChatContent("reply"), Instant.now());
        assertTrue(bounded.isReplyToBot(replyD), "newest 'd' should be present");
    }


    private ReceivedMessage msg(String text) {
        return new ReceivedMessage("discord", new ChatChannelRef("ch"),
                new ChatMessageRef(new ChatChannelRef("ch"), "m1"), null,
                new MemberRef("user-1"), new ChatContent(text), Instant.now());
    }
}

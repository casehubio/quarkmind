package io.quarkmind.chat.protocol;

import io.casehub.connectors.chat.model.ChatChannelRef;
import io.casehub.connectors.chat.model.ChatContent;
import io.casehub.connectors.chat.model.ChatMessageRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChatIntentTest {

    @Test
    void sendCreatesWithChannelAndContent() {
        var content = new ChatContent("hello", null, List.of(), List.of());
        var intent = new ChatIntent.Send("channel-1", content);
        assertEquals("channel-1", intent.channelId());
        assertEquals("hello", intent.content().text());
    }

    @Test
    void replyCreatesWithParentAndContent() {
        var channel = new ChatChannelRef("ch-1");
        var parent = new ChatMessageRef(channel, "msg-1");
        var content = new ChatContent("reply text", null, List.of(), List.of());
        var intent = new ChatIntent.Reply(parent, content);
        assertEquals("msg-1", intent.parent().messageId());
    }

    @Test
    void reactCreatesWithMessageAndEmoji() {
        var channel = new ChatChannelRef("ch-1");
        var msgRef = new ChatMessageRef(channel, "msg-1");
        var intent = new ChatIntent.React(msgRef, "👀");
        assertEquals("👀", intent.emoji());
    }

    @Test
    void sealedPermitsExactlyThreeVariants() {
        var permits = ChatIntent.class.getPermittedSubclasses();
        assertNotNull(permits);
        assertEquals(3, permits.length);
    }
}

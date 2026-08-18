package io.quarkmind.chat.protocol;

import io.casehub.connectors.chat.model.ChatContent;
import io.casehub.connectors.chat.model.ChatMessageRef;
import io.quarkmind.agency.intent.Intent;

public sealed interface ChatIntent extends Intent {
    record Send(String channelId, ChatContent content) implements ChatIntent {}
    record Reply(ChatMessageRef parent, ChatContent content) implements ChatIntent {}
    record React(ChatMessageRef message, String emoji) implements ChatIntent {}
}

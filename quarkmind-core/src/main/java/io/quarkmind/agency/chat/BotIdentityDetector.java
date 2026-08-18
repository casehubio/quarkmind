package io.quarkmind.agency.chat;

import io.casehub.connectors.chat.model.ReceivedMessage;

public interface BotIdentityDetector {
    boolean isMention(ReceivedMessage message);
    boolean isReplyToBot(ReceivedMessage message);
    String botUserId();
}

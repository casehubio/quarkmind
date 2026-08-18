package io.quarkmind.chat.agent.discord;

import io.casehub.connectors.chat.model.ReceivedMessage;
import io.quarkmind.agency.chat.BotIdentityDetector;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class DiscordIdentityDetector implements BotIdentityDetector {

    private final String botUserId;
    private final String mentionPattern;
    private final Set<String> botMessageIds = ConcurrentHashMap.newKeySet();

    public DiscordIdentityDetector(String botUserId) {
        this.botUserId = botUserId;
        this.mentionPattern = "<@" + botUserId + ">";
    }

    @Override
    public boolean isMention(ReceivedMessage message) {
        String text = message.content().text();
        return text != null && text.contains(mentionPattern);
    }

    @Override
    public boolean isReplyToBot(ReceivedMessage message) {
        return message.parentRef() != null
                && botMessageIds.contains(message.parentRef().messageId());
    }

    @Override
    public String botUserId() {
        return botUserId;
    }

    public void recordBotMessage(String messageId) {
        botMessageIds.add(messageId);
    }
}

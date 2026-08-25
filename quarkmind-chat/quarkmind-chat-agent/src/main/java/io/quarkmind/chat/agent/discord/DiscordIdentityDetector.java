package io.quarkmind.chat.agent.discord;

import io.casehub.connectors.chat.model.ReceivedMessage;
import io.quarkmind.agency.chat.BotIdentityDetector;

import java.util.LinkedHashSet;

public class DiscordIdentityDetector implements BotIdentityDetector {

    private static final int DEFAULT_MAX_TRACKED = 10_000;

    private final String                botUserId;
    private final String                mentionPattern;
    private final int                   maxTracked;
    private final LinkedHashSet<String> botMessageIds = new LinkedHashSet<>();

    public DiscordIdentityDetector(String botUserId) {
        this(botUserId, DEFAULT_MAX_TRACKED);
    }

    public DiscordIdentityDetector(String botUserId, int maxTracked) {
        this.botUserId      = botUserId;
        this.mentionPattern = "<@" + botUserId + ">";
        this.maxTracked     = maxTracked;
    }

    @Override
    public boolean isMention(ReceivedMessage message) {
        String text = message.content().text();
        return text != null && text.contains(mentionPattern);
    }

    @Override
    public synchronized boolean isReplyToBot(ReceivedMessage message) {
        return message.parentRef() != null
               && botMessageIds.contains(message.parentRef().messageId());
    }

    @Override
    public String botUserId() {
        return botUserId;
    }

    public synchronized void recordBotMessage(String messageId) {
        botMessageIds.add(messageId);
        while (botMessageIds.size() > maxTracked) {
            var it = botMessageIds.iterator();
            it.next();
            it.remove();
        }
    }
}

package io.quarkmind.agency.chat;

import java.util.List;

public record ConversationThread(
        String threadId,
        List<ClassifiedMessage> messages,
        boolean isNew,
        AttentionPriority highestPriority
) {
    public int messageCount() { return messages.size(); }
}

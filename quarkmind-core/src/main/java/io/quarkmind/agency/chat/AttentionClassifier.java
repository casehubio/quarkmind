package io.quarkmind.agency.chat;

import io.casehub.connectors.chat.model.ReceivedMessage;

import java.util.List;
import java.util.Set;

public final class AttentionClassifier {

    private AttentionClassifier() {}

    public static List<ClassifiedMessage> classify(
            List<ReceivedMessage> messages,
            BotIdentityDetector detector,
            Set<String> participatedThreadIds) {
        return messages.stream()
                .map(msg -> new ClassifiedMessage(msg, priorityOf(msg, detector, participatedThreadIds)))
                .toList();
    }

    private static AttentionPriority priorityOf(
            ReceivedMessage msg,
            BotIdentityDetector detector,
            Set<String> participatedThreadIds) {
        if (detector.isMention(msg) || detector.isReplyToBot(msg)) {
            return AttentionPriority.DIRECT;
        }
        if (msg.parentRef() != null
                && participatedThreadIds.contains(msg.parentRef().messageId())) {
            return AttentionPriority.ELEVATED;
        }
        return AttentionPriority.AMBIENT;
    }
}

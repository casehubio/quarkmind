package io.quarkmind.agency.chat;

import io.casehub.connectors.chat.model.ReceivedMessage;

import java.util.*;

public record ChatDeltaReport(Map<String, List<ConversationThread>> channelThreads) {

    public List<ConversationThread> threads(String channelId) {
        return channelThreads.getOrDefault(channelId, List.of());
    }

    public Set<String> allChannels() {
        return channelThreads.keySet();
    }

    public List<ClassifiedMessage> directMessages() {
        return channelThreads.values().stream()
                .flatMap(List::stream)
                .filter(t -> t.highestPriority() == AttentionPriority.DIRECT)
                .flatMap(t -> t.messages().stream())
                .toList();
    }

    public int totalMessageCount() {
        return channelThreads.values().stream()
                .flatMap(List::stream)
                .mapToInt(ConversationThread::messageCount)
                .sum();
    }

    public static ChatDeltaReport build(
            Map<String, List<ReceivedMessage>> channelDeltas,
            BotIdentityDetector detector,
            Set<String> participatedThreadIds) {

        var result = new LinkedHashMap<String, List<ConversationThread>>();
        for (var entry : channelDeltas.entrySet()) {
            String channelId = entry.getKey();
            List<ReceivedMessage> messages = entry.getValue();
            List<ClassifiedMessage> classified =
                    AttentionClassifier.classify(messages, detector, participatedThreadIds);

            Map<String, List<ClassifiedMessage>> threadGroups = new LinkedHashMap<>();
            for (ClassifiedMessage cm : classified) {
                String threadId = cm.message().parentRef() != null
                        ? cm.message().parentRef().messageId()
                        : cm.message().messageRef().messageId();
                threadGroups.computeIfAbsent(threadId, k -> new ArrayList<>()).add(cm);
            }

            List<ConversationThread> threads = threadGroups.entrySet().stream()
                    .map(e -> {
                        AttentionPriority highest = e.getValue().stream()
                                .map(ClassifiedMessage::priority)
                                .min(Comparator.comparingInt(Enum::ordinal))
                                .orElse(AttentionPriority.AMBIENT);
                        boolean isNew = !participatedThreadIds.contains(e.getKey());
                        return new ConversationThread(e.getKey(), List.copyOf(e.getValue()), isNew, highest);
                    })
                    .toList();

            result.put(channelId, threads);
        }
        return new ChatDeltaReport(Map.copyOf(result));
    }
}

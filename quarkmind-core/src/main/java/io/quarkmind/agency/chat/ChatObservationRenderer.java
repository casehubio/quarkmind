package io.quarkmind.agency.chat;

import java.util.List;
import java.util.stream.Collectors;

public class ChatObservationRenderer {

    private final int ambientVerbatimThreshold;

    public ChatObservationRenderer(int ambientVerbatimThreshold) {
        this.ambientVerbatimThreshold = ambientVerbatimThreshold;
    }

    public String renderDelta(ChatDeltaReport report) {
        var sb = new StringBuilder();
        for (String channelId : report.allChannels()) {
            sb.append("Channel #").append(channelId).append(":\n");
            var priorityThreads = new java.util.ArrayList<ConversationThread>();
            var ambientMessages = new java.util.ArrayList<ClassifiedMessage>();
            for (ConversationThread thread : report.threads(channelId)) {
                if (thread.highestPriority() == AttentionPriority.DIRECT
                        || thread.highestPriority() == AttentionPriority.ELEVATED) {
                    priorityThreads.add(thread);
                } else {
                    ambientMessages.addAll(thread.messages());
                }
            }
            for (ConversationThread thread : priorityThreads) {
                renderVerbatim(thread.messages(), sb);
            }
            if (!ambientMessages.isEmpty()) {
                renderAmbient(ambientMessages, sb);
            }
        }
        return sb.toString();
    }

    private void renderVerbatim(List<ClassifiedMessage> messages, StringBuilder sb) {
        for (ClassifiedMessage cm : messages) {
            sb.append("  ").append(cm.message().sender().id()).append(": ")
                    .append(cm.message().content().text()).append("\n");
        }
    }

    private void renderAmbient(List<ClassifiedMessage> messages, StringBuilder sb) {
        if (messages.size() <= ambientVerbatimThreshold) {
            renderVerbatim(messages, sb);
        } else {
            var bySender = messages.stream()
                    .collect(Collectors.groupingBy(cm -> cm.message().sender().id()));
            sb.append("  [").append(messages.size()).append(" ambient messages from ");
            sb.append(bySender.keySet().stream().collect(Collectors.joining(", ")));
            sb.append("]\n");
        }
    }
}

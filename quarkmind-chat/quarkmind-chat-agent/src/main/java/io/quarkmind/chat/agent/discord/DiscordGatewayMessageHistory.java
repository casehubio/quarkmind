package io.quarkmind.chat.agent.discord;

import io.casehub.connectors.chat.model.ChatChannelRef;
import io.casehub.connectors.chat.model.ReceivedMessage;
import io.casehub.connectors.chat.spi.MessageHistory;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class DiscordGatewayMessageHistory implements MessageHistory {

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<ReceivedMessage>> buffer =
            new ConcurrentHashMap<>();

    public void accumulate(ReceivedMessage message) {
        buffer.computeIfAbsent(message.channel().id(), k -> new CopyOnWriteArrayList<>())
                .add(message);
    }

    @Override
    public List<ReceivedMessage> messages(ChatChannelRef channel, Instant since) {
        var channelMessages = buffer.get(channel.id());
        if (channelMessages == null) return List.of();
        return channelMessages.stream()
                .filter(m -> m.receivedAt().isAfter(since))
                .toList();
    }

    public void drain(Instant before) {
        for (var messages : buffer.values()) {
            messages.removeIf(m -> !m.receivedAt().isAfter(before));
        }
    }
}

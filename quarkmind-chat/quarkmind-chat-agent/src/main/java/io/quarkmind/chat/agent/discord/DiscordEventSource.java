package io.quarkmind.chat.agent.discord;

import io.casehub.blocks.agentic.model.DriverEvent;
import io.casehub.blocks.agentic.model.EventSource;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class DiscordEventSource implements EventSource {

    private final Set<Consumer<DriverEvent>> subscribers = ConcurrentHashMap.newKeySet();

    @Override
    public Cancellation subscribe(Consumer<DriverEvent> sink) {
        subscribers.add(sink);
        return Cancellation.of(() -> subscribers.remove(sink));
    }

    public void onMessage(String channelId, String senderId, String content) {
        var event = new DriverEvent("discord", java.util.Map.of(
                "channelId", channelId, "senderId", senderId));
        subscribers.forEach(s -> s.accept(event));
    }
}

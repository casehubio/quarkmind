package io.quarkmind.chat.agent;

import io.casehub.connectors.chat.model.*;
import io.casehub.connectors.chat.spi.MessageHistory;
import io.quarkmind.agency.chat.BotIdentityDetector;
import io.quarkmind.agency.intent.IntentQueue;
import io.quarkmind.chat.protocol.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ChatWorldBridgeTest {

    @Test
    void perceiveReturnsChannelDeltas() {
        var messages = List.of(dummyMessage("hello", "ch-1"));
        var history = stubHistory(Map.of("ch-1", messages));
        var bridge = new ChatWorldBridge(history, List.of("ch-1"), stubDetector());
        var perception = bridge.perceive(WakeReason.MESSAGE);
        assertEquals(1, perception.channelDeltas().get("ch-1").size());
        assertEquals(WakeReason.MESSAGE, perception.reason());
    }

    @Test
    void perceivePassesWakeReasonPerCall() {
        var history = stubHistory(Map.of());
        var bridge = new ChatWorldBridge(history, List.of(), stubDetector());
        assertEquals(WakeReason.MESSAGE, bridge.perceive(WakeReason.MESSAGE).reason());
        assertEquals(WakeReason.HEARTBEAT, bridge.perceive(WakeReason.HEARTBEAT).reason());
    }

    @Test
    void perceiveDefaultUsesMessageReason() {
        var history = stubHistory(Map.of());
        var bridge = new ChatWorldBridge(history, List.of(), stubDetector());
        assertEquals(WakeReason.MESSAGE, bridge.perceive().reason());
    }

    @Test
    void dispatchRoutesSendToMessaging() {
        var sent = new AtomicReference<String>();
        var history = stubHistory(Map.of());
        var bridge = new ChatWorldBridge(history, List.of(), stubDetector());
        bridge.setMessaging((channel, content) -> {
            sent.set(content.text());
            return SendResult.success(new ChatMessageRef(channel, "sent-1"), Instant.now());
        });

        var queue = new IntentQueue<ChatIntent>();
        queue.enqueue(new ChatIntent.Send("ch-1", new ChatContent("hi")));
        bridge.dispatch(queue);
        assertEquals("hi", sent.get());
    }

    @Test
    void dispatchRoutesReplyToThreading() {
        var replied = new AtomicReference<String>();
        var history = stubHistory(Map.of());
        var bridge = new ChatWorldBridge(history, List.of(), stubDetector());
        bridge.setThreading((parent, content) -> {
            replied.set(content.text());
            return SendResult.success(new ChatMessageRef(parent.channel(), "sent-2"), Instant.now());
        });

        var queue = new IntentQueue<ChatIntent>();
        var parent = new ChatMessageRef(new ChatChannelRef("ch-1"), "m1");
        queue.enqueue(new ChatIntent.Reply(parent, new ChatContent("reply text")));
        bridge.dispatch(queue);
        assertEquals("reply text", replied.get());
    }

    @Test
    void dispatchRoutesReactToReactions() {
        var reacted = new AtomicReference<String>();
        var history = stubHistory(Map.of());
        var bridge = new ChatWorldBridge(history, List.of(), stubDetector());
        bridge.setReactions(new io.casehub.connectors.chat.spi.Reactions() {
            @Override public void add(ChatMessageRef ref, String emoji) { reacted.set(emoji); }
            @Override public void remove(ChatMessageRef ref, String emoji) {}
            @Override public List<String> list(ChatMessageRef ref) { return List.of(); }
        });

        var queue = new IntentQueue<ChatIntent>();
        var msgRef = new ChatMessageRef(new ChatChannelRef("ch"), "m1");
        queue.enqueue(new ChatIntent.React(msgRef, "👀"));
        bridge.dispatch(queue);
        assertEquals("👀", reacted.get());
    }

    @Test
    void perceiveOnlyReturnsNewMessagesSinceLastCheck() {
        var msg1 = dummyMessage("first", "ch-1");
        var history = new io.quarkmind.chat.agent.discord.DiscordGatewayMessageHistory();
        history.accumulate(msg1);

        var bridge = new ChatWorldBridge(history, List.of("ch-1"), stubDetector());
        var first = bridge.perceive(WakeReason.MESSAGE);
        assertEquals(1, first.channelDeltas().getOrDefault("ch-1", List.of()).size());

        var second = bridge.perceive(WakeReason.MESSAGE);
        assertTrue(second.channelDeltas().getOrDefault("ch-1", List.of()).isEmpty());
    }

    private ReceivedMessage dummyMessage(String text, String channelId) {
        return new ReceivedMessage("discord", new ChatChannelRef(channelId),
                new ChatMessageRef(new ChatChannelRef(channelId), "m1"), null,
                new MemberRef("user-1"), new ChatContent(text), Instant.now());
    }

    private BotIdentityDetector stubDetector() {
        return new BotIdentityDetector() {
            @Override public boolean isMention(ReceivedMessage msg) { return false; }
            @Override public boolean isReplyToBot(ReceivedMessage msg) { return false; }
            @Override public String botUserId() { return "bot"; }
        };
    }

    private MessageHistory stubHistory(Map<String, List<ReceivedMessage>> data) {
        return (channel, since) -> data.getOrDefault(channel.id(), List.of());
    }
}

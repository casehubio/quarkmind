package io.quarkmind.chat.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.connectors.chat.degraded.NoOpReactions;
import io.casehub.connectors.chat.model.*;
import io.quarkmind.agency.AgencyContext;
import io.quarkmind.agency.chat.BotIdentityDetector;
import io.quarkmind.agency.chat.ChatObservationRenderer;
import io.quarkmind.agency.intent.IntentQueue;
import io.quarkmind.agency.llm.LlmRequest;
import io.quarkmind.agency.llm.LlmRequestQueue;
import io.quarkmind.agency.needs.NeedState;
import io.quarkmind.agency.schedule.OutputGovernor;
import io.quarkmind.chat.agent.discord.DiscordGatewayMessageHistory;
import io.quarkmind.chat.protocol.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ChatAgentEndToEndTest {

    @Test
    void fullCycleFromMessageToResponse() {
        var mapper = new ObjectMapper();
        var detector = stubDetector();
        var llmQueue = stubLlmQueue();

        var llm = (ChatAgencyLoop.LlmInvoker) (sys, usr, id) ->
                "{\"action\":\"SEND\",\"channel\":\"general\",\"text\":\"Hey there!\"}";

        var renderer = new ChatObservationRenderer(10);
        var perceptionBridge = new DefaultChatPerceptionBridge(renderer);
        var loop = new ChatAgencyLoop(llm, detector, llmQueue, mapper, perceptionBridge);
        loop.setSystemPrompt("You are Quark, a friendly chat character.");

        var gatewayHistory = new DiscordGatewayMessageHistory();
        var incomingMsg = new ReceivedMessage("discord", new ChatChannelRef("general"),
                new ChatMessageRef(new ChatChannelRef("general"), "m1"), null,
                new MemberRef("alice"), new ChatContent("hi everyone!"), Instant.now());
        gatewayHistory.accumulate(incomingMsg);

        var bridge = new ChatWorldBridge(gatewayHistory, List.of("general"), detector);

        var sent = new AtomicReference<String>();
        bridge.setMessaging((channel, content) -> {
            sent.set(content.text());
            return SendResult.success(new ChatMessageRef(channel, "sent-1"), Instant.now());
        });
        bridge.setThreading((parent, content) ->
                SendResult.success(new ChatMessageRef(parent.channel(), "sent-2"), Instant.now()));
        bridge.setReactions(new NoOpReactions());

        var perception = bridge.perceive(WakeReason.MESSAGE);
        var context = new AgencyContext(new NeedState());
        context.put("perception", perception);
        loop.tick(context);

        @SuppressWarnings("unchecked")
        var intents = (List<ChatIntent>) context.get("intents");
        var governor = new OutputGovernor(300_000, 0, 10);

        var queue = new IntentQueue<ChatIntent>();
        for (ChatIntent intent : intents) {
            if (governor.allow()) {
                queue.enqueue(intent);
                governor.recordAction();
            }
        }
        bridge.dispatch(queue);

        assertEquals("Hey there!", sent.get());
    }

    @Test
    void heartbeatWithNoMessagesDoesNotInvokeLlm() {
        var mapper = new ObjectMapper();
        var detector = stubDetector();
        var llmQueue = stubLlmQueue();
        var invoked = new java.util.concurrent.atomic.AtomicBoolean(false);

        var llm = (ChatAgencyLoop.LlmInvoker) (sys, usr, id) -> {
            invoked.set(true);
            return "{\"action\":\"WAIT\"}";
        };

        var loop = new ChatAgencyLoop(llm, detector, llmQueue, mapper,
                new DefaultChatPerceptionBridge(new ChatObservationRenderer(10)));

        var perception = new ChatPerception(Map.of(), Map.of(), WakeReason.HEARTBEAT);
        var context = new AgencyContext(new NeedState());
        context.put("perception", perception);
        loop.tick(context);

        assertFalse(invoked.get());
    }

    @Test
    void governorBlocksExcessiveActions() {
        var mapper = new ObjectMapper();
        var detector = stubDetector();
        var llmQueue = stubLlmQueue();

        var llm = (ChatAgencyLoop.LlmInvoker) (sys, usr, id) ->
                "{\"action\":\"SEND\",\"channel\":\"ch\",\"text\":\"hi\"}";

        var loop = new ChatAgencyLoop(llm, detector, llmQueue, mapper,
                new DefaultChatPerceptionBridge(new ChatObservationRenderer(10)));

        var governor = new OutputGovernor(300_000, 0, 1);

        var dispatched = new java.util.concurrent.atomic.AtomicInteger(0);
        var gatewayHistory = new DiscordGatewayMessageHistory();
        var bridge = new ChatWorldBridge(gatewayHistory, List.of("ch"), detector);
        bridge.setMessaging((channel, content) -> {
            dispatched.incrementAndGet();
            return SendResult.success(new ChatMessageRef(channel, "s1"), Instant.now());
        });
        bridge.setReactions(new NoOpReactions());

        for (int i = 0; i < 3; i++) {
            gatewayHistory.accumulate(new ReceivedMessage("discord", new ChatChannelRef("ch"),
                    new ChatMessageRef(new ChatChannelRef("ch"), "m" + i), null,
                    new MemberRef("user"), new ChatContent("msg " + i), Instant.now()));

            var perception = bridge.perceive(WakeReason.MESSAGE);
            var context = new AgencyContext(new NeedState());
            context.put("perception", perception);
            loop.tick(context);

            @SuppressWarnings("unchecked")
            var intents = (List<ChatIntent>) context.get("intents");
            if (intents != null) {
                for (ChatIntent intent : intents) {
                    if (governor.allow()) {
                        var queue = new IntentQueue<ChatIntent>();
                        queue.enqueue(intent);
                        bridge.dispatch(queue);
                        governor.recordAction();
                    }
                }
            }
        }

        assertEquals(1, dispatched.get());
    }

    private BotIdentityDetector stubDetector() {
        return new BotIdentityDetector() {
            @Override public boolean isMention(ReceivedMessage msg) { return false; }
            @Override public boolean isReplyToBot(ReceivedMessage msg) { return false; }
            @Override public String botUserId() { return "bot-id"; }
        };
    }

    private LlmRequestQueue stubLlmQueue() {
        return new LlmRequestQueue() {
            @Override public void submit(LlmRequest request) {}
            @Override public int pendingCount() { return 0; }
            @Override public boolean hasCapacity() { return true; }
        };
    }
}

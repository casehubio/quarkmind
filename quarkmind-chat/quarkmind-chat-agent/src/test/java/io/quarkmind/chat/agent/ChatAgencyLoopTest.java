package io.quarkmind.chat.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.connectors.chat.model.*;
import io.quarkmind.agency.AgencyContext;
import io.quarkmind.agency.chat.BotIdentityDetector;
import io.quarkmind.agency.chat.ChatObservationRenderer;
import io.quarkmind.agency.llm.LlmRequest;
import io.quarkmind.agency.llm.LlmRequestQueue;
import io.quarkmind.agency.needs.NeedState;
import io.quarkmind.chat.protocol.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ChatAgencyLoopTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final BotIdentityDetector detector = stubDetector();
    private final LlmRequestQueue llmQueue = stubLlmQueue(true);

    @Test
    void tickProducesIntentsFromLlmResponse() {
        var llm = (ChatAgencyLoop.LlmInvoker) (system, user, id) ->
                "{\"action\":\"SEND\",\"channel\":\"ch-1\",\"text\":\"hello back\"}";

        var loop = createLoop(llm);
        loop.setSystemPrompt("You are a friendly bot.");

        var perception = perceptionWithMessage("hello", "ch-1");
        var context = contextWith(perception);
        loop.tick(context);

        @SuppressWarnings("unchecked")
        var intents = (List<ChatIntent>) context.get("intents");
        assertNotNull(intents);
        assertEquals(1, intents.size());
        assertInstanceOf(ChatIntent.Send.class, intents.get(0));
        assertEquals("hello back", ((ChatIntent.Send) intents.get(0)).content().text());
    }

    @Test
    void heartbeatWithNoActivityProducesNoIntents() {
        var llm = (ChatAgencyLoop.LlmInvoker) (system, user, id) ->
                "{\"action\":\"WAIT\"}";

        var loop = createLoop(llm);
        var perception = new ChatPerception(Map.of(), Map.of(), WakeReason.HEARTBEAT);
        var context = contextWith(perception);
        loop.tick(context);

        @SuppressWarnings("unchecked")
        var intents = (List<ChatIntent>) context.get("intents");
        assertTrue(intents == null || intents.isEmpty());
    }

    @Test
    void skipsLlmWhenQueueAtCapacity() {
        var invoked = new AtomicBoolean(false);
        var llm = (ChatAgencyLoop.LlmInvoker) (system, user, id) -> {
            invoked.set(true);
            return "{\"action\":\"WAIT\"}";
        };

        var loop = new ChatAgencyLoop(llm, detector, stubLlmQueue(false), mapper,
                new DefaultChatPerceptionBridge(new ChatObservationRenderer(10)));

        var perception = perceptionWithMessage("hi", "ch");
        var context = contextWith(perception);
        loop.tick(context);
        assertFalse(invoked.get());
    }

    @Test
    void parsesReactIntent() {
        var llm = (ChatAgencyLoop.LlmInvoker) (system, user, id) ->
                "{\"action\":\"REACT\",\"messageId\":\"m1\",\"emoji\":\"👀\"}";
        var loop = createLoop(llm);
        var perception = perceptionWithMessage("interesting", "ch");
        var context = contextWith(perception);
        loop.tick(context);

        @SuppressWarnings("unchecked")
        var intents = (List<ChatIntent>) context.get("intents");
        assertEquals(1, intents.size());
        assertInstanceOf(ChatIntent.React.class, intents.get(0));
        assertEquals("👀", ((ChatIntent.React) intents.get(0)).emoji());
    }

    @Test
    void parsesReplyIntent() {
        var llm = (ChatAgencyLoop.LlmInvoker) (system, user, id) ->
                "{\"action\":\"REPLY\",\"replyTo\":\"m1\",\"text\":\"sure thing\"}";
        var loop = createLoop(llm);
        var perception = perceptionWithMessage("can you help?", "ch");
        var context = contextWith(perception);
        loop.tick(context);

        @SuppressWarnings("unchecked")
        var intents = (List<ChatIntent>) context.get("intents");
        assertEquals(1, intents.size());
        assertInstanceOf(ChatIntent.Reply.class, intents.get(0));
        assertEquals("sure thing", ((ChatIntent.Reply) intents.get(0)).content().text());
    }

    @Test
    void waitActionProducesEmptyIntents() {
        var llm = (ChatAgencyLoop.LlmInvoker) (system, user, id) ->
                "{\"action\":\"WAIT\"}";
        var loop = createLoop(llm);
        var perception = perceptionWithMessage("hey", "ch");
        var context = contextWith(perception);
        loop.tick(context);

        @SuppressWarnings("unchecked")
        var intents = (List<ChatIntent>) context.get("intents");
        assertNotNull(intents);
        assertTrue(intents.isEmpty());
    }

    @Test
    void malformedJsonProducesEmptyIntents() {
        var llm = (ChatAgencyLoop.LlmInvoker) (system, user, id) ->
                "this is not json at all";
        var loop = createLoop(llm);
        var perception = perceptionWithMessage("hey", "ch");
        var context = contextWith(perception);
        loop.tick(context);

        @SuppressWarnings("unchecked")
        var intents = (List<ChatIntent>) context.get("intents");
        assertNotNull(intents);
        assertTrue(intents.isEmpty());
    }

    @Test
    void noPerceptionSkipsTick() {
        var invoked = new AtomicBoolean(false);
        var llm = (ChatAgencyLoop.LlmInvoker) (system, user, id) -> {
            invoked.set(true);
            return "{\"action\":\"WAIT\"}";
        };
        var loop = createLoop(llm);
        var context = new AgencyContext(new NeedState());
        loop.tick(context);
        assertFalse(invoked.get());
    }

    private ChatAgencyLoop createLoop(ChatAgencyLoop.LlmInvoker llm) {
        return new ChatAgencyLoop(llm, detector, llmQueue, mapper,
                new DefaultChatPerceptionBridge(new ChatObservationRenderer(10)));
    }

    private ChatPerception perceptionWithMessage(String text, String channelId) {
        var msg = new ReceivedMessage("discord", new ChatChannelRef(channelId),
                new ChatMessageRef(new ChatChannelRef(channelId), "m1"), null,
                new MemberRef("user-1"), new ChatContent(text), Instant.now());
        return new ChatPerception(Map.of(channelId, List.of(msg)), Map.of(), WakeReason.MESSAGE);
    }

    private AgencyContext contextWith(ChatPerception perception) {
        var context = new AgencyContext(new NeedState());
        context.put("perception", perception);
        return context;
    }

    private BotIdentityDetector stubDetector() {
        return new BotIdentityDetector() {
            @Override public boolean isMention(ReceivedMessage msg) { return false; }
            @Override public boolean isReplyToBot(ReceivedMessage msg) { return false; }
            @Override public String botUserId() { return "bot-id"; }
        };
    }

    private LlmRequestQueue stubLlmQueue(boolean hasCapacity) {
        return new LlmRequestQueue() {
            @Override public void submit(LlmRequest request) {}
            @Override public int pendingCount() { return hasCapacity ? 0 : 100; }
            @Override public boolean hasCapacity() { return hasCapacity; }
        };
    }
}

package io.quarkmind.chat.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.connectors.chat.model.ChatChannelRef;
import io.casehub.connectors.chat.model.ChatContent;
import io.casehub.connectors.chat.model.ChatMessageRef;
import io.casehub.connectors.chat.model.MemberRef;
import io.casehub.connectors.chat.model.ReceivedMessage;
import io.casehub.neocortex.memory.Memory;
import io.casehub.neocortex.memory.experience.ExperienceEvents;
import io.casehub.neocortex.memory.personality.PersonalityWeights;
import io.quarkmind.agency.AgencyContext;
import io.quarkmind.agency.chat.BotIdentityDetector;
import io.quarkmind.agency.chat.ChatObservationRenderer;
import io.quarkmind.agency.llm.LlmPriority;
import io.quarkmind.agency.llm.LlmRequest;
import io.quarkmind.agency.llm.LlmRequestQueue;
import io.quarkmind.agency.needs.NeedState;
import io.quarkmind.agency.schedule.IdleReflectionTrigger;
import io.quarkmind.chat.protocol.ChatIntent;
import io.quarkmind.chat.protocol.ChatPerception;
import io.quarkmind.chat.protocol.WakeReason;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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


    @Test
    void tickRetrievesMemoriesBeforeLlm() {
        var recallCalled = new AtomicBoolean(false);
        var store        = new ChatMemoryFacadeTest.RecordingMemoryStore();
        var facade = new ChatMemoryFacade(store, store, false) {
            @Override
            public List<Memory> recall(String agentId, String tenantId,
                                       String ctx, Set<String> pids, PersonalityWeights w, Instant now) {
                recallCalled.set(true);
                return List.of();
            }
        };

        var llm = (ChatAgencyLoop.LlmInvoker) (system, user, id) ->
                                                      "{\"action\":\"WAIT\",\"observation\":\"Saw a greeting\"}";
        var loop = createLoopWithMemory(llm, facade);
        loop.tick(contextWith(perceptionWithMessage("hi", "ch")));
        assertTrue(recallCalled.get());
    }

    @Test
    void tickIngestsObservationFromLlmResponse() {
        var store  = new ChatMemoryFacadeTest.RecordingMemoryStore();
        var facade = new ChatMemoryFacade(store, store, false);

        var llm = (ChatAgencyLoop.LlmInvoker) (system, user, id) ->
                                                      "{\"action\":\"SEND\",\"channel\":\"ch-1\",\"text\":\"hi\",\"observation\":\"Greeted the channel\"}";
        var loop = createLoopWithMemory(llm, facade);
        loop.setAgentId("agent-1");
        loop.tick(contextWith(perceptionWithMessage("hello", "ch-1")));

        assertEquals(1, store.stored.size());
        assertTrue(store.stored.get(0).text().contains("Greeted the channel"));
    }

    @Test
    void tickSubmitsAsyncImportanceScoring() {
        var store     = new ChatMemoryFacadeTest.RecordingMemoryStore();
        var facade    = new ChatMemoryFacade(store, store, false);
        var submitted = new ArrayList<LlmRequest>();
        var queue = new LlmRequestQueue() {
            @Override
            public void submit(LlmRequest r) {submitted.add(r);}

            @Override
            public int pendingCount()        {return 0;}

            @Override
            public boolean hasCapacity()     {return true;}
        };

        var llm = (ChatAgencyLoop.LlmInvoker) (system, user, id) ->
                                                      "{\"action\":\"WAIT\",\"observation\":\"Nothing happened\"}";
        var loop = new ChatAgencyLoop(llm, detector, queue, mapper,
                                      new DefaultChatPerceptionBridge(new ChatObservationRenderer(10)),
                                      facade, new IdleReflectionTrigger(3.0, 5));
        loop.setAgentId("agent-1");
        loop.tick(contextWith(perceptionWithMessage("hey", "ch")));

        assertTrue(submitted.stream().anyMatch(r ->
                                                       r.priority() == LlmPriority.LOW && r.responseHandler() != null));
    }

    @Test
    void tickCapturesObservationOnWait() {
        var store  = new ChatMemoryFacadeTest.RecordingMemoryStore();
        var facade = new ChatMemoryFacade(store, store, false);

        var llm = (ChatAgencyLoop.LlmInvoker) (system, user, id) ->
                                                      "{\"action\":\"WAIT\",\"observation\":\"Everyone was quiet, I decided to observe\"}";
        var loop = createLoopWithMemory(llm, facade);
        loop.setAgentId("agent-1");
        loop.tick(contextWith(perceptionWithMessage("...", "ch")));

        assertEquals(1, store.stored.size());
        assertTrue(store.stored.get(0).text().contains("decided to observe"));
    }

    @Test
    void memoryIncludedInUserPrompt() {
        var store = new ChatMemoryFacadeTest.RecordingMemoryStore();
        store.queryResults = List.of(
                new Memory("m1", "agent-1", ExperienceEvents.DOMAIN, "t1", null,
                           "Bob likes NLP", Map.of(), Instant.now().minusSeconds(3600), 0.8));
        var facade = new ChatMemoryFacade(store, store, false);

        var capturedPrompt = new AtomicBoolean(false);
        var llm = (ChatAgencyLoop.LlmInvoker) (system, user, id) -> {
            if (user.contains("What I remember") && user.contains("Bob likes NLP")) {
                capturedPrompt.set(true);
            }
            return "{\"action\":\"WAIT\",\"observation\":\"Recalled memories\"}";
        };
        var loop = createLoopWithMemory(llm, facade);
        loop.setAgentId("agent-1");
        loop.tick(contextWith(perceptionWithMessage("hello", "ch")));

        assertTrue(capturedPrompt.get());
    }


    @Test
    void heartbeatTriggersReflectionWhenThresholdMet() {
        var store   = new ChatMemoryFacadeTest.RecordingMemoryStore();
        var facade  = new ChatMemoryFacade(store, store, false);
        var trigger = new IdleReflectionTrigger(1.0, 2);
        trigger.accumulate(1.5);

        var reflectCalled = new AtomicBoolean(false);
        io.casehub.neocortex.memory.reflection.ReflectionOrchestrator orchestrator =
                (agentId, tenantId, since, max) -> {
                    reflectCalled.set(true);
                    return List.of();
                };

        var llm = (ChatAgencyLoop.LlmInvoker) (system, user, id) ->
                                                      "{\"action\":\"WAIT\",\"observation\":\"idle\"}";
        var loop = new ChatAgencyLoop(llm, detector, llmQueue, mapper,
                                      new DefaultChatPerceptionBridge(new ChatObservationRenderer(10)),
                                      facade, trigger, orchestrator);

        var heartbeat = new ChatPerception(Map.of(), Map.of(), WakeReason.HEARTBEAT);
        for (int i = 0; i < 3; i++) {
            loop.tick(contextWith(heartbeat));
        }

        assertTrue(reflectCalled.get());
        assertFalse(trigger.shouldReflect(10));
    }

    @Test
    void idleTickChecksPersonalityEvolution() {
        var store   = new ChatMemoryFacadeTest.RecordingMemoryStore();
        var facade  = new ChatMemoryFacade(store, store, false);
        var trigger = new IdleReflectionTrigger(100.0, 100);

        var evolutionChecked = new AtomicBoolean(false);
        var pipeline = new io.quarkmind.agency.personality.PersonalityEvolutionPipeline(
                (desc, ctx) -> {
                    evolutionChecked.set(true);
                    return new io.casehub.eidos.api.DispositionHealth.DispositionStatus.Aligned(Map.of());
                },
                (desc, pending) -> {throw new AssertionError("should not be called");},
                new LlmReflectionDispositionActivatorTest.RecordingSignalStore());

        var descriptor = io.casehub.eidos.api.AgentDescriptor.builder()
                                                             .agentId("agent-1").name("Test").slot("chat").tenancyId("t1")
                                                             .disposition(io.casehub.eidos.api.AgentDisposition.builder()
                                                                                                               .dispositionProfile(new io.casehub.eidos.api.DispositionValue("empathetic", 0.5))
                                                                                                               .build())
                                                             .build();

        var llm = (ChatAgencyLoop.LlmInvoker) (system, user, id) ->
                                                      "{\"action\":\"WAIT\",\"observation\":\"idle\"}";
        var loop = new ChatAgencyLoop(llm, detector, llmQueue, mapper,
                                      new DefaultChatPerceptionBridge(new io.quarkmind.agency.chat.ChatObservationRenderer(10)),
                                      facade, trigger, null, pipeline, () -> descriptor);

        var heartbeat = new ChatPerception(Map.of(), Map.of(), WakeReason.HEARTBEAT);
        loop.tick(contextWith(heartbeat));

        assertTrue(evolutionChecked.get());
    }

    @Test
    void evolvedResultUpdatesActivatorProfile() {
        var store       = new ChatMemoryFacadeTest.RecordingMemoryStore();
        var facade      = new ChatMemoryFacade(store, store, false);
        var trigger     = new IdleReflectionTrigger(100.0, 100);
        var signalStore = new LlmReflectionDispositionActivatorTest.RecordingSignalStore();

        var newProfile = List.of(
                new io.casehub.eidos.api.DispositionValue("curious", 0.6),
                new io.casehub.eidos.api.DispositionValue("empathetic", 0.4));

        var pipeline = new io.quarkmind.agency.personality.PersonalityEvolutionPipeline(
                (desc, ctx) -> new io.casehub.eidos.api.DispositionHealth.DispositionStatus.EvolutionPending(
                        () -> "DOMINANT_AUXILIARY_SWAP", "curious", Map.of()),
                (desc, pending) -> new io.casehub.eidos.api.DispositionEvolution.EvolutionResult.Evolved(
                        newProfile, "EMPATHETIC-CURIOUS", "CURIOUS-EMPATHETIC"),
                signalStore);

        var submitted = new ArrayList<io.quarkmind.agency.llm.LlmRequest>();
        var activatorQueue = new LlmRequestQueue() {
            @Override
            public void submit(io.quarkmind.agency.llm.LlmRequest r) {submitted.add(r);}

            @Override
            public int pendingCount()                                {return 0;}

            @Override
            public boolean hasCapacity()                             {return true;}
        };
        var activator = new LlmReflectionDispositionActivator(
                activatorQueue, signalStore,
                List.of(new io.casehub.eidos.api.DispositionValue("empathetic", 0.6)));

        var descriptor = io.casehub.eidos.api.AgentDescriptor.builder()
                                                             .agentId("agent-1").name("Test").slot("chat").tenancyId("t1")
                                                             .disposition(io.casehub.eidos.api.AgentDisposition.builder()
                                                                                                               .dispositionProfile(new io.casehub.eidos.api.DispositionValue("empathetic", 0.6),
                                                                                                                                   new io.casehub.eidos.api.DispositionValue("curious", 0.4))
                                                                                                               .build())
                                                             .build();

        var llm = (ChatAgencyLoop.LlmInvoker) (system, user, id) ->
                                                      "{\"action\":\"WAIT\",\"observation\":\"idle\"}";
        var loop = new ChatAgencyLoop(llm, detector, llmQueue, mapper,
                                      new DefaultChatPerceptionBridge(new io.quarkmind.agency.chat.ChatObservationRenderer(10)),
                                      facade, trigger, null, pipeline, () -> descriptor);
        loop.setDispositionActivator(activator);

        var heartbeat = new ChatPerception(Map.of(), Map.of(), WakeReason.HEARTBEAT);
        loop.tick(contextWith(heartbeat));

        // After evolution, activator profile should be updated — verify by triggering a classification
        activator.onReflection("agent-1", "t1", "test insight");
        assertFalse(submitted.isEmpty());
        assertTrue(submitted.get(0).prompt().contains("curious"));
    }


    private ChatAgencyLoop createLoop(ChatAgencyLoop.LlmInvoker llm) {
        return new ChatAgencyLoop(llm, detector, llmQueue, mapper,
                new DefaultChatPerceptionBridge(new ChatObservationRenderer(10)));
    }

    private ChatAgencyLoop createLoopWithMemory(ChatAgencyLoop.LlmInvoker llm, ChatMemoryFacade facade) {
        return new ChatAgencyLoop(llm, detector, llmQueue, mapper,
                                  new DefaultChatPerceptionBridge(new ChatObservationRenderer(10)),
                                  facade, new IdleReflectionTrigger(3.0, 5));
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

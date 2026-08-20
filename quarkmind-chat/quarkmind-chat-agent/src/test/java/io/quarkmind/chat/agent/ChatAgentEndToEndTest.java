package io.quarkmind.chat.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.connectors.chat.degraded.NoOpReactions;
import io.casehub.connectors.chat.model.ChatChannelRef;
import io.casehub.connectors.chat.model.ChatContent;
import io.casehub.connectors.chat.model.ChatMessageRef;
import io.casehub.connectors.chat.model.MemberRef;
import io.casehub.connectors.chat.model.ReceivedMessage;
import io.casehub.connectors.chat.model.SendResult;
import io.casehub.neocortex.memory.Memory;
import io.casehub.neocortex.memory.experience.ExperienceEvents;
import io.quarkmind.agency.AgencyContext;
import io.quarkmind.agency.chat.BotIdentityDetector;
import io.quarkmind.agency.chat.ChatObservationRenderer;
import io.quarkmind.agency.intent.IntentQueue;
import io.quarkmind.agency.llm.LlmPriority;
import io.quarkmind.agency.llm.LlmRequest;
import io.quarkmind.agency.llm.LlmRequestQueue;
import io.quarkmind.agency.needs.NeedState;
import io.quarkmind.agency.schedule.IdleReflectionTrigger;
import io.quarkmind.agency.schedule.OutputGovernor;
import io.quarkmind.chat.agent.discord.DiscordGatewayMessageHistory;
import io.quarkmind.chat.protocol.ChatIntent;
import io.quarkmind.chat.protocol.ChatPerception;
import io.quarkmind.chat.protocol.WakeReason;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void personalityEvolutionEndToEnd() {
        var mapper            = new ObjectMapper();
        var store             = new ChatMemoryFacadeTest.RecordingMemoryStore();
        var facade            = new ChatMemoryFacade(store, store, false);
        var reflectionTrigger = new IdleReflectionTrigger(1.0, 2);
        reflectionTrigger.accumulate(1.5);

        var signalStore     = new TrackingSignalStore();
        var scoringRequests = new ArrayList<LlmRequest>();
        var queue = new LlmRequestQueue() {
            @Override
            public void submit(LlmRequest r) {scoringRequests.add(r);}

            @Override
            public int pendingCount()        {return 0;}

            @Override
            public boolean hasCapacity()     {return true;}
        };

        var initialProfile = List.of(
                new io.casehub.eidos.api.DispositionValue("analytical", 0.35),
                new io.casehub.eidos.api.DispositionValue("empathetic", 0.20),
                new io.casehub.eidos.api.DispositionValue("playful", 0.45));
        var descriptor = io.casehub.eidos.api.AgentDescriptor.builder()
                                                             .agentId("agent-1").name("TestBot").slot("chat").tenancyId("t1")
                                                             .disposition(io.casehub.eidos.api.AgentDisposition.builder()
                                                                                                               .dispositionProfile(initialProfile)
                                                                                                               .build())
                                                             .build();

        var activator = new LlmReflectionDispositionActivator(queue, signalStore, initialProfile);
        var baseSynthesizer = new LlmReflectionSynthesizer(
                (sys, usr, id) -> "[{\"insight\":\"Users consistently seek emotional support and empathetic engagement\"}]");
        var decoratedSynthesizer = new DispositionAwareReflectionSynthesizer(baseSynthesizer, activator);

        io.casehub.neocortex.memory.reflection.ReflectionOrchestrator orchestrator =
                (agentId, tenantId, since, max) -> {
                    var sources = List.of(new Memory("m1", agentId, io.casehub.neocortex.memory.experience.ExperienceEvents.DOMAIN,
                                                     tenantId, null, "Helped someone with their feelings", Map.of(), Instant.now(), 0.8));
                    var events = decoratedSynthesizer.synthesize(agentId, tenantId, sources, 1);
                    return events.stream().map(e -> "ref-" + e.insight().hashCode()).toList();
                };

        var pipeline = new io.quarkmind.agency.personality.PersonalityEvolutionPipeline(
                (desc, ctx) -> {
                    var counts = signalStore.activationCounts(desc.agentId(), desc.tenancyId());
                    if (counts.getOrDefault("empathetic", 0) > 0) {
                        return new io.casehub.eidos.api.DispositionHealth.DispositionStatus.Drifted(
                                Map.of("empathetic", 0.4), "empathetic", 0.1);
                    }
                    return new io.casehub.eidos.api.DispositionHealth.DispositionStatus.Aligned(Map.of());
                },
                (desc, pending) -> new io.casehub.eidos.api.DispositionEvolution.EvolutionResult.Dampened(0.5),
                signalStore);

        var llm = (ChatAgencyLoop.LlmInvoker) (sys, usr, id) ->
                                                      "{\"action\":\"WAIT\",\"observation\":\"idle\"}";
        var loop = new ChatAgencyLoop(llm, stubDetector(), queue, mapper,
                                      new DefaultChatPerceptionBridge(new io.quarkmind.agency.chat.ChatObservationRenderer(10)),
                                      facade, reflectionTrigger, orchestrator, pipeline, () -> descriptor);
        loop.setAgentId("agent-1");
        loop.setDispositionActivator(activator);

        var heartbeat = new ChatPerception(Map.of(), Map.of(), WakeReason.HEARTBEAT);

        loop.tick(contextWith(heartbeat));
        loop.tick(contextWith(heartbeat));
        loop.tick(contextWith(heartbeat));

        var classificationReqs = scoringRequests.stream()
                                                .filter(r -> r.prompt().contains("empathetic") && r.prompt().contains("analytical"))
                                                .toList();
        assertFalse(classificationReqs.isEmpty(), "Classification request should have been submitted");

        classificationReqs.get(0).responseHandler().accept("empathetic");

        var counts = signalStore.activationCounts("agent-1", "t1");
        assertEquals(1, counts.getOrDefault("empathetic", 0));
    }

    private io.quarkmind.agency.AgencyContext contextWith(ChatPerception perception) {
        var context = new io.quarkmind.agency.AgencyContext(new io.quarkmind.agency.needs.NeedState());
        context.put("perception", perception);
        return context;
    }

    static class TrackingSignalStore implements io.casehub.eidos.api.DispositionSignalStore {
        private final java.util.concurrent.ConcurrentHashMap<String, Integer> counts = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public void recordActivation(String agentId, String tenancyId, String functionTerm) {
            counts.merge(functionTerm, 1, Integer::sum);
        }

        @Override
        public Map<String, Integer> activationCounts(String agentId, String tenancyId) {
            return Map.copyOf(counts);
        }

        @Override
        public void decay(String agentId, String tenancyId, double decayFactor) {}

        @Override
        public void clear(String agentId, String tenancyId)                     {counts.clear();}
    }


    @Test
    void memoryIntegrationEndToEnd() {
        var mapper            = new ObjectMapper();
        var store             = new ChatMemoryFacadeTest.RecordingMemoryStore();
        var facade            = new ChatMemoryFacade(store, store, false);
        var reflectionTrigger = new IdleReflectionTrigger(3.0, 5);
        var scoringRequests   = new ArrayList<LlmRequest>();
        var queue = new LlmRequestQueue() {
            @Override
            public void submit(LlmRequest r) {scoringRequests.add(r);}

            @Override
            public int pendingCount()        {return 0;}

            @Override
            public boolean hasCapacity()     {return true;}
        };

        var llm = (ChatAgencyLoop.LlmInvoker) (sys, usr, id) ->
                                                      "{\"action\":\"SEND\",\"channel\":\"ch-1\",\"text\":\"I remember!\","
                                                      + "\"observation\":\"Bob asked about ML. I helped.\"}";

        var loop = new ChatAgencyLoop(llm, stubDetector(), queue, mapper,
                                      new DefaultChatPerceptionBridge(new ChatObservationRenderer(10)),
                                      facade, reflectionTrigger);
        loop.setSystemPrompt("You are a friendly bot.");
        loop.setAgentId("agent-1");

        var perception = new ChatPerception(
                Map.of("ch-1", List.of(new ReceivedMessage("discord", new ChatChannelRef("ch-1"),
                                                           new ChatMessageRef(new ChatChannelRef("ch-1"), "m1"), null,
                                                           new MemberRef("bob"), new ChatContent("tell me about ML"), Instant.now()))),
                Map.of(), WakeReason.MESSAGE);
        var context = new AgencyContext(new NeedState());
        context.put("perception", perception);
        loop.tick(context);

        // Verify observation was stored
        assertEquals(1, store.stored.size());
        assertTrue(store.stored.get(0).text().contains("Bob asked about ML"));
        assertNull(store.stored.get(0).importance());

        // Verify async scoring was submitted
        assertTrue(scoringRequests.stream()
                                  .anyMatch(r -> r.priority() == LlmPriority.LOW && r.responseHandler() != null));

        // Simulate scoring callback
        var scoringReq = scoringRequests.stream()
                                        .filter(r -> r.responseHandler() != null).findFirst().orElseThrow();
        scoringReq.responseHandler().accept("0.7");
        assertEquals("mem-1", store.lastUpdatedMemoryId);
        assertEquals(0.7, store.lastUpdatedImportance, 0.001);

        // Tick 2: memory should be recalled
        store.queryResults = List.of(
                new Memory("mem-1", "agent-1", ExperienceEvents.DOMAIN, "t1", null,
                           "Bob asked about ML. I helped.", Map.of(), Instant.now(), 0.7));

        var perception2 = new ChatPerception(
                Map.of("ch-1", List.of(new ReceivedMessage("discord", new ChatChannelRef("ch-1"),
                                                           new ChatMessageRef(new ChatChannelRef("ch-1"), "m2"), null,
                                                           new MemberRef("bob"), new ChatContent("what did we talk about?"), Instant.now()))),
                Map.of(), WakeReason.MESSAGE);
        var context2 = new AgencyContext(new NeedState());
        context2.put("perception", perception2);
        loop.tick(context2);

        assertNotNull(store.lastQuery);
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

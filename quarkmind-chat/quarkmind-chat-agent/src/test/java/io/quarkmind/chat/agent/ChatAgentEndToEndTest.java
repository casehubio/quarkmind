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
        var mapper   = new ObjectMapper();
        var llmQueue = stubLlmQueue();

        var llm = (ChatAgencyLoop.LlmInvoker) (sys, usr, id) ->
                                                      "{\"action\":\"SEND\",\"channel\":\"general\",\"text\":\"Hey there!\"}";

        var renderer         = new ChatObservationRenderer(10);
        var perceptionBridge = new DefaultChatPerceptionBridge(renderer);
        var loop = new ChatAgencyLoop(llm, llmQueue, mapper, perceptionBridge,
                                      null, null, null);

        var detector       = stubDetector();
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
        var character = new CharacterContext("quark", "server-1",
                                             "You are Quark, a friendly chat character.",
                                             null, detector);
        var context = new AgencyContext(new NeedState());
        context.put("perception", perception);
        context.put("character", character);
        loop.tick(context);

        @SuppressWarnings("unchecked")
        var intents = (List<ChatIntent>) context.get("intents");
        var queue = new IntentQueue<ChatIntent>();
        for (ChatIntent intent : intents) {
            queue.enqueue(intent);
        }
        bridge.dispatch(queue);

        assertEquals("Hey there!", sent.get());
    }

    @Test
    void heartbeatWithNoMessagesDoesNotInvokeLlm() {
        var mapper   = new ObjectMapper();
        var llmQueue = stubLlmQueue();
        var invoked  = new java.util.concurrent.atomic.AtomicBoolean(false);

        var llm = (ChatAgencyLoop.LlmInvoker) (sys, usr, id) -> {
            invoked.set(true);
            return "{\"action\":\"WAIT\"}";
        };

        var loop = new ChatAgencyLoop(llm, llmQueue, mapper,
                                      new DefaultChatPerceptionBridge(new ChatObservationRenderer(10)),
                                      null, null, null);

        var perception = new ChatPerception(Map.of(), Map.of(), WakeReason.HEARTBEAT);
        var context    = new AgencyContext(new NeedState());
        context.put("perception", perception);
        context.put("character", new CharacterContext("bot", "default", "",
                                                      null, stubDetector()));
        loop.tick(context);

        assertFalse(invoked.get());
    }

    @Test
    void memoryIntegrationEndToEnd() {
        var mapper          = new ObjectMapper();
        var store           = new ChatMemoryFacadeTest.RecordingMemoryStore();
        var facade          = new ChatMemoryFacade(store, store, false);
        var scoringRequests = new ArrayList<LlmRequest>();
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

        var loop = new ChatAgencyLoop(llm, queue, mapper,
                                      new DefaultChatPerceptionBridge(new ChatObservationRenderer(10)),
                                      facade, null, null);

        var character = new CharacterContext("agent-1", "default",
                                             "You are a friendly bot.", null, stubDetector());

        var perception = new ChatPerception(
                Map.of("ch-1", List.of(new ReceivedMessage("discord", new ChatChannelRef("ch-1"),
                                                           new ChatMessageRef(new ChatChannelRef("ch-1"), "m1"), null,
                                                           new MemberRef("bob"), new ChatContent("tell me about ML"), Instant.now()))),
                Map.of(), WakeReason.MESSAGE);
        var context = new AgencyContext(new NeedState());
        context.put("perception", perception);
        context.put("character", character);
        loop.tick(context);

        assertEquals(1, store.stored.size());
        assertTrue(store.stored.get(0).text().contains("Bob asked about ML"));
        assertNull(store.stored.get(0).importance());

        assertTrue(scoringRequests.stream()
                                  .anyMatch(r -> r.priority() == LlmPriority.LOW && r.responseHandler() != null));

        var scoringReq = scoringRequests.stream()
                                        .filter(r -> r.responseHandler() != null).findFirst().orElseThrow();
        scoringReq.responseHandler().accept("0.7");
        assertEquals("mem-1", store.lastUpdatedMemoryId);
        assertEquals(0.7, store.lastUpdatedImportance, 0.001);

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
        context2.put("character", character);
        loop.tick(context2);

        assertNotNull(store.lastQuery);
    }

    private BotIdentityDetector stubDetector() {
        return new BotIdentityDetector() {
            @Override
            public boolean isMention(ReceivedMessage msg)    {return false;}

            @Override
            public boolean isReplyToBot(ReceivedMessage msg) {return false;}

            @Override
            public String botUserId()                        {return "bot-id";}
        };
    }

    private LlmRequestQueue stubLlmQueue() {
        return new LlmRequestQueue() {
            @Override
            public void submit(LlmRequest request) {}

            @Override
            public int pendingCount()              {return 0;}

            @Override
            public boolean hasCapacity()           {return true;}
        };
    }
}

package io.quarkmind.chat.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.connectors.chat.model.ChatChannelRef;
import io.casehub.connectors.chat.model.ChatContent;
import io.casehub.connectors.chat.model.ChatMessageRef;
import io.casehub.connectors.chat.model.MemberRef;
import io.casehub.connectors.chat.model.ReceivedMessage;
import io.quarkmind.agency.chat.BotIdentityDetector;
import io.quarkmind.agency.chat.ChatObservationRenderer;
import io.quarkmind.agency.llm.LlmRequest;
import io.quarkmind.agency.llm.LlmRequestQueue;
import io.quarkmind.chat.agent.discord.DiscordIdentityDetector;
import io.quarkmind.chat.protocol.ChatPerception;
import io.quarkmind.chat.protocol.WakeReason;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ChatCharacterManagerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final LlmRequestQueue llmQueue = new LlmRequestQueue() {
        @Override public void submit(LlmRequest r) {}
        @Override public int pendingCount() { return 0; }
        @Override public boolean hasCapacity() { return true; }
    };

    @Test
    void createsAndRetrievesCharacters() {
        var manager = createManager((s, u, id) -> "{\"action\":\"WAIT\",\"observation\":\"idle\"}");
        var luna = stubCharacter("luna", "bot-luna");
        var rex = stubCharacter("rex", "bot-rex");

        manager.addCharacter(luna);
        manager.addCharacter(rex);

        assertEquals(2, manager.characterCount());
        assertSame(luna, manager.character("luna"));
        assertSame(rex, manager.character("rex"));
    }

    @Test
    void tickRoutesToCorrectCharacterContext() {
        var capturedAgentId = new AtomicReference<String>();
        ChatAgencyLoop.LlmInvoker llm = (system, user, id) -> {
            capturedAgentId.set(id);
            return "{\"action\":\"WAIT\",\"observation\":\"idle\"}";
        };

        var manager = createManager(llm);
        manager.addCharacter(stubCharacter("luna", "bot-luna"));

        var perception = perceptionWithMessage("hi", "ch");
        manager.tickCharacter("luna", perception);

        assertEquals("luna", capturedAgentId.get());
    }

    @Test
    void tickForUnknownCharacterIsNoOp() {
        var manager = createManager((s, u, id) -> "{\"action\":\"WAIT\",\"observation\":\"idle\"}");
        var perception = new ChatPerception(Map.of(), Map.of(), WakeReason.MESSAGE);
        assertDoesNotThrow(() -> manager.tickCharacter("nonexistent", perception));
    }

    @Test
    void multipleCharactersTickIndependently() {
        var capturedIds = new java.util.ArrayList<String>();
        ChatAgencyLoop.LlmInvoker llm = (system, user, id) -> {
            capturedIds.add(id);
            return "{\"action\":\"WAIT\",\"observation\":\"idle\"}";
        };

        var manager = createManager(llm);
        manager.addCharacter(stubCharacter("luna", "bot-luna"));
        manager.addCharacter(stubCharacter("rex", "bot-rex"));

        var perception = perceptionWithMessage("hello all", "ch");
        manager.tickCharacter("luna", perception);
        manager.tickCharacter("rex", perception);

        assertEquals(2, capturedIds.size());
        assertTrue(capturedIds.contains("luna"));
        assertTrue(capturedIds.contains("rex"));
    }

    private ChatCharacterManager createManager(ChatAgencyLoop.LlmInvoker llm) {
        var loop = new ChatAgencyLoop(llm, llmQueue, mapper,
                new DefaultChatPerceptionBridge(new ChatObservationRenderer(10)),
                null, null, null);
        return new ChatCharacterManager(loop);
    }

    private CharacterContext stubCharacter(String agentId, String botUserId) {
        return new CharacterContext(agentId, "default", "You are " + agentId + ".",
                null, new DiscordIdentityDetector(botUserId));
    }

    private ChatPerception perceptionWithMessage(String text, String channelId) {
        var msg = new ReceivedMessage("discord", new ChatChannelRef(channelId),
                new ChatMessageRef(new ChatChannelRef(channelId), "m1"), null,
                new MemberRef("user-1"), new ChatContent(text), Instant.now());
        return new ChatPerception(Map.of(channelId, List.of(msg)), Map.of(), WakeReason.MESSAGE);
    }
}

package io.quarkmind.chat.agent;

import io.casehub.neocortex.memory.Memory;
import io.casehub.neocortex.memory.experience.ExperienceEvents;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LlmReflectionSynthesizerTest {

    @Test
    void synthesizesReflectionFromSourceMemories() {
        var llm = (ChatAgencyLoop.LlmInvoker) (system, user, id) ->
                "[{\"insight\":\"Bob is deeply interested in NLP and transformer architectures\"}]";

        var synthesizer = new LlmReflectionSynthesizer(llm);
        var sources = List.of(
                new Memory("m1", "agent-1", ExperienceEvents.DOMAIN, "t1", null,
                        "Talked to Bob about transformers", Map.of(),
                        Instant.now().minusSeconds(3600), 0.8),
                new Memory("m2", "agent-1", ExperienceEvents.DOMAIN, "t1", null,
                        "Bob asked about attention mechanisms", Map.of(),
                        Instant.now().minusSeconds(1800), 0.7));

        var events = synthesizer.synthesize("agent-1", "t1", sources, 1);
        assertFalse(events.isEmpty());
        assertEquals("agent-1", events.get(0).agentId());
        assertEquals("t1", events.get(0).tenantId());
        assertFalse(events.get(0).insight().isBlank());
        assertEquals(1, events.get(0).level());
        assertEquals(List.of("m1", "m2"), events.get(0).sourceMemoryIds());
    }

    @Test
    void synthesizesMultipleReflections() {
        var llm = (ChatAgencyLoop.LlmInvoker) (system, user, id) ->
                "[{\"insight\":\"Bob likes NLP\"},{\"insight\":\"Alice prefers formal language\"}]";

        var synthesizer = new LlmReflectionSynthesizer(llm);
        var sources = List.of(
                new Memory("m1", "agent-1", ExperienceEvents.DOMAIN, "t1", null,
                        "Talked to Bob", Map.of(), Instant.now(), 0.5));

        var events = synthesizer.synthesize("agent-1", "t1", sources, 1);
        assertEquals(2, events.size());
    }

    @Test
    void returnsEmptyOnLlmFailure() {
        var llm = (ChatAgencyLoop.LlmInvoker) (system, user, id) -> {
            throw new RuntimeException("LLM unavailable");
        };

        var synthesizer = new LlmReflectionSynthesizer(llm);
        var sources = List.of(
                new Memory("m1", "agent-1", ExperienceEvents.DOMAIN, "t1", null,
                        "Something happened", Map.of(), Instant.now(), 0.5));

        var events = synthesizer.synthesize("agent-1", "t1", sources, 1);
        assertTrue(events.isEmpty());
    }

    @Test
    void skipsBlankInsights() {
        var llm = (ChatAgencyLoop.LlmInvoker) (system, user, id) ->
                "[{\"insight\":\"Real insight\"},{\"insight\":\"  \"},{\"insight\":\"\"}]";

        var synthesizer = new LlmReflectionSynthesizer(llm);
        var sources = List.of(
                new Memory("m1", "agent-1", ExperienceEvents.DOMAIN, "t1", null,
                        "Memory text", Map.of(), Instant.now(), 0.5));

        var events = synthesizer.synthesize("agent-1", "t1", sources, 1);
        assertEquals(1, events.size());
        assertEquals("Real insight", events.get(0).insight());
    }

    @Test
    void returnsEmptyOnMalformedJson() {
        var llm = (ChatAgencyLoop.LlmInvoker) (system, user, id) -> "not json at all";

        var synthesizer = new LlmReflectionSynthesizer(llm);
        var sources = List.of(
                new Memory("m1", "agent-1", ExperienceEvents.DOMAIN, "t1", null,
                        "Memory text", Map.of(), Instant.now(), 0.5));

        var events = synthesizer.synthesize("agent-1", "t1", sources, 1);
        assertTrue(events.isEmpty());
    }
}

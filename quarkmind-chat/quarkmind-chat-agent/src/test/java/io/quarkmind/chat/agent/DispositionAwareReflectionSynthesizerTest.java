package io.quarkmind.chat.agent;

import io.casehub.neocortex.memory.Memory;
import io.casehub.neocortex.memory.experience.ExperienceEvents;
import io.casehub.neocortex.memory.reflection.ReflectionEvent;
import io.casehub.neocortex.memory.reflection.ReflectionSynthesizer;
import io.quarkmind.agency.personality.ReflectionDispositionActivator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DispositionAwareReflectionSynthesizerTest {

    @Test
    void delegatesToUnderlyingSynthesizer() {
        var delegateCalled = new java.util.concurrent.atomic.AtomicBoolean(false);
        ReflectionSynthesizer delegate = (agentId, tenantId, sources, level) -> {
            delegateCalled.set(true);
            return List.of(new ReflectionEvent(agentId, tenantId, null,
                    "An insight", 1, List.of("m1"), 0.7, Map.of()));
        };
        var activations = new ArrayList<String>();
        ReflectionDispositionActivator activator = (aid, tid, insight) ->
                activations.add(insight);

        var synthesizer = new DispositionAwareReflectionSynthesizer(delegate, activator);
        var sources = List.of(testMemory("m1"));
        var events = synthesizer.synthesize("agent-1", "t1", sources, 1);

        assertTrue(delegateCalled.get());
        assertEquals(1, events.size());
        assertEquals("An insight", events.get(0).insight());
    }

    @Test
    void feedsEachInsightToActivator() {
        ReflectionSynthesizer delegate = (agentId, tenantId, sources, level) -> List.of(
                new ReflectionEvent(agentId, tenantId, null, "Insight A", 1, List.of("m1"), 0.6, Map.of()),
                new ReflectionEvent(agentId, tenantId, null, "Insight B", 1, List.of("m1"), 0.7, Map.of()));
        var activations = new ArrayList<String>();
        ReflectionDispositionActivator activator = (aid, tid, insight) ->
                activations.add(insight);

        var synthesizer = new DispositionAwareReflectionSynthesizer(delegate, activator);
        synthesizer.synthesize("agent-1", "t1", List.of(testMemory("m1")), 1);

        assertEquals(2, activations.size());
        assertEquals("Insight A", activations.get(0));
        assertEquals("Insight B", activations.get(1));
    }

    @Test
    void passesCorrectAgentAndTenantToActivator() {
        ReflectionSynthesizer delegate = (agentId, tenantId, sources, level) -> List.of(
                new ReflectionEvent(agentId, tenantId, null, "Insight", 1, List.of("m1"), 0.5, Map.of()));
        var agentIds = new ArrayList<String>();
        var tenantIds = new ArrayList<String>();
        ReflectionDispositionActivator activator = (aid, tid, insight) -> {
            agentIds.add(aid);
            tenantIds.add(tid);
        };

        var synthesizer = new DispositionAwareReflectionSynthesizer(delegate, activator);
        synthesizer.synthesize("bot-42", "server-7", List.of(testMemory("m1")), 1);

        assertEquals("bot-42", agentIds.get(0));
        assertEquals("server-7", tenantIds.get(0));
    }

    @Test
    void returnsEmptyWhenDelegateReturnsEmpty() {
        ReflectionSynthesizer delegate = (agentId, tenantId, sources, level) -> List.of();
        var activations = new ArrayList<String>();
        ReflectionDispositionActivator activator = (aid, tid, insight) ->
                activations.add(insight);

        var synthesizer = new DispositionAwareReflectionSynthesizer(delegate, activator);
        var events = synthesizer.synthesize("agent-1", "t1", List.of(testMemory("m1")), 1);

        assertTrue(events.isEmpty());
        assertTrue(activations.isEmpty());
    }

    @Test
    void activatorFailureDoesNotBreakSynthesis() {
        ReflectionSynthesizer delegate = (agentId, tenantId, sources, level) -> List.of(
                new ReflectionEvent(agentId, tenantId, null, "Insight", 1, List.of("m1"), 0.5, Map.of()));
        ReflectionDispositionActivator activator = (aid, tid, insight) -> {
            throw new RuntimeException("activator failed");
        };

        var synthesizer = new DispositionAwareReflectionSynthesizer(delegate, activator);
        var events = synthesizer.synthesize("agent-1", "t1", List.of(testMemory("m1")), 1);

        assertEquals(1, events.size());
    }

    private Memory testMemory(String id) {
        return new Memory(id, "agent-1", ExperienceEvents.DOMAIN, "t1", null,
                "Some experience", Map.of(), Instant.now(), 0.5);
    }
}

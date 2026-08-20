package io.quarkmind.chat.agent;

import io.casehub.eidos.api.DispositionSignalStore;
import io.casehub.eidos.api.DispositionValue;
import io.quarkmind.agency.llm.LlmPriority;
import io.quarkmind.agency.llm.LlmRequest;
import io.quarkmind.agency.llm.LlmRequestQueue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LlmReflectionDispositionActivatorTest {

    @Test
    void submitsLowPriorityClassificationRequest() {
        var submitted = new ArrayList<LlmRequest>();
        var queue = stubQueue(submitted);
        var store = new RecordingSignalStore();
        var profile = List.of(
                new DispositionValue("empathetic", 0.4),
                new DispositionValue("analytical", 0.3),
                new DispositionValue("playful", 0.3));

        var activator = new LlmReflectionDispositionActivator(queue, store, profile);
        activator.onReflection("agent-1", "t1", "Users tend to be emotionally expressive");

        assertEquals(1, submitted.size());
        assertEquals(LlmPriority.LOW, submitted.get(0).priority());
        assertTrue(submitted.get(0).prompt().contains("empathetic"));
        assertTrue(submitted.get(0).prompt().contains("analytical"));
        assertTrue(submitted.get(0).prompt().contains("playful"));
    }

    @Test
    void recordsActivationWhenLlmReturnsValidTerm() {
        var submitted = new ArrayList<LlmRequest>();
        var queue = stubQueue(submitted);
        var store = new RecordingSignalStore();
        var profile = List.of(
                new DispositionValue("empathetic", 0.4),
                new DispositionValue("analytical", 0.3));

        var activator = new LlmReflectionDispositionActivator(queue, store, profile);
        activator.onReflection("agent-1", "t1", "People open up emotionally");

        submitted.get(0).responseHandler().accept("empathetic");

        assertEquals(1, store.activations.size());
        assertEquals("empathetic", store.activations.get(0).term);
        assertEquals("agent-1", store.activations.get(0).agentId);
        assertEquals("t1", store.activations.get(0).tenantId);
    }

    @Test
    void ignoresInvalidTermFromLlm() {
        var submitted = new ArrayList<LlmRequest>();
        var queue = stubQueue(submitted);
        var store = new RecordingSignalStore();
        var profile = List.of(new DispositionValue("empathetic", 0.5));

        var activator = new LlmReflectionDispositionActivator(queue, store, profile);
        activator.onReflection("agent-1", "t1", "Some reflection");

        submitted.get(0).responseHandler().accept("nonexistent-term");

        assertTrue(store.activations.isEmpty());
    }

    @Test
    void handlesNoneResponse() {
        var submitted = new ArrayList<LlmRequest>();
        var queue = stubQueue(submitted);
        var store = new RecordingSignalStore();
        var profile = List.of(new DispositionValue("empathetic", 0.5));

        var activator = new LlmReflectionDispositionActivator(queue, store, profile);
        activator.onReflection("agent-1", "t1", "Mundane observation");

        submitted.get(0).responseHandler().accept("none");

        assertTrue(store.activations.isEmpty());
    }

    @Test
    void skipsWhenProfileIsEmpty() {
        var submitted = new ArrayList<LlmRequest>();
        var queue = stubQueue(submitted);
        var store = new RecordingSignalStore();

        var activator = new LlmReflectionDispositionActivator(queue, store, List.of());
        activator.onReflection("agent-1", "t1", "Something happened");

        assertTrue(submitted.isEmpty());
    }

    @Test
    void matchesTermCaseInsensitively() {
        var submitted = new ArrayList<LlmRequest>();
        var queue = stubQueue(submitted);
        var store = new RecordingSignalStore();
        var profile = List.of(new DispositionValue("empathetic", 0.5));

        var activator = new LlmReflectionDispositionActivator(queue, store, profile);
        activator.onReflection("agent-1", "t1", "Reflection");

        submitted.get(0).responseHandler().accept("  Empathetic  ");

        assertEquals(1, store.activations.size());
        assertEquals("empathetic", store.activations.get(0).term);
    }

    @Test
    void updateProfileChangesTermsForSubsequentCalls() {
        var submitted = new ArrayList<LlmRequest>();
        var queue = stubQueue(submitted);
        var store = new RecordingSignalStore();
        var profile = List.of(new DispositionValue("empathetic", 0.5));

        var activator = new LlmReflectionDispositionActivator(queue, store, profile);
        activator.updateProfile(List.of(new DispositionValue("curious", 0.5)));
        activator.onReflection("agent-1", "t1", "Something");

        assertTrue(submitted.get(0).prompt().contains("curious"));
        assertFalse(submitted.get(0).prompt().contains("empathetic"));
    }

    private LlmRequestQueue stubQueue(List<LlmRequest> sink) {
        return new LlmRequestQueue() {
            @Override public void submit(LlmRequest r) { sink.add(r); }
            @Override public int pendingCount() { return 0; }
            @Override public boolean hasCapacity() { return true; }
        };
    }

    record Activation(String agentId, String tenantId, String term) {}

    static class RecordingSignalStore implements DispositionSignalStore {
        final List<Activation> activations = new ArrayList<>();
        @Override public void recordActivation(String agentId, String tenancyId, String functionTerm) {
            activations.add(new Activation(agentId, tenancyId, functionTerm));
        }
        @Override public Map<String, Integer> activationCounts(String agentId, String tenancyId) { return Map.of(); }
        @Override public void decay(String agentId, String tenancyId, double decayFactor) {}
        @Override public void clear(String agentId, String tenancyId) {}
    }
}

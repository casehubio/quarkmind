package io.quarkmind.agency.personality;

import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentDisposition;
import io.casehub.eidos.api.CapabilityHealth.ProbeContext;
import io.casehub.eidos.api.DispositionEvolution;
import io.casehub.eidos.api.DispositionEvolution.EvolutionResult;
import io.casehub.eidos.api.DispositionHealth;
import io.casehub.eidos.api.DispositionHealth.DispositionStatus;
import io.casehub.eidos.api.DispositionSignalStore;
import io.casehub.eidos.api.DispositionValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PersonalityEvolutionPipelineTest {

    @Test
    void returnsEmptyWhenAligned() {
        var store = new StubSignalStore();
        var pipeline = new PersonalityEvolutionPipeline(
                (desc, ctx) -> new DispositionStatus.Aligned(Map.of()),
                (desc, pending) -> { throw new AssertionError("should not be called"); },
                store);
        var result = pipeline.checkEvolution(testDescriptor());
        assertTrue(result.isEmpty());
    }

    @Test
    void returnsEmptyWhenDrifted() {
        var store = new StubSignalStore();
        var pipeline = new PersonalityEvolutionPipeline(
                (desc, ctx) -> new DispositionStatus.Drifted(Map.of(), "empathetic", 0.1),
                (desc, pending) -> { throw new AssertionError("should not be called"); },
                store);
        var result = pipeline.checkEvolution(testDescriptor());
        assertTrue(result.isEmpty());
    }

    @Test
    void returnsEvolvedAndDoesNotDecay() {
        var store = new StubSignalStore();
        var newProfile = List.of(
                new DispositionValue("empathetic", 0.35),
                new DispositionValue("analytical", 0.20),
                new DispositionValue("playful", 0.45));
        var pipeline = new PersonalityEvolutionPipeline(
                (desc, ctx) -> new DispositionStatus.EvolutionPending(
                        () -> "DOMINANT_AUXILIARY_SWAP", "empathetic", Map.of()),
                (desc, pending) -> new EvolutionResult.Evolved(newProfile, "ANALYTICAL-EMPATHETIC", "EMPATHETIC-PLAYFUL"),
                store);
        var result = pipeline.checkEvolution(testDescriptor());
        assertTrue(result.isPresent());
        assertInstanceOf(EvolutionResult.Evolved.class, result.get());
        assertFalse(store.decayCalled);
    }

    @Test
    void returnsDampenedAndDecays() {
        var store = new StubSignalStore();
        var pipeline = new PersonalityEvolutionPipeline(
                (desc, ctx) -> new DispositionStatus.EvolutionPending(
                        () -> "STRUCTURAL_REORGANIZATION", "curious", Map.of()),
                (desc, pending) -> new EvolutionResult.Dampened(0.2),
                store);
        var result = pipeline.checkEvolution(testDescriptor());
        assertTrue(result.isPresent());
        assertInstanceOf(EvolutionResult.Dampened.class, result.get());
        assertTrue(store.decayCalled);
        assertEquals(0.2, store.lastDecayFactor, 0.001);
        assertEquals("agent-1", store.lastDecayAgentId);
    }

    private AgentDescriptor testDescriptor() {
        return AgentDescriptor.builder()
                .agentId("agent-1").name("Test").slot("chat").tenancyId("t1")
                .disposition(AgentDisposition.builder()
                        .dispositionProfile(
                                new DispositionValue("analytical", 0.35),
                                new DispositionValue("empathetic", 0.20),
                                new DispositionValue("playful", 0.45))
                        .build())
                .build();
    }

    public static class StubSignalStore implements DispositionSignalStore {
        boolean decayCalled = false;
        double lastDecayFactor;
        String lastDecayAgentId;

        @Override public void recordActivation(String agentId, String tenancyId, String functionTerm) {}
        @Override public Map<String, Integer> activationCounts(String agentId, String tenancyId) { return Map.of(); }
        @Override public void decay(String agentId, String tenancyId, double decayFactor) {
            decayCalled = true;
            lastDecayFactor = decayFactor;
            lastDecayAgentId = agentId;
        }
        @Override public void clear(String agentId, String tenancyId) {}
    }
}

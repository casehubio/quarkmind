package io.quarkmind.agency.personality;

import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.CapabilityHealth.ProbeContext;
import io.casehub.eidos.api.DispositionEvolution;
import io.casehub.eidos.api.DispositionEvolution.EvolutionResult;
import io.casehub.eidos.api.DispositionHealth;
import io.casehub.eidos.api.DispositionHealth.DispositionStatus;
import io.casehub.eidos.api.DispositionSignalStore;

import java.util.Optional;

public class PersonalityEvolutionPipeline {

    private final DispositionHealth health;
    private final DispositionEvolution evolution;
    private final DispositionSignalStore signalStore;

    public PersonalityEvolutionPipeline(DispositionHealth health,
                                        DispositionEvolution evolution,
                                        DispositionSignalStore signalStore) {
        this.health = health;
        this.evolution = evolution;
        this.signalStore = signalStore;
    }

    public Optional<EvolutionResult> checkEvolution(AgentDescriptor descriptor) {
        var status = health.probe(descriptor, ProbeContext.of("chat"));
        if (status instanceof DispositionStatus.EvolutionPending pending) {
            var result = evolution.evaluate(descriptor, pending);
            if (result instanceof EvolutionResult.Dampened dampened) {
                signalStore.decay(descriptor.agentId(), descriptor.tenancyId(),
                        dampened.decayFactor());
            }
            return Optional.of(result);
        }
        return Optional.empty();
    }
}

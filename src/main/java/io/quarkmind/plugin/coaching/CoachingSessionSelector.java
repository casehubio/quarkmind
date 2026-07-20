package io.quarkmind.plugin.coaching;

import io.casehub.eidos.api.AgentDescriptor;
import io.quarkmind.plugin.advisory.QuarkMindAgentRegistrar;
import io.quarkmind.sc2.GameStarted;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@ApplicationScoped
public class CoachingSessionSelector {

    private final List<AgentDescriptor>            coachAgents;
    private final String                           defaultPersonality;
    private final AtomicReference<AgentDescriptor> cached = new AtomicReference<>();

    @Inject
    CoachingSessionSelector(QuarkMindAgentRegistrar registrar,
                            @ConfigProperty(name = "quarkmind.coaching.default-personality",
                                            defaultValue = "directive")
                            String defaultPersonality) {
        this.coachAgents        = registrar.descriptors().stream()
                                           .filter(d -> d.capabilities().stream().anyMatch(c -> c.name().equals("coaching")))
                                           .toList();
        this.defaultPersonality = defaultPersonality;
    }

    CoachingSessionSelector(List<AgentDescriptor> coachAgents, String defaultPersonality) {
        this.coachAgents        = coachAgents;
        this.defaultPersonality = defaultPersonality;
    }

    public AgentDescriptor select(CoachingUrgencyTier tier) {
        AgentDescriptor selected = cached.get();
        if (selected != null) {return selected;}

        selected = coachAgents.stream()
                              .filter(a -> a.agentId().contains(defaultPersonality))
                              .findFirst()
                              .orElse(coachAgents.getFirst());

        cached.compareAndSet(null, selected);
        return cached.get();
    }

    void onGameStarted(@Observes GameStarted event) {
        cached.set(null);
    }
}

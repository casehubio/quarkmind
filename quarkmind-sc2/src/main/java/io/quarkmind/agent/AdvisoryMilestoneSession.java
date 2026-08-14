package io.quarkmind.agent;

import io.quarkmind.agency.milestone.MilestoneTracker;
import io.quarkmind.sc2.GameStarted;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class AdvisoryMilestoneSession implements MilestoneTracker {

    private final Map<String, UUID> entryIds = new ConcurrentHashMap<>();
    private final Set<String> firedMilestones = ConcurrentHashMap.newKeySet();

    public Optional<UUID> entryId(String advisorId) {
        return Optional.ofNullable(entryIds.get(advisorId));
    }

    public void setEntryId(String advisorId, UUID id) {
        entryIds.put(advisorId, id);
    }

    @Override
    public boolean hasFired(String milestoneId) {
        return firedMilestones.contains(milestoneId);
    }

    @Override
    public void markFired(String milestoneId) {
        firedMilestones.add(milestoneId);
    }

    public void reset() {
        entryIds.clear();
        firedMilestones.clear();
    }

    void onGameStarted(@Observes GameStarted event) {
        reset();
    }
}

package io.quarkmind.agency.milestone;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class MilestoneSession implements MilestoneTracker {

    private final Map<String, UUID> entryIds = new ConcurrentHashMap<>();
    private final Set<String> firedMilestones = ConcurrentHashMap.newKeySet();

    public Optional<UUID> entryId(String strategyId) {
        return Optional.ofNullable(entryIds.get(strategyId));
    }

    public void setEntryId(String strategyId, UUID id) {
        entryIds.put(strategyId, id);
    }

    public boolean hasFired(String milestoneId) {
        return firedMilestones.contains(milestoneId);
    }

    public void markFired(String milestoneId) {
        firedMilestones.add(milestoneId);
    }

    public void reset() {
        entryIds.clear();
        firedMilestones.clear();
    }
}

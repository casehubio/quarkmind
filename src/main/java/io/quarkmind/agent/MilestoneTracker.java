package io.quarkmind.agent;

public interface MilestoneTracker {
    boolean hasFired(String milestoneId);
    void markFired(String milestoneId);
}

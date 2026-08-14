package io.quarkmind.agency.milestone;

public interface MilestoneTracker {
    boolean hasFired(String milestoneId);
    void markFired(String milestoneId);
}

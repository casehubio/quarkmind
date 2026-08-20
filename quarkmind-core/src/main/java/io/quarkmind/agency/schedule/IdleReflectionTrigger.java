package io.quarkmind.agency.schedule;

public class IdleReflectionTrigger {

    private double accumulatedImportance = 0.0;
    private final double threshold;
    private final int idleHeartbeats;

    public IdleReflectionTrigger(double threshold, int idleHeartbeats) {
        this.threshold = threshold;
        this.idleHeartbeats = idleHeartbeats;
    }

    public void accumulate(double importance) {
        accumulatedImportance += importance;
    }

    public boolean shouldReflect(int consecutiveIdleTicks) {
        return accumulatedImportance >= threshold && consecutiveIdleTicks >= idleHeartbeats;
    }

    public void reset() {
        accumulatedImportance = 0.0;
    }
}

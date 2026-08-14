package io.quarkmind.plugin.coaching;

public enum CoachingUrgencyTier {
    CRISIS(2000, 150),
    STRATEGIC(5000, 110),
    ECONOMIC(5000, 110);

    private final int latencyCapMs;
    private final int cooldownFrames;

    CoachingUrgencyTier(int latencyCapMs, int cooldownFrames) {
        this.latencyCapMs = latencyCapMs;
        this.cooldownFrames = cooldownFrames;
    }

    public int latencyCapMs() { return latencyCapMs; }
    public int cooldownFrames() { return cooldownFrames; }
}

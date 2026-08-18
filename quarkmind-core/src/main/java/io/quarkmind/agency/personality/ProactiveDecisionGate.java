package io.quarkmind.agency.personality;

public class ProactiveDecisionGate {

    private final long minTimeSinceLastPostMs;
    private final int maxChannelActivity;

    public ProactiveDecisionGate(long minTimeSinceLastPostMs, int maxChannelActivity) {
        this.minTimeSinceLastPostMs = minTimeSinceLastPostMs;
        this.maxChannelActivity = maxChannelActivity;
    }

    public boolean shouldAct(long timeSinceLastPostMs, int recentChannelMessages, boolean othersTyping) {
        if (timeSinceLastPostMs < minTimeSinceLastPostMs) return false;
        if (othersTyping) return false;
        return recentChannelMessages <= maxChannelActivity;
    }
}

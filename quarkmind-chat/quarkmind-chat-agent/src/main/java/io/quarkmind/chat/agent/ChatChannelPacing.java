package io.quarkmind.chat.agent;

public class ChatChannelPacing {

    private final int maxChannelActivityForUnprompted;
    private final long minTimeSinceLastPostMs;

    public ChatChannelPacing(int maxChannelActivityForUnprompted, long minTimeSinceLastPostMs) {
        this.maxChannelActivityForUnprompted = maxChannelActivityForUnprompted;
        this.minTimeSinceLastPostMs = minTimeSinceLastPostMs;
    }

    public boolean allowUnprompted(int recentChannelMessages, long timeSinceLastPostMs) {
        if (timeSinceLastPostMs < minTimeSinceLastPostMs) return false;
        return recentChannelMessages <= maxChannelActivityForUnprompted;
    }
}

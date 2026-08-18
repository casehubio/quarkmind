package io.quarkmind.agency.schedule;

import java.util.ArrayDeque;
import java.util.Deque;

public class OutputGovernor {

    private final long windowMs;
    private final long minIntervalMs;
    private final int maxPerWindow;
    private final Deque<Long> actionTimestamps = new ArrayDeque<>();

    public OutputGovernor(long windowMs, long minIntervalMs, int maxPerWindow) {
        this.windowMs = windowMs;
        this.minIntervalMs = minIntervalMs;
        this.maxPerWindow = maxPerWindow;
    }

    public boolean allow() {
        long now = System.currentTimeMillis();
        pruneOld(now);
        if (!actionTimestamps.isEmpty() && now - actionTimestamps.peekLast() < minIntervalMs) {
            return false;
        }
        return actionTimestamps.size() < maxPerWindow;
    }

    public void recordAction() {
        actionTimestamps.addLast(System.currentTimeMillis());
    }

    private void pruneOld(long now) {
        while (!actionTimestamps.isEmpty() && now - actionTimestamps.peekFirst() > windowMs) {
            actionTimestamps.pollFirst();
        }
    }
}

package io.quarkmind.agent;

import java.util.List;

public interface MilestoneTrigger {
    List<MilestoneEvent> check(long gameFrame, MilestoneTracker tracker);
}

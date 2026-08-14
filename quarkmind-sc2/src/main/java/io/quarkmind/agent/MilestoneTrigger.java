package io.quarkmind.agent;

import io.quarkmind.agency.milestone.MilestoneTracker;

import java.util.List;

public interface MilestoneTrigger {
    List<MilestoneEvent> check(long gameFrame, MilestoneTracker tracker);
}

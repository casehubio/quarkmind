package io.quarkmind.agent;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class FrameThresholdTrigger implements MilestoneTrigger {

    public record Threshold(long frame, double weight) {}

    private final List<Threshold> thresholds;

    @Inject
    FrameThresholdTrigger(MilestoneConfig config) {
        List<Threshold> parsed = new ArrayList<>();
        for (var entry : config.frameThresholds()) {
            parsed.add(new Threshold(entry.frame(), entry.weight()));
        }
        this.thresholds = List.copyOf(parsed);
    }

    /** Test constructor — no CDI. */
    FrameThresholdTrigger(List<Threshold> thresholds) {
        this.thresholds = List.copyOf(thresholds);
    }

    @Override
    public List<MilestoneEvent> check(long gameFrame, MilestoneTracker tracker) {
        List<MilestoneEvent> events = new ArrayList<>();
        for (Threshold t : thresholds) {
            String milestoneId = "frame:" + t.frame();
            if (gameFrame >= t.frame() && !tracker.hasFired(milestoneId)) {
                tracker.markFired(milestoneId);
                events.add(new MilestoneEvent(milestoneId, t.weight()));
            }
        }
        return events;
    }
}

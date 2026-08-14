package io.quarkmind.agent;

import io.casehub.blocks.summarisation.EventStreamBus;
import io.quarkmind.agency.milestone.MilestoneTracker;
import io.quarkmind.plugin.summarisation.TacticalPosture;
import io.quarkmind.plugin.summarisation.SummarisationLifecycle;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.List;

@ApplicationScoped
public class GamePhaseTrigger implements MilestoneTrigger {

    private final long expectedGameLength;
    private final double minWeight;
    private final double maxWeight;

    private volatile TacticalPosture lastSeenPhase;

    private final Instance<SummarisationLifecycle> lazyLifecycle;
    private volatile boolean subscribed = false;

    @Inject
    GamePhaseTrigger(Instance<SummarisationLifecycle> summarisationLifecycle, MilestoneConfig config) {
        this.expectedGameLength = config.phaseTriggers().expectedGameLength();
        this.minWeight = config.phaseTriggers().minWeight();
        this.maxWeight = config.phaseTriggers().maxWeight();
        this.lazyLifecycle = summarisationLifecycle;
    }

    /** Test constructor — direct phaseBus injection, no CDI. */
    GamePhaseTrigger(EventStreamBus<TacticalPosture> phaseBus, long expectedGameLength, double minWeight, double maxWeight) {
        this.expectedGameLength = expectedGameLength;
        this.minWeight = minWeight;
        this.maxWeight = maxWeight;
        this.lazyLifecycle = null;
        this.subscribed = true;
        phaseBus.subscribe(p -> true, e -> lastSeenPhase = e.payload());
    }

    private void ensureSubscribed() {
        if (!subscribed) {
            synchronized (this) {
                if (!subscribed) {
                    lazyLifecycle.get().phaseBus().subscribe(p -> true, e -> lastSeenPhase = e.payload());
                    subscribed = true;
                }
            }
        }
    }

    @Override
    public List<MilestoneEvent> check(long gameFrame, MilestoneTracker tracker) {
        ensureSubscribed();
        TacticalPosture phase = lastSeenPhase;
        if (phase == null) return List.of();

        String milestoneId = "phase:" + phase.posture();
        if (tracker.hasFired(milestoneId)) return List.of();

        tracker.markFired(milestoneId);
        double raw = (double) gameFrame / expectedGameLength;
        double weight = Math.max(minWeight, Math.min(maxWeight, raw));
        return List.of(new MilestoneEvent(milestoneId, weight));
    }
}

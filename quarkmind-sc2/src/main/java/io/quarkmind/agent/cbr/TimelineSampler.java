package io.quarkmind.agent.cbr;

import io.quarkmind.domain.GameState;
import io.quarkmind.domain.TimelineObservation;
import io.quarkmind.sc2.GameStarted;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@ApplicationScoped
public class TimelineSampler {

    static final long SAMPLE_INTERVAL = 672;

    private final List<TimelineObservation> timeline = new CopyOnWriteArrayList<>();
    private long lastSampleFrame = -SAMPLE_INTERVAL;

    public void tick(GameState gs) {
        if (gs.gameFrame() - lastSampleFrame >= SAMPLE_INTERVAL) {
            timeline.add(TimelineObservation.from(gs));
            lastSampleFrame = gs.gameFrame();
        }
    }

    public List<TimelineObservation> getTimeline() {
        return List.copyOf(timeline);
    }

    void onGameStarted(@Observes GameStarted event) {
        timeline.clear();
        lastSampleFrame = -SAMPLE_INTERVAL;
    }
}

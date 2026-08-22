package io.quarkmind.plugin.scouting;

import java.util.ArrayList;
import java.util.List;

public class TemporalWindowAccumulator {

    static final int MAX_WINDOWS = 10;
    static final int TICKS_PER_WINDOW = 60;
    static final int FEATURES_PER_PLAYER = 134;
    static final int FEATURES_PER_WINDOW = 2 * FEATURES_PER_PLAYER + 1;

    private final List<WindowSnapshot> tickSnapshots = new ArrayList<>();

    public void addSnapshot(WindowSnapshot snapshot) {
        tickSnapshots.add(snapshot);
    }

    public List<float[]> getWindowedFeatures() {
        List<float[]> result = new ArrayList<>(MAX_WINDOWS);
        for (int w = 0; w < MAX_WINDOWS; w++) {
            int startTick = w * TICKS_PER_WINDOW;
            int endTick = Math.min(startTick + TICKS_PER_WINDOW, tickSnapshots.size());
            if (startTick >= tickSnapshots.size()) {
                result.add(new float[FEATURES_PER_WINDOW]);
                continue;
            }
            float[] window = new float[FEATURES_PER_WINDOW];
            int count = endTick - startTick;
            boolean anyVision = false;
            for (int t = startTick; t < endTick; t++) {
                var snap = tickSnapshots.get(t);
                for (int f = 0; f < FEATURES_PER_PLAYER; f++) {
                    window[f] += snap.playerFeatures()[f];
                    window[FEATURES_PER_PLAYER + f] +=
                        snap.opponentFeatures()[f] * snap.scoutingVisibility();
                }
                if (snap.scoutingVisibility() > 0) anyVision = true;
            }
            for (int f = 0; f < 2 * FEATURES_PER_PLAYER; f++) {
                window[f] /= count;
            }
            window[FEATURES_PER_WINDOW - 1] = anyVision ? 1.0f : 0.0f;
            result.add(window);
        }
        return result;
    }

    public void reset() {
        tickSnapshots.clear();
    }
}

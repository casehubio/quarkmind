package io.quarkmind.agency.schedule;

import io.quarkmind.agency.needs.NeedState;

import java.util.Map;

public class NeedThresholdWake {

    private final Map<String, Double> thresholds;

    public NeedThresholdWake(Map<String, Double> thresholds) {
        this.thresholds = Map.copyOf(thresholds);
    }

    public boolean anyNeedCrossed(NeedState needs) {
        for (var entry : thresholds.entrySet()) {
            if (needs.get(entry.getKey()) < entry.getValue()) {
                return true;
            }
        }
        return false;
    }

    public String mostUrgentNeed(NeedState needs) {
        String most = null;
        double lowestRatio = Double.MAX_VALUE;
        for (var entry : thresholds.entrySet()) {
            double ratio = needs.get(entry.getKey()) / entry.getValue();
            if (ratio < lowestRatio) {
                lowestRatio = ratio;
                most = entry.getKey();
            }
        }
        return most;
    }
}

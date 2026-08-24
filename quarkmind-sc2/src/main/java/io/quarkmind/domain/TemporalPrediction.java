package io.quarkmind.domain;

import java.util.List;

public record TemporalPrediction(
        String predictedNextPhase,
        Trend economyTrend,
        Trend armyTrend,
        double minutesToNextTransition,
        double confidence,
        int matchCount,
        double bestMatchScore
) {
    public enum Trend { GROWING, STABLE, DECLINING, SPIKE, UNKNOWN }

    public static Trend computeEconomyTrend(List<TimelineObservation> lookahead) {
        return computeTrend(lookahead.stream().mapToInt(TimelineObservation::ourMinerals).toArray());
    }

    public static Trend computeArmyTrend(List<TimelineObservation> lookahead) {
        return computeTrend(lookahead.stream().mapToInt(TimelineObservation::ourArmySupply).toArray());
    }

    static Trend computeTrend(int[] values) {
        if (values.length < 2) return Trend.UNKNOWN;

        double mean = 0;
        for (int v : values) mean += v;
        mean /= values.length;
        if (mean == 0) return Trend.STABLE;

        for (int i = 1; i < values.length; i++) {
            if (Math.abs(values[i] - values[i - 1]) > 0.5 * mean) return Trend.SPIKE;
        }

        boolean growing = true, declining = true;
        for (int i = 1; i < values.length; i++) {
            if (values[i] < values[i - 1]) growing = false;
            if (values[i] > values[i - 1]) declining = false;
        }
        if (growing) return Trend.GROWING;
        if (declining) return Trend.DECLINING;

        int min = values[0], max = values[0];
        for (int v : values) { min = Math.min(min, v); max = Math.max(max, v); }
        if ((max - min) < 0.1 * mean) return Trend.STABLE;

        return Trend.UNKNOWN;
    }

    public static double computeConfidence(double dtwScore, int agreeingCount, int topK) {
        double base = dtwScore * ((double) agreeingCount / topK);
        double boost;
        if (agreeingCount >= topK) {
            boost = 1.5;
        } else if (agreeingCount >= 3) {
            boost = 1.3;
        } else {
            boost = 0.5;
        }
        return Math.min(1.0, base * boost);
    }
}

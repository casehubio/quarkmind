package io.quarkmind.plugin.scouting;

import io.quarkmind.domain.UnitType;
import io.quarkmind.plugin.scouting.events.EnemyUnitFirstSeen;

import java.util.List;
import java.util.Map;

public final class StrategyFeatureExtractor {

    private static final int UNIT_TYPE_COUNT = UnitType.values().length;
    private static final int FEATURE_LENGTH = UNIT_TYPE_COUNT + 1;

    public Map<String, float[][]> extract(List<EnemyUnitFirstSeen> observations, double gameTimeMinutes) {
        float[] vector = new float[FEATURE_LENGTH];
        for (EnemyUnitFirstSeen obs : observations) {
            vector[obs.type().ordinal()] += 1.0f;
        }
        vector[FEATURE_LENGTH - 1] = (float) gameTimeMinutes;
        return Map.of("input", new float[][] { vector });
    }
}

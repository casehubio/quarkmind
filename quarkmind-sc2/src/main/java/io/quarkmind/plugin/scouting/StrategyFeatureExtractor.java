package io.quarkmind.plugin.scouting;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

public final class StrategyFeatureExtractor {

    private static final int FEATURES_PER_WINDOW = TemporalWindowAccumulator.FEATURES_PER_WINDOW;
    private static final int MAX_WINDOWS = TemporalWindowAccumulator.MAX_WINDOWS;
    private static final int FLATTENED_SIZE = MAX_WINDOWS * FEATURES_PER_WINDOW;
    private static final int HAS_VISION_OFFSET = FEATURES_PER_WINDOW - 1;

    private final float[] normMean;
    private final float[] normStd;

    public StrategyFeatureExtractor() {
        try (InputStream is = getClass().getClassLoader()
                .getResourceAsStream("classifier/norm_stats.json")) {
            if (is == null) {
                throw new IllegalStateException("classifier/norm_stats.json not found on classpath");
            }
            JsonObject json = Json.createReader(is).readObject();
            normMean = toFloatArray(json.getJsonArray("mean"));
            normStd = toFloatArray(json.getJsonArray("std"));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    public StrategyFeatures extract(List<float[]> windowedFeatures, MapCharacteristics map) {
        float[] temporal = new float[FLATTENED_SIZE];
        for (int w = 0; w < MAX_WINDOWS; w++) {
            System.arraycopy(windowedFeatures.get(w), 0,
                temporal, w * FEATURES_PER_WINDOW, FEATURES_PER_WINDOW);
        }

        boolean hasPlayer = hasNonZeroBlock(temporal, 0, TemporalWindowAccumulator.FEATURES_PER_PLAYER);
        boolean hasOpponent = hasNonZeroBlock(temporal,
            TemporalWindowAccumulator.FEATURES_PER_PLAYER, 2 * TemporalWindowAccumulator.FEATURES_PER_PLAYER);

        for (int w = 0; w < MAX_WINDOWS; w++) {
            int base = w * FEATURES_PER_WINDOW;
            boolean populated = false;
            for (int f = 0; f < FEATURES_PER_WINDOW; f++) {
                if (temporal[base + f] != 0.0f) { populated = true; break; }
            }
            if (!populated) continue;
            for (int f = 0; f < FEATURES_PER_WINDOW; f++) {
                if (f == HAS_VISION_OFFSET) continue;
                if (normStd[f] > 0) {
                    temporal[base + f] = (temporal[base + f] - normMean[f]) / normStd[f];
                }
            }
        }

        float[] mapFeatures = map.toArray(hasPlayer, hasOpponent);

        return new StrategyFeatures(Map.of(
            "temporal", new float[][] { temporal },
            "map", new float[][] { mapFeatures }
        ));
    }

    private boolean hasNonZeroBlock(float[] data, int start, int end) {
        for (int w = 0; w < MAX_WINDOWS; w++) {
            for (int f = start; f < end; f++) {
                if (data[w * FEATURES_PER_WINDOW + f] != 0.0f) return true;
            }
        }
        return false;
    }

    private static float[] toFloatArray(JsonArray array) {
        float[] result = new float[array.size()];
        for (int i = 0; i < array.size(); i++) {
            result[i] = (float) array.getJsonNumber(i).doubleValue();
        }
        return result;
    }
}

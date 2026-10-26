package io.quarkmind.plugin.scouting;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

public final class StrategyFeatureExtractor {

    private static final int MAX_WINDOWS = TemporalWindowAccumulator.MAX_WINDOWS;

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
        int     onnxFPW  = FeatureIndexMaps.ONNX_FEATURES_PER_WINDOW;
        int     corePP   = FeatureIndexMaps.N_ONNX_CORE_PER_PLAYER;
        int     fullNP   = FeatureIndexMaps.N_FEATURES_PER_PLAYER;
        float[] temporal = new float[MAX_WINDOWS * onnxFPW];

        for (int w = 0; w < MAX_WINDOWS; w++) {
            float[] full     = windowedFeatures.get(w);
            int     destBase = w * onnxFPW;
            System.arraycopy(full, 0, temporal, destBase, corePP);
            System.arraycopy(full, fullNP, temporal, destBase + corePP, corePP);
            temporal[destBase + 2 * corePP] = full[FeatureIndexMaps.HAS_VISION_INDEX];
        }

        boolean hasPlayer   = hasNonZeroBlock(temporal, 0, corePP, onnxFPW);
        boolean hasOpponent = hasNonZeroBlock(temporal, corePP, 2 * corePP, onnxFPW);

        for (int w = 0; w < MAX_WINDOWS; w++) {
            int base = w * onnxFPW;
            for (int f = 0; f < onnxFPW; f++) {
                if (f == 2 * corePP) {continue;}
                if (normStd[f] > 0) {
                    temporal[base + f] = (temporal[base + f] - normMean[f]) / normStd[f];
                }
            }
        }

        float[] mapFeatures = map.toArray(hasPlayer, hasOpponent);

        return new StrategyFeatures(Map.of(
                "temporal", new float[][]{temporal},
                "map", new float[][]{mapFeatures}
                                          ));
    }

    private boolean hasNonZeroBlock(float[] data, int start, int end, int stride) {
        for (int w = 0; w < MAX_WINDOWS; w++) {
            for (int f = start; f < end; f++) {
                if (data[w * stride + f] != 0.0f) {return true;}
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

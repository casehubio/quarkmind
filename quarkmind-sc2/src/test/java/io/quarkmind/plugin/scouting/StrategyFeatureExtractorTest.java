package io.quarkmind.plugin.scouting;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class StrategyFeatureExtractorTest {

    @Test
    void extract_producesCorrectTensorDimensions() {
        var extractor = new StrategyFeatureExtractor();
        var accumulator = new TemporalWindowAccumulator();
        for (int i = 0; i < 360; i++) {
            accumulator.addSnapshot(new WindowSnapshot(
                new float[134], new float[134], 0.5f));
        }
        var result = extractor.extract(
            accumulator.getWindowedFeatures(),
            MapCharacteristics.DEFAULT);
        assertThat(result.tensors()).containsKeys("temporal", "map");
        assertThat(result.tensors().get("temporal")).hasNumberOfRows(1);
        assertThat(result.tensors().get("temporal")[0]).hasSize(2690);
        assertThat(result.tensors().get("map")).hasNumberOfRows(1);
        assertThat(result.tensors().get("map")[0]).hasSize(6);
    }

    @Test
    void extract_normalizesTemporalFeatures() {
        var extractor = new StrategyFeatureExtractor();
        var accumulator = new TemporalWindowAccumulator();
        for (int i = 0; i < 60; i++) {
            var player = new float[134];
            player[0] = 1.0f;
            accumulator.addSnapshot(new WindowSnapshot(
                player, new float[134], 0.0f));
        }
        var result = extractor.extract(
            accumulator.getWindowedFeatures(),
            MapCharacteristics.DEFAULT);
        float raw = result.tensors().get("temporal")[0][0];
        assertThat(raw).isNotEqualTo(1.0f);
    }

    @Test
    void extract_hasVisionFlagNotNormalized() {
        var extractor = new StrategyFeatureExtractor();
        var accumulator = new TemporalWindowAccumulator();
        for (int i = 0; i < 60; i++) {
            accumulator.addSnapshot(new WindowSnapshot(
                new float[134], new float[134], 1.0f));
        }
        var result = extractor.extract(
            accumulator.getWindowedFeatures(),
            MapCharacteristics.DEFAULT);
        assertThat(result.tensors().get("temporal")[0][268]).isEqualTo(1.0f);
    }

    @Test
    void extract_zeroPaddedWindowsStayZero() {
        var extractor = new StrategyFeatureExtractor();
        var accumulator = new TemporalWindowAccumulator();
        for (int i = 0; i < 60; i++) {
            var player = new float[134];
            player[0] = 5.0f;
            accumulator.addSnapshot(new WindowSnapshot(
                player, new float[134], 0.5f));
        }
        var result = extractor.extract(
            accumulator.getWindowedFeatures(),
            MapCharacteristics.DEFAULT);
        float[] temporal = result.tensors().get("temporal")[0];
        for (int f = 269; f < 538; f++) {
            assertThat(temporal[f]).as("zero-padded window feature at %d", f).isEqualTo(0.0f);
        }
    }

    @Test
    void extract_mapFeaturesIncludeAvailabilityFlags() {
        var extractor = new StrategyFeatureExtractor();
        var accumulator = new TemporalWindowAccumulator();
        for (int i = 0; i < 60; i++) {
            var player = new float[134];
            player[0] = 1.0f;
            accumulator.addSnapshot(new WindowSnapshot(
                player, new float[134], 0.0f));
        }
        var result = extractor.extract(
            accumulator.getWindowedFeatures(),
            MapCharacteristics.DEFAULT);
        float[] map = result.tensors().get("map")[0];
        assertThat(map[4]).isEqualTo(1.0f);
        assertThat(map[5]).isEqualTo(0.0f);
    }
}

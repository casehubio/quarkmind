package io.quarkmind.plugin.scouting;

import io.quarkmind.domain.UnitType;
import io.quarkmind.plugin.scouting.events.EnemyUnitFirstSeen;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StrategyFeatureExtractorTest {

    @Test
    void emptyObservations_producesTensorWithBatchDimension() {
        var extractor = new StrategyFeatureExtractor();
        Map<String, float[][]> features = extractor.extract(List.of(), 0.0);
        assertNotNull(features);
        assertFalse(features.isEmpty());
        for (float[][] tensor : features.values()) {
            assertEquals(1, tensor.length, "batch dimension should be 1");
        }
    }

    @Test
    void featureVectorIncludesGameTime() {
        var extractor = new StrategyFeatureExtractor();
        Map<String, float[][]> features = extractor.extract(List.of(), 5.0);
        float[] vector = features.get("input")[0];
        int gameTimeIdx = vector.length - 1;
        assertEquals(5.0f, vector[gameTimeIdx], 0.01f, "last feature should be game time in minutes");
    }

    @Test
    void observedUnits_incrementCountAtCorrectIndex() {
        var extractor = new StrategyFeatureExtractor();
        var observations = List.of(
                new EnemyUnitFirstSeen(UnitType.ZERGLING, 30_000),
                new EnemyUnitFirstSeen(UnitType.ZERGLING, 35_000),
                new EnemyUnitFirstSeen(UnitType.ROACH, 40_000));
        Map<String, float[][]> features = extractor.extract(observations, 2.0);
        float[] vector = features.get("input")[0];
        int zerglingIdx = UnitType.ZERGLING.ordinal();
        int roachIdx = UnitType.ROACH.ordinal();
        assertEquals(2.0f, vector[zerglingIdx], 0.01f, "zergling count should be 2");
        assertEquals(1.0f, vector[roachIdx], 0.01f, "roach count should be 1");
    }

    @Test
    void featureVectorLength_isUnitTypeCountPlusOne() {
        var extractor = new StrategyFeatureExtractor();
        Map<String, float[][]> features = extractor.extract(List.of(), 0.0);
        float[] vector = features.get("input")[0];
        assertEquals(UnitType.values().length + 1, vector.length,
                "feature vector = one slot per UnitType + game time");
    }

    @Test
    void deterministicOutput_sameInputSameResult() {
        var extractor = new StrategyFeatureExtractor();
        var obs = List.of(new EnemyUnitFirstSeen(UnitType.MARINE, 10_000));
        Map<String, float[][]> a = extractor.extract(obs, 1.0);
        Map<String, float[][]> b = extractor.extract(obs, 1.0);
        assertArrayEquals(a.get("input")[0], b.get("input")[0]);
    }
}

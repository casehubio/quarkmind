package io.quarkmind.sc2.replay;

import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.intent.TrainIntent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StrippedReplayFeatureExtractorCalibrationTest {

    @Test
    void calibrationFactorOverridesMultiplication() {
        var extractor = new StrippedReplayFeatureExtractor();
        extractor.setCalibrationFactors(Map.of(UnitType.MARINE, 2.0));

        List<Map<String, Object>> events = extractor.processTrainForTest(
                new TrainIntent("rax1", UnitType.MARINE), 1000L, 1, 100, 0L);

        long unitBornCount = events.stream()
                .filter(e -> "UnitBorn".equals(e.get("evtTypeName")))
                .count();
        assertEquals(2, unitBornCount, "factor 2.0 should produce 2 UnitBorn events");
    }

    @Test
    void noFactorFallsBackToBuildingCount() {
        var extractor = new StrippedReplayFeatureExtractor();
        assertNull(extractor.getCalibrationFactors());

        List<Map<String, Object>> events = extractor.processTrainForTest(
                new TrainIntent("rax1", UnitType.MARINE), 1000L, 1, 100, 0L);

        long unitBornCount = events.stream()
                .filter(e -> "UnitBorn".equals(e.get("evtTypeName")))
                .count();
        assertEquals(1, unitBornCount, "no factors = repeatCount 1 (no building context in test)");
    }

    @Test
    void factorRoundsDown() {
        var extractor = new StrippedReplayFeatureExtractor();
        extractor.setCalibrationFactors(Map.of(UnitType.SCV, 1.44));

        List<Map<String, Object>> events = extractor.processTrainForTest(
                new TrainIntent("cc1", UnitType.SCV), 1000L, 1, 100, 0L);

        long count = events.stream()
                .filter(e -> "UnitBorn".equals(e.get("evtTypeName")))
                .count();
        assertEquals(1, count, "1.44 rounds to 1");
    }

    @Test
    void fractionalFactorTruncatesPerCommand() {
        var extractor = new StrippedReplayFeatureExtractor();
        extractor.setCalibrationFactors(Map.of(UnitType.SCV, 1.6));

        List<Map<String, Object>> events = extractor.processTrainForTest(
                new TrainIntent("cc1", UnitType.SCV), 1000L, 1, 200, 0L);

        long count = events.stream()
                .filter(e -> "UnitBorn".equals(e.get("evtTypeName")))
                .count();
        assertEquals(1, count, "1.6 truncates to 1 per command (remainder 0.6 accumulates in extract())");
    }

    @Test
    void techlabUnitsIgnoreCalibrationFactors() {
        var extractor = new StrippedReplayFeatureExtractor();
        extractor.setCalibrationFactors(Map.of(UnitType.SIEGE_TANK, 3.0));

        List<Map<String, Object>> events = extractor.processTrainForTest(
                new TrainIntent("fac1", UnitType.SIEGE_TANK), 1000L, 1, 100, 0L);

        long count = events.stream()
                .filter(e -> "UnitBorn".equals(e.get("evtTypeName")))
                .count();
        assertEquals(1, count, "TECHLAB_UNITS should always produce 1 regardless of factor");
    }
}

package io.quarkmind.sc2.detector;

import io.quarkmind.domain.GamePhase;
import io.quarkmind.domain.Race;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DetectorCatalogTest {

    @Test
    void catalogHas23ConcreteDetectors() {
        var config = DetectorCatalog.create();
        long count = config.detectors().stream()
            .mapToLong(d -> d.positiveArchetypes().size())
            .sum();
        assertThat(count).isEqualTo(23);
    }

    @Test
    void earlyPhaseHas7Detectors() {
        var config = DetectorCatalog.create();
        var registry = new DetectorRegistry(config);
        long count = 0;
        for (Race race : List.of(Race.TERRAN, Race.ZERG, Race.PROTOSS)) {
            count += registry.getActiveDetectors(GamePhase.EARLY, race).size();
        }
        assertThat(count).isEqualTo(7);
    }

    @Test
    void midPhaseHas11Detectors() {
        var config = DetectorCatalog.create();
        var registry = new DetectorRegistry(config);
        long count = 0;
        for (Race race : List.of(Race.TERRAN, Race.ZERG, Race.PROTOSS)) {
            count += registry.getActiveDetectors(GamePhase.MID, race).size();
        }
        assertThat(count).isEqualTo(11);
    }

    @Test
    void latePhaseHas5Detectors() {
        var config = DetectorCatalog.create();
        var registry = new DetectorRegistry(config);
        long count = 0;
        for (Race race : List.of(Race.TERRAN, Race.ZERG, Race.PROTOSS)) {
            count += registry.getActiveDetectors(GamePhase.LATE, race).size();
        }
        assertThat(count).isEqualTo(5);
    }

    @Test
    void allModelPathsResolveOnClasspath() {
        var config = DetectorCatalog.create();
        for (var def : config.detectors()) {
            for (var entry : def.positiveArchetypes().entrySet()) {
                String key = def.key(entry.getKey());
                var url = getClass().getClassLoader()
                    .getResource("models/detectors/strategy_" + key + ".onnx");
                assertThat(url)
                    .as("Model file for detector %s", key)
                    .isNotNull();
            }
        }
    }
}

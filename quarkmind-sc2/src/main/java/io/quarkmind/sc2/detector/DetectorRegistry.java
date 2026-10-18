package io.quarkmind.sc2.detector;

import io.quarkmind.domain.GamePhase;
import io.quarkmind.domain.Race;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DetectorRegistry {

    private final DetectorCatalogConfig config;
    private final Map<String, Object> sessions = new ConcurrentHashMap<>();

    public DetectorRegistry(DetectorCatalogConfig config) {
        this.config = config;
    }

    public List<DetectorCatalogConfig.DetectorDef> getActiveDetectors(GamePhase phase, Race enemyRace) {
        return config.detectors().stream()
            .filter(d -> d.phase() == phase)
            .filter(d -> d.positiveArchetypes().containsKey(enemyRace))
            .toList();
    }

    public FeatureTier getFeatureTier(String detectorName) {
        return config.detectors().stream()
            .filter(d -> d.name().equals(detectorName))
            .findFirst()
            .map(DetectorCatalogConfig.DetectorDef::featureTier)
            .orElseThrow(() -> new IllegalArgumentException(
                "Unknown detector: " + detectorName));
    }

    public DetectorCatalogConfig config() {
        return config;
    }
}

package io.quarkmind.sc2.detector;

import io.quarkmind.domain.GamePhase;
import io.quarkmind.domain.Race;

import java.util.List;
import java.util.Map;

public record DetectorCatalogConfig(List<DetectorDef> detectors) {

    public record DetectorDef(
        String name,
        GamePhase phase,
        FeatureTier featureTier,
        Map<Race, List<String>> positiveArchetypes,
        String modelPath
    ) {
        public String key(Race enemyRace) {
            return name + "_vs_" + enemyRace.name().toLowerCase();
        }
    }
}

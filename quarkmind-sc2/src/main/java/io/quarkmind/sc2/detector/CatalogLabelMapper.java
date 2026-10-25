package io.quarkmind.sc2.detector;

import io.quarkmind.domain.Race;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class CatalogLabelMapper {

    private static final Map<Race, Set<String>> VALID_LABELS;

    static {
        VALID_LABELS = new HashMap<>();
        var config = DetectorCatalog.create();
        for (var def : config.detectors()) {
            for (var entry : def.positiveArchetypes().entrySet()) {
                VALID_LABELS.computeIfAbsent(entry.getKey(), k -> new HashSet<>())
                            .addAll(entry.getValue());
            }
        }
    }

    public static boolean isValidLabel(String label, Race enemyRace) {
        var labels = VALID_LABELS.get(enemyRace);
        return labels != null && labels.contains(label);
    }

    public static Set<String> validLabelsForMatchup(Race enemyRace) {
        return Set.copyOf(VALID_LABELS.getOrDefault(enemyRace, Set.of()));
    }

    private CatalogLabelMapper() {}
}

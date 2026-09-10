package io.quarkmind.plugin.scouting;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DroolsEvidenceReplayTest {

    static List<Map<String, Object>> l1Inputs;
    static List<Map<String, Object>> l1Outputs;

    @BeforeAll
    static void loadFixtures() throws Exception {
        var mapper = new ObjectMapper();
        try (InputStream in = DroolsEvidenceReplayTest.class.getResourceAsStream(
                "/fixtures/pipeline/nothing-4720936/l1-input.json")) {
            l1Inputs = mapper.readValue(in, new TypeReference<>() {});
        }
        try (InputStream in = DroolsEvidenceReplayTest.class.getResourceAsStream(
                "/fixtures/pipeline/nothing-4720936/l1-output.json")) {
            l1Outputs = mapper.readValue(in, new TypeReference<>() {});
        }
    }

    @Test
    void fixtures_haveMatchingFrameCounts() {
        assertThat(l1Inputs).hasSameSizeAs(l1Outputs);
    }

    @Test
    void fixtures_containFramesWithEnemies() {
        long framesWithEnemies = l1Inputs.stream()
            .filter(i -> ((Number) i.get("enemyCount")).intValue() > 0)
            .count();
        assertThat(framesWithEnemies)
            .as("At least one sampled frame should have enemies")
            .isGreaterThan(0);
    }

    @Test
    void l1Evidence_firesForMidGameCompositions() {
        long framesWithEvidence = l1Outputs.stream()
            .filter(o -> !((List<?>) o.get("evidenceMarkers")).isEmpty())
            .count();
        assertThat(framesWithEvidence)
            .as("Mid-game frames should produce evidence markers")
            .isGreaterThan(0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void l1Evidence_containsExpectedArchetypes() {
        var allArchetypes = l1Outputs.stream()
            .flatMap(o -> ((List<Map<String, Object>>) o.get("evidenceMarkers")).stream())
            .map(m -> (String) m.get("archetype"))
            .distinct()
            .toList();
        assertThat(allArchetypes)
            .as("Evidence should contain Zerg mid-game archetypes (PvZ replay)")
            .anyMatch(a -> a.startsWith("ZERG_"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void l1Evidence_weightsAreInValidRange() {
        l1Outputs.stream()
            .flatMap(o -> ((List<Map<String, Object>>) o.get("evidenceMarkers")).stream())
            .forEach(m -> {
                double weight = ((Number) m.get("weight")).doubleValue();
                assertThat(weight)
                    .as("Evidence weight for %s", m.get("archetype"))
                    .isBetween(0.0, 1.0);
            });
    }

    @Test
    @SuppressWarnings("unchecked")
    void l1Revisions_counterIndicateRushes() {
        var revisionArchetypes = l1Outputs.stream()
            .flatMap(o -> ((List<Map<String, Object>>) o.get("revisions")).stream())
            .map(r -> (String) r.get("archetype"))
            .distinct()
            .toList();
        assertThat(revisionArchetypes)
            .as("Counter-indication revisions should target rush archetypes")
            .anyMatch(a -> a.contains("RUSH"));
    }
}

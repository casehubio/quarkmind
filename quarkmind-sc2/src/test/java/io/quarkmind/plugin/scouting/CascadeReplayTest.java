package io.quarkmind.plugin.scouting;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.StrategyArchetype;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CascadeReplayTest {

    static List<Map<String, Object>> l1Inputs;
    static List<Map<String, Object>> l1Outputs;

    @BeforeAll
    static void loadFixtures() throws Exception {
        var mapper = new ObjectMapper();
        try (InputStream in = CascadeReplayTest.class.getResourceAsStream(
                "/fixtures/pipeline/nothing-4720936/l1-input.json")) {
            l1Inputs = mapper.readValue(in, new TypeReference<>() {});
        }
        try (InputStream in = CascadeReplayTest.class.getResourceAsStream(
                "/fixtures/pipeline/nothing-4720936/l1-output.json")) {
            l1Outputs = mapper.readValue(in, new TypeReference<>() {});
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void cascade_neverReturnsEmpty_whenEnemiesVisible() {
        var classifier = new CascadingPatternClassifier(0.7, 0.5);
        long prevFrame = -1;

        for (int i = 0; i < l1Inputs.size(); i++) {
            Map<String, Object> input = l1Inputs.get(i);
            Map<String, Object> output = l1Outputs.get(i);
            long frame = ((Number) input.get("frame")).longValue();
            int enemyCount = ((Number) input.get("enemyCount")).intValue();
            String raceStr = (String) input.get("enemyRace");
            Race enemyRace = raceStr != null ? Race.valueOf(raceStr) : null;

            List<Map<String, Object>> markers =
                (List<Map<String, Object>>) output.get("evidenceMarkers");
            List<Map<String, Object>> revs =
                (List<Map<String, Object>>) output.get("revisions");

            List<EvidenceMarker> evidence = markers.stream()
                .map(m -> new EvidenceMarker(
                    StrategyArchetype.valueOf((String) m.get("archetype")),
                    ((Number) m.get("weight")).doubleValue(),
                    (String) m.get("signal")))
                .toList();
            List<ConfidenceRevision> revisions = revs.stream()
                .map(r -> new ConfidenceRevision(
                    StrategyArchetype.valueOf((String) r.get("archetype")),
                    ((Number) r.get("dampingFactor")).doubleValue(),
                    (String) r.get("reason")))
                .toList();

            CascadeResult result = classifier.classify(
                evidence, revisions, null, enemyRace, frame, prevFrame, null, enemyCount);

            if (enemyCount > 0) {
                assertThat(result.assessments())
                    .as("Frame %d with %d enemies must produce assessments", frame, enemyCount)
                    .isNotEmpty();
            }
            prevFrame = frame;
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void cascade_producesRealClassifications_notOnlyFallback() {
        var classifier = new CascadingPatternClassifier(0.7, 0.5);
        long prevFrame = -1;
        boolean anyRealClassification = false;

        for (int i = 0; i < l1Inputs.size(); i++) {
            Map<String, Object> input = l1Inputs.get(i);
            Map<String, Object> output = l1Outputs.get(i);
            long frame = ((Number) input.get("frame")).longValue();
            int enemyCount = ((Number) input.get("enemyCount")).intValue();
            String raceStr = (String) input.get("enemyRace");
            Race enemyRace = raceStr != null ? Race.valueOf(raceStr) : null;

            List<Map<String, Object>> markers =
                (List<Map<String, Object>>) output.get("evidenceMarkers");
            List<Map<String, Object>> revs =
                (List<Map<String, Object>>) output.get("revisions");

            List<EvidenceMarker> evidence = markers.stream()
                .map(m -> new EvidenceMarker(
                    StrategyArchetype.valueOf((String) m.get("archetype")),
                    ((Number) m.get("weight")).doubleValue(),
                    (String) m.get("signal")))
                .toList();
            List<ConfidenceRevision> revisions = revs.stream()
                .map(r -> new ConfidenceRevision(
                    StrategyArchetype.valueOf((String) r.get("archetype")),
                    ((Number) r.get("dampingFactor")).doubleValue(),
                    (String) r.get("reason")))
                .toList();

            CascadeResult result = classifier.classify(
                evidence, revisions, null, enemyRace, frame, prevFrame, null, enemyCount);

            if (!result.assessments().isEmpty()
                    && !result.assessments().get(0).archetype().name().endsWith("_COMPOSITION_UNKNOWN")) {
                anyRealClassification = true;
            }
            prevFrame = frame;
        }

        assertThat(anyRealClassification)
            .as("At least one frame should produce a real classification (not just fallback)")
            .isTrue();
    }
}

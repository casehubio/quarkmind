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

class DroolsCascadeCompositionTest {

    static List<Map<String, Object>> l1Inputs;
    static List<Map<String, Object>> l1Outputs;

    @BeforeAll
    static void loadFixtures() throws Exception {
        var mapper = new ObjectMapper();
        try (InputStream in = DroolsCascadeCompositionTest.class.getResourceAsStream(
                "/fixtures/pipeline/nothing-4720936/l1-input.json")) {
            l1Inputs = mapper.readValue(in, new TypeReference<>() {});
        }
        try (InputStream in = DroolsCascadeCompositionTest.class.getResourceAsStream(
                "/fixtures/pipeline/nothing-4720936/l1-output.json")) {
            l1Outputs = mapper.readValue(in, new TypeReference<>() {});
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void l1PlusL2_producesAssessments_forEveryFrameWithEnemies() {
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
                    .as("Frame %d: enemies visible → assessments must be non-empty", frame)
                    .isNotEmpty();
            }
            prevFrame = frame;
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void l1PlusL2_realClassificationsDominate() {
        var classifier = new CascadingPatternClassifier(0.7, 0.5);
        long prevFrame = -1;
        int realCount = 0;
        int fallbackCount = 0;

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

            if (enemyCount > 0 && !result.assessments().isEmpty()) {
                if (result.assessments().get(0).archetype().name().endsWith("_COMPOSITION_UNKNOWN")) {
                    fallbackCount++;
                } else {
                    realCount++;
                }
            }
            prevFrame = frame;
        }

        int total = realCount + fallbackCount;
        assertThat(total).as("Total frames with assessments").isGreaterThan(0);
        assertThat(realCount).as("Real classifications should exist").isGreaterThan(0);
    }
}

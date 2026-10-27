package io.quarkmind.sc2.replay;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkmind.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PriorExportPipelineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void learnPriors_fromLabels() {
        var labels = List.of("RUSH", "RUSH", "RUSH", "MACRO_ECONOMY", "MACRO_ECONOMY",
            "TECH_RUSH", "BIO_TIMING", "BIO_TIMING", "BIO_TIMING", "BIO_TIMING");

        var allArchetypes = Set.of("RUSH", "MACRO_ECONOMY", "TECH_RUSH", "BIO_TIMING",
            "PROXY", "BANSHEE_HARASS", "AIR_SUPERIORITY", "MECH_PUSH");

        var priors = PriorExportPipeline.learnPriors(labels, allArchetypes);

        assertEquals(allArchetypes.size(), priors.size());
        assertTrue(priors.get("BIO_TIMING") > priors.get("RUSH"));
        assertTrue(priors.get("RUSH") > priors.get("TECH_RUSH"));
        assertTrue(priors.get("PROXY") > 0);

        double sum = priors.values().stream().mapToDouble(d -> d).sum();
        assertEquals(1.0, sum, 0.001);
    }

    @Test
    void learnPriors_laplaceSmoothingPreventsZero() {
        var labels = List.of("RUSH", "RUSH", "RUSH");
        var allArchetypes = Set.of("RUSH", "MACRO_ECONOMY", "TECH_RUSH");

        var priors = PriorExportPipeline.learnPriors(labels, allArchetypes);

        for (double p : priors.values()) {
            assertTrue(p > 0, "Prior should be non-zero with Laplace smoothing");
        }
    }

    @Test
    void learnObservationCpts_fromSequences() {
        var seq1 = new ScoutingObservationSequence("hash1", 1, Race.TERRAN, Race.ZERG,
            List.of(
                new ScoutingObservation("first_building", "barracks", 900),
                new ScoutingObservation("gas_timing", "normal", 2500)
            ), "RUSH");

        var seq2 = new ScoutingObservationSequence("hash2", 1, Race.TERRAN, Race.ZERG,
            List.of(
                new ScoutingObservation("first_building", "barracks", 850),
                new ScoutingObservation("gas_timing", "early", 1500)
            ), "RUSH");

        var seq3 = new ScoutingObservationSequence("hash3", 1, Race.TERRAN, Race.ZERG,
            List.of(
                new ScoutingObservation("first_building", "factory", 2000),
                new ScoutingObservation("gas_timing", "early", 1200)
            ), "MECH_PUSH");

        var allArchetypes = Set.of("RUSH", "MECH_PUSH", "MACRO_ECONOMY");
        var cpts = PriorExportPipeline.learnObservationCpts(
            List.of(seq1, seq2, seq3), allArchetypes);

        assertTrue(cpts.containsKey("first_building"));
        var fbCpt = cpts.get("first_building");

        var rushDist = fbCpt.get("RUSH");
        assertNotNull(rushDist);

        for (var entry : fbCpt.entrySet()) {
            for (double p : entry.getValue()) {
                assertTrue(p >= 0.01, "CPT below floor: " + p);
                assertTrue(p <= 0.99, "CPT above ceiling: " + p);
            }
            double sum = 0;
            for (double p : entry.getValue()) sum += p;
            assertEquals(1.0, sum, 0.01, "CPT should sum to 1.0 for " + entry.getKey());
        }
    }

    @Test
    void learnObservationCpts_unknownLabelIgnored() {
        var seq = new ScoutingObservationSequence("hash1", 1, Race.TERRAN, Race.ZERG,
            List.of(new ScoutingObservation("first_building", "barracks", 900)),
            "UNKNOWN_LABEL");

        var allArchetypes = Set.of("RUSH");
        var cpts = PriorExportPipeline.learnObservationCpts(List.of(seq), allArchetypes);

        assertTrue(cpts.containsKey("first_building"));
    }

    @Test
    void exportToJson_writesFiles(@TempDir Path tempDir) throws Exception {
        var priors = Map.of("RUSH", 0.3, "MACRO_ECONOMY", 0.5, "TECH_RUSH", 0.2);
        var cpts = Map.<String, Map<String, double[]>>of("first_building", Map.of(
            "RUSH", new double[]{0.7, 0.2, 0.1},
            "MACRO_ECONOMY", new double[]{0.2, 0.5, 0.3}
        ));
        var stateIndex = Map.of("first_building", List.of("barracks", "factory", "gateway"));

        PriorExportPipeline.exportToJson(tempDir, "EARLY", "vs_terran", priors, cpts, stateIndex);

        assertTrue(java.nio.file.Files.exists(tempDir.resolve("opponent-priors-EARLY-vs_terran.json")));
        assertTrue(java.nio.file.Files.exists(tempDir.resolve("observation-cpts-EARLY-vs_terran.json")));

        var loadedPriors = MAPPER.readValue(
            tempDir.resolve("opponent-priors-EARLY-vs_terran.json").toFile(),
            new TypeReference<Map<String, Double>>() {});
        assertEquals(0.3, loadedPriors.get("RUSH"), 0.001);
    }

    @Test
    void collectStateIndex_groupsByVariable() {
        var sequences = List.of(
            new ScoutingObservationSequence("h1", 1, Race.TERRAN, Race.ZERG,
                List.of(
                    new ScoutingObservation("first_building", "barracks", 900),
                    new ScoutingObservation("gas_timing", "early", 1500)
                ), "RUSH"),
            new ScoutingObservationSequence("h2", 1, Race.TERRAN, Race.ZERG,
                List.of(
                    new ScoutingObservation("first_building", "factory", 2000),
                    new ScoutingObservation("gas_timing", "normal", 2500)
                ), "MECH_PUSH")
        );

        var stateIndex = PriorExportPipeline.collectStateIndex(sequences);
        assertEquals(List.of("barracks", "factory"), stateIndex.get("first_building"));
        assertEquals(List.of("early", "normal"), stateIndex.get("gas_timing"));
    }
}

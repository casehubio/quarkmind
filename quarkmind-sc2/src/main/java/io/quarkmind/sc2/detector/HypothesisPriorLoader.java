package io.quarkmind.sc2.detector;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkmind.domain.GamePhase;
import io.quarkmind.domain.Race;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

public class HypothesisPriorLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record ObservationCpts(
        Map<String, String[]> stateIndex,
        Map<String, Map<String, double[]>> cpts
    ) {}

    public static Map<String, Double> loadOpponentPriors(GamePhase phase, Race enemyRace) {
        String path = "priors/opponent-priors-" + phase.name() + "-vs_"
            + enemyRace.name().toLowerCase() + ".json";
        try (InputStream is = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalArgumentException("Prior resource not found: " + path);
            }
            return MAPPER.readValue(is, new TypeReference<>() {});
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load priors: " + path, e);
        }
    }

    public static ObservationCpts loadObservationCpts(GamePhase phase, Race enemyRace) {
        String path = "priors/observation-cpts-" + phase.name() + "-vs_"
            + enemyRace.name().toLowerCase() + ".json";
        try (InputStream is = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalArgumentException("CPT resource not found: " + path);
            }
            JsonNode root = MAPPER.readTree(is);

            Map<String, String[]> stateIndex = new LinkedHashMap<>();
            root.get("stateIndex").fields().forEachRemaining(e ->
                stateIndex.put(e.getKey(), MAPPER.convertValue(e.getValue(), String[].class)));

            Map<String, Map<String, double[]>> cpts = new LinkedHashMap<>();
            root.get("cpts").fields().forEachRemaining(varEntry -> {
                Map<String, double[]> archetypeMap = new LinkedHashMap<>();
                varEntry.getValue().fields().forEachRemaining(archEntry ->
                    archetypeMap.put(archEntry.getKey(),
                        MAPPER.convertValue(archEntry.getValue(), double[].class)));
                cpts.put(varEntry.getKey(), archetypeMap);
            });

            return new ObservationCpts(stateIndex, cpts);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load CPTs: " + path, e);
        }
    }
}

package io.quarkmind.sc2.detector;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkmind.domain.GamePhase;
import io.quarkmind.domain.Race;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class HypothesisPriorLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record ObservationCpts(
        Map<String, String[]> stateIndex,
        Map<String, Map<String, double[]>> cpts
    ) {}

    public record ExpertOverride(
            String whenOpponent,
            String preferredResponse,
            double priorMultiplier,
            double floor
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

    public static Map<String, Map<String, Double>> loadResponsePriors(Race enemyRace) {
        String matchup = "vs_" + enemyRace.name().toLowerCase();
        String path    = "priors/response-priors-" + matchup + ".json";
        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            if (is == null) {return Map.of();}
            return MAPPER.readValue(is, new TypeReference<>() {});
        } catch (IOException e) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    public static List<ExpertOverride> loadExpertOverrides(Race enemyRace) {
        String matchup = "vs_" + enemyRace.name().toLowerCase();
        try (InputStream is = Thread.currentThread().getContextClassLoader()
                                    .getResourceAsStream("priors/response-overrides.yaml")) {
            if (is == null) {return List.of();}
            ObjectMapper                           yaml    = new ObjectMapper(new YAMLFactory());
            Map<String, List<Map<String, Object>>> root    = yaml.readValue(is, new TypeReference<>() {});
            List<Map<String, Object>>              entries = root.getOrDefault(matchup, List.of());
            return entries.stream().map(e -> new ExpertOverride(
                    (String) e.get("when_opponent"),
                    (String) e.get("preferred_response"),
                    ((Number) e.get("prior_multiplier")).doubleValue(),
                    e.containsKey("floor") ? ((Number) e.get("floor")).doubleValue() : 0.05
            )).toList();
        } catch (IOException ex) {
            return List.of();
        }
    }

    public static Map<String, Double> applyExpertOverrides(
            Map<String, Double> priors, List<ExpertOverride> overrides) {
        Map<String, Double> adjusted = new LinkedHashMap<>(priors);
        for (ExpertOverride ov : overrides) {
            adjusted.computeIfPresent(ov.preferredResponse(),
                                      (k, v) -> Math.max(ov.floor(), v * ov.priorMultiplier()));
        }
        double total = adjusted.values().stream().mapToDouble(d -> d).sum();
        adjusted.replaceAll((k, v) -> v / total);
        return adjusted;
    }

}

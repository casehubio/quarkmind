package io.quarkmind.plugin.commentary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FewShotRetriever {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Map<String, List<FewShotExample>> cellCache = new ConcurrentHashMap<>();
    private final Path baseDir;

    private FewShotRetriever(Path baseDir) {
        this.baseDir = baseDir;
    }

    public static FewShotRetriever fromDirectory(Path dir) {
        return new FewShotRetriever(dir);
    }

    public List<FewShotExample> retrieve(String phase, String eventType,
                                          String matchup, int limit) {
        String cellKey = phase + "_" + eventType;
        List<FewShotExample> examples = cellCache.computeIfAbsent(cellKey, this::loadCell);

        if (examples.isEmpty()) {
            return List.of();
        }

        List<FewShotExample> ranked = new ArrayList<>(examples);
        if (matchup != null && !matchup.isEmpty()) {
            ranked.sort((a, b) -> {
                boolean aMatch = matchup.equals(a.matchup());
                boolean bMatch = matchup.equals(b.matchup());
                if (aMatch != bMatch) return aMatch ? -1 : 1;
                return Double.compare(b.qualityScore(), a.qualityScore());
            });
        }

        return ranked.subList(0, Math.min(limit, ranked.size()));
    }

    private List<FewShotExample> loadCell(String cellKey) {
        Path cellFile = baseDir.resolve(cellKey + ".json");
        if (!Files.exists(cellFile)) {
            return List.of();
        }
        try {
            JsonNode root = MAPPER.readTree(Files.readString(cellFile));
            JsonNode examplesNode = root.get("examples");
            if (examplesNode == null || !examplesNode.isArray()) {
                return List.of();
            }
            List<FewShotExample> result = new ArrayList<>();
            for (JsonNode ex : examplesNode) {
                result.add(new FewShotExample(
                    ex.path("id").asText(""),
                    ex.path("quality_score").asDouble(0),
                    ex.path("matchup").asText(""),
                    ex.path("game_state_summary").asText(""),
                    ex.path("commentary").asText("")
                ));
            }
            return result;
        } catch (IOException e) {
            return List.of();
        }
    }
}

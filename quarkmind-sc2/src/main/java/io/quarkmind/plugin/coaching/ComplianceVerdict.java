package io.quarkmind.plugin.coaching;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public record ComplianceVerdict(String verdict, double confidence, String reasoning) {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ComplianceVerdict NEUTRAL = new ComplianceVerdict("NEUTRAL", 0.0, "");

    public static ComplianceVerdict parse(String text) {
        if (text == null || text.isBlank()) return NEUTRAL;
        try {
            String json = text.strip();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").strip();
            }
            JsonNode node = MAPPER.readTree(json);
            String verdict = node.path("verdict").asText("IGNORED");
            double confidence = node.path("confidence").asDouble(0.5);
            String reasoning = node.path("reasoning").asText("");
            return new ComplianceVerdict(verdict, confidence, reasoning);
        } catch (Exception e) {
            return NEUTRAL;
        }
    }
}

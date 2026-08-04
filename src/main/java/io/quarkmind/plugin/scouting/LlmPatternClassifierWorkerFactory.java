package io.quarkmind.plugin.scouting;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.StrategyArchetype;
import io.quarkmind.plugin.advisory.CompletionCallback;
import org.jboss.logging.Logger;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class LlmPatternClassifierWorkerFactory {

    static final String ADVISOR_ID = "llm-classifier:pattern-fallback";
    static final String CAPABILITY_NAME = "scouting-llm-fallback";

    private static final Logger log = Logger.getLogger(LlmPatternClassifierWorkerFactory.class);

    private LlmPatternClassifierWorkerFactory() {}

    public static Worker createWorker(ChatModel chatModel, CompletionCallback onCompletion) {
        return Worker.builder()
            .name(ADVISOR_ID)
            .capabilityName(CAPABILITY_NAME)
            .function(new WorkerFunction.Sync<>(Map.class, Map.class, (input, scope) ->
                executeClassification(chatModel, input, onCompletion)))
            .description("LLM fallback classifier for ambiguous enemy builds")
            .build();
    }

    @SuppressWarnings("unchecked")
    private static WorkerResult executeClassification(ChatModel chatModel,
            Map<String, Object> input, CompletionCallback onCompletion) {
        long startNanos = System.nanoTime();

        try {
            Map<String, Object> triggerData = (Map<String, Object>) input.get(
                QuarkMindCaseFile.LLM_FALLBACK_TRIGGER);
            if (triggerData == null) {
                return WorkerResult.of(Map.of());
            }

            Race enemyRace = Race.valueOf((String) triggerData.get("enemyRace"));
            Map<String, Double> confidences = (Map<String, Double>) triggerData.get("cumulativeConfidences");
            List<Map<String, Object>> timeline = (List<Map<String, Object>>) triggerData.get("unitTimeline");
            long gameFrame = ((Number) triggerData.get("gameFrame")).longValue();

            if (confidences == null) confidences = Map.of();
            if (timeline == null) timeline = List.of();

            SystemMessage systemMessage = new SystemMessage(buildSystemPrompt(enemyRace, confidences));
            UserMessage userMessage = new UserMessage(buildUserMessage(timeline, gameFrame));

            ChatRequest request = ChatRequest.builder()
                .messages(systemMessage, userMessage)
                .build();

            ChatResponse response = chatModel.chat(request);
            String responseText = response.aiMessage().text();

            String archetypeStr = extractSection(responseText, "ARCHETYPE");
            String confidenceStr = extractSection(responseText, "CONFIDENCE");
            String rationale = extractSection(responseText, "RATIONALE");

            StrategyArchetype archetype;
            try {
                archetype = StrategyArchetype.valueOf(archetypeStr.trim());
            } catch (IllegalArgumentException e) {
                log.warnf("[LLM-FALLBACK] Invalid archetype from LLM: '%s'", archetypeStr);
                return WorkerResult.of(Map.of());
            }

            if (archetype.race() != enemyRace) {
                log.warnf("[LLM-FALLBACK] LLM returned archetype %s for race %s but enemy is %s",
                    archetype, archetype.race(), enemyRace);
                return WorkerResult.of(Map.of());
            }

            double confidence = parseConfidence(confidenceStr);
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;

            Map<String, Double> gameStateSnapshot = new HashMap<>();
            gameStateSnapshot.put("minerals", getDoubleOrZero(input, "game.resources.minerals"));
            gameStateSnapshot.put("supply", getDoubleOrZero(input, "game.resources.supply.used"));
            gameStateSnapshot.put("army", getDoubleOrZero(input, "game.units.army"));

            onCompletion.onCompleted(ADVISOR_ID, CAPABILITY_NAME, gameFrame,
                archetype.name() + ": " + rationale, confidence, latencyMs, gameStateSnapshot);

            return WorkerResult.of(Map.of(
                QuarkMindCaseFile.LLM_FALLBACK_ARCHETYPE, archetype.name(),
                QuarkMindCaseFile.LLM_FALLBACK_CONFIDENCE, confidenceStr.trim(),
                QuarkMindCaseFile.LLM_FALLBACK_RATIONALE, rationale
            ));
        } catch (Exception e) {
            log.warnf(e, "[LLM-FALLBACK] Classification failed: %s", e.getMessage());
            return WorkerResult.of(Map.of());
        }
    }

    static String buildSystemPrompt(Race enemyRace, Map<String, Double> confidences) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a StarCraft II strategy classifier. Given a sequence of enemy unit\n");
        sb.append("observations and their timestamps, classify the enemy's strategy.\n\n");
        sb.append("The enemy race is: ").append(enemyRace).append("\n\n");
        sb.append("Valid archetypes for this race:\n");

        Arrays.stream(StrategyArchetype.values())
            .filter(a -> a.race() == enemyRace)
            .forEach(a -> sb.append("- ").append(a.name()).append("\n"));

        if (!confidences.isEmpty()) {
            sb.append("\nA rule-based classifier attempted classification but could not reach\n");
            sb.append("sufficient confidence. Its current scores:\n");
            confidences.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .forEach(e -> sb.append("- ").append(e.getKey()).append(": ")
                    .append(String.format("%.2f", e.getValue())).append("\n"));
        }

        sb.append("\nRespond with exactly:\n");
        sb.append("ARCHETYPE: <one archetype from the list above>\n");
        sb.append("CONFIDENCE: <0.0 to 1.0>\n");
        sb.append("RATIONALE: <one sentence explaining why>\n");
        return sb.toString();
    }

    static String buildUserMessage(List<Map<String, Object>> timeline, long gameFrame) {
        StringBuilder sb = new StringBuilder();
        sb.append("Enemy unit observation timeline (chronological):\n");
        for (Map<String, Object> entry : timeline) {
            Object typeObj = entry.get("unitType");
            Object timeObj = entry.get("gameTimeMs");
            double timeSec = timeObj instanceof Number n ? n.doubleValue() / 1000.0 : 0.0;
            sb.append(String.format("%.1fs — %s\n", timeSec, typeObj));
        }
        double gameTimeSec = gameFrame / 12.0;
        int minutes = (int) (gameTimeSec / 60);
        int seconds = (int) (gameTimeSec % 60);
        sb.append(String.format("\nCurrent game time: %d:%02d\n", minutes, seconds));
        sb.append("Classify this build.\n");
        return sb.toString();
    }

    static String extractSection(String responseText, String label) {
        if (responseText == null) return "";
        String prefix = label + ":";
        int start = responseText.indexOf(prefix);
        if (start < 0) return "";
        start += prefix.length();
        int end = responseText.indexOf("\n", start);
        if (end < 0) end = responseText.length();
        return responseText.substring(start, end).trim();
    }

    private static double parseConfidence(String confidenceStr) {
        try {
            return Double.parseDouble(confidenceStr.trim());
        } catch (NumberFormatException e) {
            return 0.6;
        }
    }

    private static double getDoubleOrZero(Map<String, Object> input, String key) {
        Object value = input.get(key);
        if (value instanceof Number n) return n.doubleValue();
        return 0.0;
    }
}

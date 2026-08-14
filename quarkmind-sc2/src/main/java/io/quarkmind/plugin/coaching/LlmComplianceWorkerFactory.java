package io.quarkmind.plugin.coaching;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.quarkmind.domain.*;
import org.jboss.logging.Logger;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

public final class LlmComplianceWorkerFactory {

    static final String WORKER_ID = "llm-compliance-evaluator";
    static final String CAPABILITY_NAME = "coaching-llm-compliance";

    private static final Logger log = Logger.getLogger(LlmComplianceWorkerFactory.class);

    private LlmComplianceWorkerFactory() {}

    public static Worker createWorker(ChatModel chatModel, ComplianceWorkerDispatcher.Callback onCompletion) {
        return Worker.builder()
            .name(WORKER_ID)
            .capabilityName(CAPABILITY_NAME)
            .function(new WorkerFunction.Sync<>(Map.class, Map.class, (input, scope) ->
                executeCompliance(chatModel, input, onCompletion)))
            .description("LLM compliance evaluator for non-verifiable coaching advice")
            .build();
    }

    @SuppressWarnings("unchecked")
    private static WorkerResult executeCompliance(ChatModel chatModel, Map<String, Object> input,
                                                   ComplianceWorkerDispatcher.Callback onCompletion) {
        try {
            String correlationId = (String) input.get("correlationId");
            String agentId = (String) input.get("agentId");
            long gameFrame = ((Number) input.get("gameFrame")).longValue();
            String summary = (String) input.get("summary");
            CoachingAdvice advice = (CoachingAdvice) input.get("advice");

            ChatRequest request = ChatRequest.builder()
                .messages(new SystemMessage(buildSystemPrompt()), new UserMessage(summary))
                .build();

            ChatResponse response = chatModel.chat(request);
            ComplianceVerdict verdict = ComplianceVerdict.parse(response.aiMessage().text());

            onCompletion.onCompleted(correlationId, agentId, verdict, advice, gameFrame);

            return WorkerResult.of(Map.of("verdict", verdict.verdict(), "confidence", String.valueOf(verdict.confidence())));
        } catch (Exception e) {
            log.warnf(e, "[LLM-COMPLIANCE] Evaluation failed: %s", e.getMessage());
            return WorkerResult.failed("LLM compliance evaluation failed: " + e.getMessage());
        }
    }

    static String buildSystemPrompt() {
        return """
            You are a StarCraft II coaching compliance evaluator. You will receive:
            1. The coaching ADVICE that was given to the human player
            2. A BEFORE snapshot of the game state when the advice was given
            3. An AFTER snapshot of the game state after the verification window
            4. A CHANGES summary showing what happened

            Assess whether the human player's actions reflect compliance with the advice.

            Respond with JSON in this exact format:
            {
              "verdict": "<COMPLIED | PARTIALLY | IGNORED>",
              "confidence": <0.0 to 1.0>,
              "reasoning": "<one sentence explaining your assessment>"
            }

            Verdict definitions:
            - COMPLIED: Clear evidence the player followed the advice
            - PARTIALLY: Some relevant actions but incomplete or mixed execution
            - IGNORED: No evidence of following the advice, or actions contradict it

            Confidence reflects how clear the signal is:
            - High (0.8-1.0): Obvious compliance or obvious disregard
            - Medium (0.5-0.8): Some ambiguity but a clear lean
            - Low (0.0-0.5): Very unclear whether the player attempted compliance
            """;
    }

    static String summariseForCompliance(GameState baseline, GameState current, String adviceText) {
        StringBuilder sb = new StringBuilder();
        sb.append("ADVICE: \"").append(adviceText).append("\"\n\n");

        appendSnapshot(sb, "BEFORE", baseline);
        sb.append("\n");
        appendSnapshot(sb, "AFTER", current);
        sb.append("\n");
        appendChanges(sb, baseline, current);

        return sb.toString();
    }

    private static void appendSnapshot(StringBuilder sb, String label, GameState state) {
        int minutes = (int) (state.gameFrame() / 12.0 / 60);
        int seconds = (int) (state.gameFrame() / 12.0 % 60);
        sb.append(String.format("%s (frame %d, %d:%02d):\n", label, state.gameFrame(), minutes, seconds));
        sb.append(String.format("Resources: %d minerals, %d vespene, %d/%d supply\n",
            state.minerals(), state.vespene(), state.supplyUsed(), state.supply()));

        Map<UnitType, Long> unitCounts = state.myUnits().stream()
            .collect(Collectors.groupingBy(Unit::type, LinkedHashMap::new, Collectors.counting()));
        if (unitCounts.isEmpty()) {
            sb.append("Army: (none)\n");
        } else {
            sb.append("Army: ");
            sb.append(unitCounts.entrySet().stream()
                .map(e -> e.getValue() + "x " + e.getKey().name())
                .collect(Collectors.joining(", ")));
            sb.append(String.format(" (%d units)\n", state.myUnits().size()));
        }

        Map<BuildingType, Long> buildingCounts = state.myBuildings().stream()
            .collect(Collectors.groupingBy(Building::type, LinkedHashMap::new, Collectors.counting()));
        if (!buildingCounts.isEmpty()) {
            sb.append("Buildings: ");
            sb.append(buildingCounts.entrySet().stream()
                .map(e -> e.getValue() > 1 ? e.getValue() + "x " + e.getKey().name() : e.getKey().name())
                .collect(Collectors.joining(", ")));
            sb.append("\n");
        }
    }

    private static void appendChanges(StringBuilder sb, GameState baseline, GameState current) {
        sb.append("CHANGES:\n");

        Map<UnitType, Long> beforeUnits = baseline.myUnits().stream()
            .collect(Collectors.groupingBy(Unit::type, Collectors.counting()));
        Map<UnitType, Long> afterUnits = current.myUnits().stream()
            .collect(Collectors.groupingBy(Unit::type, Collectors.counting()));
        boolean anyUnitChange = appendDelta(sb, beforeUnits, afterUnits);

        Map<BuildingType, Long> beforeBuildings = baseline.myBuildings().stream()
            .collect(Collectors.groupingBy(Building::type, Collectors.counting()));
        Map<BuildingType, Long> afterBuildings = current.myBuildings().stream()
            .collect(Collectors.groupingBy(Building::type, Collectors.counting()));
        boolean anyBuildingChange = appendDelta(sb, beforeBuildings, afterBuildings);

        if (!anyUnitChange && !anyBuildingChange) {
            sb.append("No unit or building changes\n");
        }

        sb.append(String.format("Minerals: %+d, Vespene: %+d, Supply: %+d/%+d\n",
            current.minerals() - baseline.minerals(),
            current.vespene() - baseline.vespene(),
            current.supplyUsed() - baseline.supplyUsed(),
            current.supply() - baseline.supply()));
    }

    private static <T extends Enum<T>> boolean appendDelta(StringBuilder sb,
            Map<T, Long> before, Map<T, Long> after) {
        boolean any = false;
        var allKeys = new LinkedHashSet<>(before.keySet());
        allKeys.addAll(after.keySet());
        for (T key : allKeys) {
            long diff = after.getOrDefault(key, 0L) - before.getOrDefault(key, 0L);
            if (diff != 0) {
                sb.append(String.format("%+dx %s\n", diff, key.name()));
                any = true;
            }
        }
        return any;
    }
}

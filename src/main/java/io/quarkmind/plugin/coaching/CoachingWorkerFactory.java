package io.quarkmind.plugin.coaching;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentDisposition;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.UnitType;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class CoachingWorkerFactory {

    private static final Logger log = Logger.getLogger(CoachingWorkerFactory.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CoachingWorkerFactory() {}

    public static List<Worker> createWorkers(List<AgentDescriptor> descriptors, ChatModel chatModel,
                                              CoachingCompletionCallback onCompletion) {
        List<AgentDescriptor> coachDescriptors = descriptors.stream()
            .filter(d -> d.capabilities().stream().anyMatch(c -> c.name().equals("coaching")))
            .toList();

        List<Worker> workers = new ArrayList<>(coachDescriptors.size());
        for (AgentDescriptor descriptor : coachDescriptors) {
            workers.add(createWorker(descriptor, chatModel, onCompletion));
        }
        log.infof("[COACHING] Created %d coaching workers", workers.size());
        return workers;
    }

    private static Worker createWorker(AgentDescriptor descriptor, ChatModel chatModel,
                                        CoachingCompletionCallback onCompletion) {
        String capabilityName = descriptor.capabilities().get(0).name();
        return Worker.builder()
            .name(descriptor.agentId())
            .capabilityName(capabilityName)
            .function(new WorkerFunction.Sync<>(Map.class, input ->
                executeCoaching(descriptor, chatModel, input, onCompletion)))
            .description("Coaching worker: " + descriptor.name() + " (" + descriptor.agentId() + ")")
            .build();
    }

    private static WorkerResult executeCoaching(AgentDescriptor descriptor, ChatModel chatModel,
                                                 Map<String, Object> input,
                                                 CoachingCompletionCallback onCompletion) {
        long startNanos = System.nanoTime();
        String capabilityName = descriptor.capabilities().get(0).name();

        try {
            boolean isCrisis = isCrisisTrigger(input);
            SystemMessage systemMessage = new SystemMessage(buildSystemPrompt(descriptor, isCrisis));
            UserMessage userMessage = new UserMessage(buildUserMessage(input));

            ChatRequest request = ChatRequest.builder()
                .messages(systemMessage, userMessage)
                .build();

            ChatResponse response = chatModel.chat(request);
            String responseText = response.aiMessage().text();

            CoachingAdvice advice = parseAdvice(responseText);
            if (advice == null) {
                log.warnf("[COACHING] %s returned unparseable response", descriptor.agentId());
                return WorkerResult.failed("Coaching " + descriptor.agentId() + " returned unparseable response");
            }

            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            long gameFrame = getGameFrame(input);
            CoachingUrgencyTier tier = isCrisis ? CoachingUrgencyTier.CRISIS : resolveUrgencyTier(input);

            onCompletion.onCompleted(descriptor.agentId(), capabilityName, gameFrame,
                advice, tier, latencyMs);

            return WorkerResult.of(Map.of("agent.coaching.advice", advice.advice()));
        } catch (Exception e) {
            log.warnf(e, "[COACHING] %s failed: %s", descriptor.agentId(), e.getMessage());
            return WorkerResult.failed("Coaching " + descriptor.agentId() + " failed: " + e.getMessage());
        }
    }

    static String buildSystemPrompt(AgentDescriptor descriptor, boolean crisisOverride) {
        AgentDisposition disposition = descriptor.disposition();
        boolean isDirective = crisisOverride
            || (disposition != null && "bold".equals(disposition.riskAppetite()));

        StringBuilder sb = new StringBuilder();
        sb.append("You are a StarCraft II coach providing real-time advice to a human player.\n\n");

        if (isDirective) {
            sb.append("Style: Give direct, actionable instructions. Use imperative voice.\n");
            sb.append("Example: \"Build 3 Stalkers immediately from your Gateways.\"\n\n");
        } else {
            sb.append("Style: Ask guiding questions to help the player discover the right action.\n");
            sb.append("Example: \"What could you build to counter those Roaches?\"\n\n");
        }

        sb.append("Behavioural disposition:\n");
        if (disposition != null) {
            appendTrait(sb, "Risk appetite", disposition.riskAppetite());
            appendTrait(sb, "Rule following", disposition.ruleFollowing());
            appendTrait(sb, "Social orientation", disposition.socialOrient());
        }

        sb.append("\nRespond with JSON in this exact format:\n");
        sb.append("{\n");
        sb.append("  \"advice\": \"<your coaching advice as a single sentence>\",\n");
        sb.append("  \"domain\": \"<CoachingDomain: BUILD | MILITARY | EXPAND | TECH>\",\n");
        sb.append("  \"verificationUnitType\": \"<UnitType if verifiable, e.g. STALKER, or null>\",\n");
        sb.append("  \"verificationBuildingType\": \"<BuildingType if verifiable, e.g. NEXUS, or null>\",\n");
        sb.append("  \"verificationCountDelta\": <integer count to verify, or null>,\n");
        sb.append("  \"verificationWindowFrames\": <frames to wait before checking, default 450>\n");
        sb.append("}\n\n");
        sb.append("Only set verification fields when the advice is concretely measurable.\n");
        sb.append("For general advice like \"improve macro\", omit verification fields.\n");

        return sb.toString();
    }

    static String buildUserMessage(Map<String, Object> input) {
        StringBuilder sb = new StringBuilder();
        Object trigger = input.get(QuarkMindCaseFile.COACHING_TRIGGER);
        if (trigger instanceof Map<?, ?> triggerMap) {
            Object momentTypes = triggerMap.get("momentTypes");
            if (momentTypes != null) {
                sb.append("TRIGGER: ").append(momentTypes).append("\n");
            }
            Object urgency = triggerMap.get("urgencyTier");
            if (urgency != null) {
                sb.append("URGENCY: ").append(urgency).append("\n");
            }

            Object pattern = triggerMap.get("patternAssessment");
            if (pattern instanceof Map<?, ?> patternMap) {
                Object archetype = patternMap.get("archetype");
                Object confidence = patternMap.get("confidence");
                if (archetype != null) {
                    sb.append("ENEMY PATTERN: ").append(archetype);
                    if (confidence != null) sb.append(" (confidence: ").append(confidence).append(")");
                    sb.append("\n");
                }
            }
        }

        sb.append("\nGame state:\n");
        appendField(sb, "Minerals", input.get(QuarkMindCaseFile.MINERALS));
        appendField(sb, "Vespene", input.get(QuarkMindCaseFile.VESPENE));
        appendField(sb, "Supply", input.get(QuarkMindCaseFile.SUPPLY_USED));
        appendField(sb, "Supply cap", input.get(QuarkMindCaseFile.SUPPLY_CAP));
        appendField(sb, "Army size", input.get(QuarkMindCaseFile.ARMY));

        sb.append("\nProvide your coaching advice.");
        return sb.toString();
    }

    static CoachingAdvice parseAdvice(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            String json = text.strip();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").strip();
            }
            JsonNode node = MAPPER.readTree(json);
            String advice = node.path("advice").asText(null);
            if (advice == null) return null;

            String domainStr = node.path("domain").asText("BUILD");
            CoachingDomain domain = CoachingDomain.valueOf(domainStr);

            UnitType unitType = null;
            if (node.has("verificationUnitType") && !node.get("verificationUnitType").isNull()) {
                try { unitType = UnitType.valueOf(node.get("verificationUnitType").asText()); }
                catch (IllegalArgumentException ignored) {}
            }

            BuildingType buildingType = null;
            if (node.has("verificationBuildingType") && !node.get("verificationBuildingType").isNull()) {
                try { buildingType = BuildingType.valueOf(node.get("verificationBuildingType").asText()); }
                catch (IllegalArgumentException ignored) {}
            }

            Integer countDelta = null;
            if (node.has("verificationCountDelta") && !node.get("verificationCountDelta").isNull()) {
                countDelta = node.get("verificationCountDelta").asInt();
            }

            int windowFrames = node.path("verificationWindowFrames").asInt(450);

            return new CoachingAdvice(advice, domain, unitType, buildingType, countDelta, windowFrames);
        } catch (Exception e) {
            log.debugf("Failed to parse coaching advice: %s", e.getMessage());
            return null;
        }
    }

    private static boolean isCrisisTrigger(Map<String, Object> input) {
        Object trigger = input.get(QuarkMindCaseFile.COACHING_TRIGGER);
        if (trigger instanceof Map<?, ?> triggerMap) {
            return "CRISIS".equals(triggerMap.get("urgencyTier"));
        }
        return false;
    }

    private static CoachingUrgencyTier resolveUrgencyTier(Map<String, Object> input) {
        Object trigger = input.get(QuarkMindCaseFile.COACHING_TRIGGER);
        if (trigger instanceof Map<?, ?> triggerMap) {
            String tierStr = (String) triggerMap.get("urgencyTier");
            if (tierStr != null) {
                try { return CoachingUrgencyTier.valueOf(tierStr); }
                catch (IllegalArgumentException ignored) {}
            }
        }
        return CoachingUrgencyTier.ECONOMIC;
    }

    private static long getGameFrame(Map<String, Object> input) {
        Object trigger = input.get(QuarkMindCaseFile.COACHING_TRIGGER);
        if (trigger instanceof Map<?, ?> triggerMap) {
            Object frame = triggerMap.get("gameFrame");
            if (frame instanceof Number num) return num.longValue();
        }
        return 0L;
    }

    private static void appendTrait(StringBuilder sb, String label, String value) {
        if (value != null) sb.append("- ").append(label).append(": ").append(value).append("\n");
    }

    private static void appendField(StringBuilder sb, String label, Object value) {
        if (value != null) sb.append("- ").append(label).append(": ").append(value).append("\n");
    }
}

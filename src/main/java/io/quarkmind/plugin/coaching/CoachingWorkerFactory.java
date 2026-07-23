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
            .function(new WorkerFunction.Sync<>(Map.class, Map.class, (input, scope) ->
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
                advice, tier, latencyMs, reconstructTriggerState(input));

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
        sb.append("  \"verificationType\": \"<see types below, or omit for non-verifiable advice>\",\n");
        sb.append("  \"verificationParams\": { <type-specific params> },\n");
        sb.append("  \"verificationWindowFrames\": <frames to wait before checking, default 450>\n");
        sb.append("}\n\n");
        sb.append("Verification types:\n");
        sb.append("- COUNT_DELTA: params {unitType, buildingType, expectedDelta}\n");
        sb.append("- ARMY_CENTROID_RETREAT: params {referenceLocation, minDistance} - army moved AWAY from referenceLocation\n");
        sb.append("- ARMY_CENTROID_ADVANCE: params {referenceLocation, minDistance} - army moved TOWARD referenceLocation\n");
        sb.append("- EXPANSION_PLACEMENT: params {expansionOrdinal} - new base near expansion N (0=main, 1=natural, 2=third)\n");
        sb.append("- UNITS_NEAR_LOCATION: params {location, unitType, radius, minCount}\n\n");
        sb.append("Location tokens: PLAYER_BASE, ENEMY_BASE, MAP_CENTER, NATURAL, THIRD, EXPANSION_N, NEAREST_RAMP, WATCHTOWER\n\n");
        sb.append("Only set verificationType when the advice is concretely verifiable.\n");
        sb.append("For general advice like \"improve macro\", omit verificationType entirely.\n");

        return sb.toString();}

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
        if (text == null || text.isBlank()) {return null;}
        try {
            String json = text.strip();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").strip();
            }
            JsonNode node   = MAPPER.readTree(json);
            String   advice = node.path("advice").asText(null);
            if (advice == null) {return null;}

            String         domainStr    = node.path("domain").asText("BUILD");
            CoachingDomain domain       = CoachingDomain.valueOf(domainStr);
            int            windowFrames = node.path("verificationWindowFrames").asInt(450);

            String verificationType = node.path("verificationType").asText(null);
            if (verificationType != null) {
                JsonNode              params = node.path("verificationParams");
                VerificationPredicate pred   = parseVerificationType(verificationType, params);
                return new CoachingAdvice(advice, domain, pred, windowFrames);
            }

            UnitType unitType = null;
            if (node.has("verificationUnitType") && !node.get("verificationUnitType").isNull()) {
                try {
                    unitType = UnitType.valueOf(node.get("verificationUnitType").asText());
                } catch (IllegalArgumentException ignored) {}
            }
            BuildingType buildingType = null;
            if (node.has("verificationBuildingType") && !node.get("verificationBuildingType").isNull()) {
                try {
                    buildingType = BuildingType.valueOf(node.get("verificationBuildingType").asText());
                } catch (IllegalArgumentException ignored) {}
            }
            Integer countDelta = null;
            if (node.has("verificationCountDelta") && !node.get("verificationCountDelta").isNull()) {
                countDelta = node.get("verificationCountDelta").asInt();
            }

            VerificationPredicate verification = null;
            if (countDelta != null && (unitType != null || buildingType != null)) {
                verification = new CountDelta(unitType, buildingType, countDelta, 0);
            }
            return new CoachingAdvice(advice, domain, verification, windowFrames);
        } catch (Exception e) {
            log.debugf("Failed to parse coaching advice: %s", e.getMessage());
            return null;
        }}

    private static VerificationPredicate parseVerificationType(String type, JsonNode params) {
        return switch (type) {
            case "COUNT_DELTA" -> {
                UnitType     ut    = parseUnitType(params.path("unitType").asText(null));
                BuildingType bt    = parseBuildingType(params.path("buildingType").asText(null));
                int          delta = params.path("expectedDelta").asInt(1);
                yield new CountDelta(ut, bt, delta, 0);
            }
            case "ARMY_CENTROID_RETREAT" -> new ArmyCentroidMovement(
                    MovementDirection.RETREAT,
                    parseLocationToken(params.path("referenceLocation").asText("ENEMY_BASE")),
                    params.path("minDistance").asDouble(8.0), null);
            case "ARMY_CENTROID_ADVANCE" -> new ArmyCentroidMovement(
                    MovementDirection.ADVANCE,
                    parseLocationToken(params.path("referenceLocation").asText("ENEMY_BASE")),
                    params.path("minDistance").asDouble(8.0), null);
            case "EXPANSION_PLACEMENT" -> new ExpansionPlacement(
                    new LocationReference.ExpansionOrdinal(params.path("expansionOrdinal").asInt(1)),
                    params.path("proximityRadius").asDouble(5.0), java.util.Set.of());
            case "UNITS_NEAR_LOCATION" -> new UnitsNearLocation(
                    parseUnitType(params.path("unitType").asText(null)),
                    parseLocationToken(params.path("location").asText("MAP_CENTER")),
                    params.path("radius").asDouble(10.0),
                    params.path("minCount").asInt(1));
            default -> null;
        };
    }

    private static LocationReference parseLocationToken(String token) {
        if (token == null) {return new LocationReference.MapCenter();}
        return switch (token) {
            case "PLAYER_BASE" -> new LocationReference.PlayerBase();
            case "ENEMY_BASE" -> new LocationReference.EnemyBase();
            case "MAP_CENTER" -> new LocationReference.MapCenter();
            case "NATURAL" -> new LocationReference.ExpansionOrdinal(1);
            case "THIRD" -> new LocationReference.ExpansionOrdinal(2);
            case "NEAREST_RAMP" -> new LocationReference.NearestRamp(new LocationReference.PlayerBase());
            case "WATCHTOWER" -> new LocationReference.Watchtower(0);
            default -> {
                if (token.startsWith("EXPANSION_")) {
                    try {
                        int ordinal = Integer.parseInt(token.substring("EXPANSION_".length()));
                        yield new LocationReference.ExpansionOrdinal(ordinal);
                    } catch (NumberFormatException ignored) {}
                }
                yield new LocationReference.MapCenter();
            }
        };
    }

    private static UnitType parseUnitType(String text) {
        if (text == null) {return null;}
        try {return UnitType.valueOf(text);} catch (IllegalArgumentException e) {return null;}
    }

    private static BuildingType parseBuildingType(String text) {
        if (text == null) {return null;}
        try {return BuildingType.valueOf(text);} catch (IllegalArgumentException e) {return null;}
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

    @SuppressWarnings("unchecked")
    private static io.quarkmind.domain.GameState reconstructTriggerState(Map<String, Object> input) {
        try {
            var army = (java.util.List<io.quarkmind.domain.Unit>) input.getOrDefault(
                    io.quarkmind.agent.QuarkMindCaseFile.ARMY, java.util.List.of());
            var workers = (java.util.List<io.quarkmind.domain.Unit>) input.getOrDefault(
                    io.quarkmind.agent.QuarkMindCaseFile.WORKERS, java.util.List.of());
            var buildings = (java.util.List<io.quarkmind.domain.Building>) input.getOrDefault(
                    io.quarkmind.agent.QuarkMindCaseFile.MY_BUILDINGS, java.util.List.of());
            var allUnits = new java.util.ArrayList<>(army);
            allUnits.addAll(workers);
            int  minerals   = input.get(io.quarkmind.agent.QuarkMindCaseFile.MINERALS) instanceof Number n ? n.intValue() : 0;
            int  vespene    = input.get(io.quarkmind.agent.QuarkMindCaseFile.VESPENE) instanceof Number n ? n.intValue() : 0;
            int  supplyCap  = input.get(io.quarkmind.agent.QuarkMindCaseFile.SUPPLY_CAP) instanceof Number n ? n.intValue() : 0;
            int  supplyUsed = input.get(io.quarkmind.agent.QuarkMindCaseFile.SUPPLY_USED) instanceof Number n ? n.intValue() : 0;
            long frame      = getGameFrame(input);
            return new io.quarkmind.domain.GameState(minerals, vespene, supplyCap, supplyUsed,
                                                     allUnits, buildings, java.util.List.of(), java.util.List.of(), java.util.List.of(),
                                                     java.util.List.of(), java.util.List.of(), frame, null);
        } catch (Exception e) {
            log.debugf("Failed to reconstruct trigger state: %s", e.getMessage());
            return null;
        }
    }
}

package io.quarkmind.plugin.advisory;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentDisposition;
import io.casehub.eidos.api.DispositionAxis;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Factory that creates advisory {@link Worker Workers} from eidos {@link AgentDescriptor
 * AgentDescriptors} and a LangChain4j {@link ChatModel}.
 *
 * <p>Each descriptor maps to one Worker. The Worker's sync function:
 * <ol>
 *   <li>Reads game state from the input map (working panel keys)</li>
 *   <li>Builds a system prompt from the descriptor's disposition traits and role</li>
 *   <li>Builds a user message with the trigger event and game state summary</li>
 *   <li>Calls {@code chatModel.chat(ChatRequest)}</li>
 *   <li>Returns a {@link WorkerResult} with role-prefixed output keys</li>
 * </ol>
 *
 * <p>This is a plain Java factory with no CDI — the ChatModel is provided by the caller
 * (injected via CDI in {@code QuarkMindCaseHub}).
 *
 * <p>Refs #180
 */
public final class AdvisoryWorkerFactory {

    private static final Logger log = Logger.getLogger(AdvisoryWorkerFactory.class);

    private AdvisoryWorkerFactory() {} // static factory only

    /**
     * Creates one {@link Worker} per {@link AgentDescriptor}. Each Worker:
     * <ul>
     *   <li>name = descriptor's agentId (e.g. "claude:crisis-aggressive@v1")</li>
     *   <li>capabilityName = first capability name from the descriptor (e.g. "advisory-crisis")</li>
     *   <li>function = sync function that calls the ChatModel and returns role-prefixed keys</li>
     * </ul>
     *
     * @param descriptors  the advisor descriptors (from {@code QuarkMindAdvisorRegistrar})
     * @param chatModel    the LangChain4j ChatModel for LLM calls
     * @param onCompletion callback fired after successful advisory completion
     * @return list of Workers, one per descriptor
     */
    public static List<Worker> createWorkers(List<AgentDescriptor> descriptors, ChatModel chatModel,
                                             CompletionCallback onCompletion) {
        List<Worker> workers = new ArrayList<>(descriptors.size());
        for (AgentDescriptor descriptor : descriptors) {
            workers.add(createWorker(descriptor, chatModel, onCompletion));
        }
        log.infof("[ADVISORY] Created %d advisory workers from descriptors", workers.size());
        return workers;
    }

    private static Worker createWorker(AgentDescriptor descriptor, ChatModel chatModel,
                                       CompletionCallback onCompletion) {
        String capabilityName = descriptor.capabilities().get(0).name();
        String role           = extractRole(capabilityName);

        return Worker.builder()
                     .name(descriptor.agentId())
                     .capabilityName(capabilityName)
                     .function(new WorkerFunction.Sync<>(Map.class, Map.class, (input, scope) ->
                                                                            executeAdvisory(descriptor, chatModel, role, input, onCompletion)))
                     .description("LLM advisory worker: " + descriptor.name()
                                  + " (" + descriptor.agentId() + ")")
                     .build();
    }

    /**
     * Extracts the role from a capability name. E.g. "advisory-crisis" -> "crisis".
     */
    static String extractRole(String capabilityName) {
        if (capabilityName.startsWith("advisory-")) {
            return capabilityName.substring("advisory-".length());
        }
        return capabilityName;
    }

    private static WorkerResult executeAdvisory(
            AgentDescriptor descriptor, ChatModel chatModel, String role,
            Map<String, Object> input, CompletionCallback onCompletion) {
        long   startNanos     = System.nanoTime();
        String capabilityName = descriptor.capabilities().get(0).name();

        try {
            SystemMessage systemMessage = new SystemMessage(buildSystemPrompt(descriptor, role));
            UserMessage   userMessage   = new UserMessage(buildUserMessage(role, input));

            ChatRequest request = ChatRequest.builder()
                                             .messages(systemMessage, userMessage)
                                             .build();

            ChatResponse response     = chatModel.chat(request);
            String       responseText = response.aiMessage().text();

            long                latencyMs         = (System.nanoTime() - startNanos) / 1_000_000;
            String              confidenceStr     = extractSection(responseText, "CONFIDENCE");
            double              confidence        = parseConfidence(confidenceStr);
            long                gameFrame         = getGameFrame(input);
            Map<String, Double> gameStateSnapshot = captureGameStateSnapshot(input);

            String keyPrefix = "agent.advisory." + role + ".";
            WorkerResult result = WorkerResult.of(Map.of(
                    keyPrefix + "recommendation", responseText != null ? responseText : "",
                    keyPrefix + "reasoning", extractSection(responseText, "REASONING"),
                    keyPrefix + "confidence", confidenceStr,
                    keyPrefix + "agent_id", descriptor.agentId(),
                    keyPrefix + "timestamp", gameFrame
                                                        ));

            // Fire completion callback
            onCompletion.onCompleted(descriptor.agentId(), capabilityName, gameFrame,
                                     responseText != null ? responseText : "",
                                     confidence, latencyMs, gameStateSnapshot);

            return result;
        } catch (Exception e) {
            log.warnf(e, "[ADVISORY] %s failed: %s", descriptor.agentId(), e.getMessage());
            return WorkerResult.failed(
                    "Advisory " + descriptor.agentId() + " failed: " + e.getMessage());
        }
    }

    private static Map<String, Double> captureGameStateSnapshot(Map<String, Object> input) {
        Map<String, Double> snapshot = new java.util.HashMap<>();
        snapshot.put("minerals", getDoubleOrZero(input, "game.resources.minerals"));
        snapshot.put("supply", getDoubleOrZero(input, "game.resources.supply.used"));
        snapshot.put("army", getDoubleOrZero(input, "game.units.army"));
        return snapshot;
    }

    private static double getDoubleOrZero(Map<String, Object> input, String key) {
        Object value = input.get(key);
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return 0.0;
    }

    private static double parseConfidence(String confidenceStr) {
        try {
            return Double.parseDouble(confidenceStr);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static long getGameFrame(Map<String, Object> input) {
        Object frame = input.get("game.frame");
        if (frame instanceof Number num) {
            return num.longValue();
        }
        return 0L;
    }

    /**
     * Builds a system prompt incorporating the descriptor's disposition traits and role.
     *
     * <p>The prompt instructs the LLM to respond as an advisor with the specified
     * behavioural disposition, using the role name for context.
     */
    static String buildSystemPrompt(AgentDescriptor descriptor, String role) {
        AgentDisposition disposition = descriptor.disposition();
        StringBuilder    sb          = new StringBuilder();
        sb.append("You are a StarCraft II ").append(role).append(" advisor.\n");
        sb.append("Your name is: ").append(descriptor.name()).append("\n\n");
        sb.append("Behavioural disposition:\n");
        if (disposition != null) {
            appendTrait(sb, "Risk appetite", disposition.primaryTerm(DispositionAxis.RISK_APPETITE));
            appendTrait(sb, "Rule following", disposition.primaryTerm(DispositionAxis.RULE_FOLLOWING));
            appendTrait(sb, "Social orientation", disposition.primaryTerm(DispositionAxis.SOCIAL_ORIENTATION));
            appendTrait(sb, "Autonomy", disposition.primaryTerm(DispositionAxis.AUTONOMY));
            appendTrait(sb, "Conflict mode", disposition.primaryTerm(DispositionAxis.CONFLICT_MODE));
        }
        sb.append("\nIntel types you may receive:\n");
        sb.append("- PATTERN_ASSESSMENT: enemy strategy classification with archetype name ");
        sb.append("and confidence score (0.0–1.0). ");
        sb.append("Factor the classified intent into your recommendation — ");
        sb.append("a high-confidence rush classification should increase urgency.\n\n");
        sb.append("Respond with:\n");
        sb.append("RECOMMENDATION: <your recommendation>\n");
        sb.append("REASONING: <your reasoning>\n");
        sb.append("CONFIDENCE: <0.0 to 1.0>\n");
        return sb.toString();
    }

    private static void appendTrait(StringBuilder sb, String label, String value) {
        if (value != null) {
            sb.append("- ").append(label).append(": ").append(value).append("\n");
        }
    }

    /**
     * Builds the user message with trigger event and game state summary.
     */
    static String buildUserMessage(String role, Map<String, Object> input) {
        StringBuilder sb         = new StringBuilder();
        String        triggerKey = "game.advisory.trigger." + role;
        Object        trigger    = input.get(triggerKey);
        if (trigger != null) {
            if (trigger instanceof Map<?, ?> triggerMap) {
                Object event = triggerMap.get("event");
                if (event != null) {
                    sb.append("TRIGGER EVENT: ").append(event).append("\n");
                } else {
                    sb.append("TRIGGER EVENT: ").append(trigger).append("\n");
                }
                Object pattern = triggerMap.get("patternAssessment");
                if (pattern instanceof Map<?, ?> patternMap) {
                    Object archetype  = patternMap.get("archetype");
                    Object confidence = patternMap.get("confidence");
                    if (archetype != null) {
                        sb.append("Enemy pattern classification: ").append(archetype);
                        if (confidence != null) {
                            sb.append(" (confidence: ").append(confidence).append(")");
                        }
                        sb.append("\n");
                    }
                }
            } else {
                sb.append("TRIGGER EVENT: ").append(trigger).append("\n");
            }
            sb.append("\n");
        }

        sb.append("Current game state:\n");
        Object frame = input.get("game.frame");
        if (frame != null) {
            sb.append("- Game frame: ").append(frame).append("\n");
        }
        Object minerals = input.get("game.resources.minerals");
        if (minerals != null) {
            sb.append("- Minerals: ").append(minerals).append("\n");
        }
        Object vespene = input.get("game.resources.vespene");
        if (vespene != null) {
            sb.append("- Vespene: ").append(vespene).append("\n");
        }
        Object supplyUsed = input.get("game.resources.supply.used");
        if (supplyUsed != null) {
            sb.append("- Supply used: ").append(supplyUsed).append("\n");
        }
        Object supplyCap = input.get("game.resources.supply.cap");
        if (supplyCap != null) {
            sb.append("- Supply cap: ").append(supplyCap).append("\n");
        }

        sb.append("\nProvide your advisory recommendation for this ").append(role).append(" situation.");
        return sb.toString();
    }

    /**
     * Extracts a labelled section from the LLM response text.
     * E.g., for label "REASONING" in "REASONING: some text\nCONFIDENCE: 0.8",
     * returns "some text".
     *
     * @return the extracted text, or empty string if not found
     */
    static String extractSection(String responseText, String label) {
        if (responseText == null) {
            return "";
        }
        String prefix = label + ":";
        int    start  = responseText.indexOf(prefix);
        if (start < 0) {
            return "";
        }
        start += prefix.length();
        int end = responseText.indexOf("\n", start);
        if (end < 0) {
            end = responseText.length();
        }
        return responseText.substring(start, end).trim();
    }
}

package io.quarkmind.plugin.commentary;

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
import io.quarkmind.agent.QuarkMindCaseFile;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Factory that creates commentary {@link Worker Workers} from eidos {@link AgentDescriptor
 * AgentDescriptors} and a LangChain4j {@link ChatModel}.
 *
 * <p>Similar to {@link io.quarkmind.plugin.advisory.AdvisoryWorkerFactory} but simpler:
 * <ul>
 *   <li>Response is plain text (no RECOMMENDATION/REASONING/CONFIDENCE parsing)</li>
 *   <li>Two Worker types: reactive (immediate) and narrative (contextual)</li>
 *   <li>Output keys: {@code agent.commentary.reactive.text} or {@code agent.commentary.narrative.text}</li>
 * </ul>
 *
 * <p>This is a plain Java factory with no CDI — the ChatModel is provided by the caller
 * (injected via CDI in {@code QuarkMindCaseHub}).
 *
 * <p>Refs #181 (Task 5)
 */
public final class CommentaryWorkerFactory {

    private static final Logger log = Logger.getLogger(CommentaryWorkerFactory.class);

    private CommentaryWorkerFactory() {} // static factory only

    /**
     * Creates reactive commentary {@link Worker Workers} from descriptors.
     * Filters descriptors by {@code commentary-reactive} capability.
     *
     * @param descriptors  agent descriptors (from {@code QuarkMindAgentRegistrar})
     * @param chatModel    LangChain4j ChatModel for LLM calls
     * @param onCompletion callback fired after successful commentary completion
     * @return list of Workers, one per reactive descriptor
     */
    public static List<Worker> createReactiveWorkers(List<AgentDescriptor> descriptors, ChatModel chatModel,
                                                     CommentaryCompletionCallback onCompletion) {
        List<AgentDescriptor> reactiveDescriptors = descriptors.stream()
                                                               .filter(d -> d.capabilities().stream().anyMatch(c -> c.name().equals("commentary-reactive")))
                                                               .toList();

        List<Worker> workers = new ArrayList<>(reactiveDescriptors.size());
        for (AgentDescriptor descriptor : reactiveDescriptors) {
            workers.add(createReactiveWorker(descriptor, chatModel, onCompletion));
        }
        log.infof("[COMMENTARY] Created %d reactive commentary workers", workers.size());
        return workers;
    }

    /**
     * Creates narrative commentary {@link Worker Workers} from descriptors.
     * Filters descriptors by {@code commentary-narrative} capability.
     *
     * @param descriptors  agent descriptors (from {@code QuarkMindAgentRegistrar})
     * @param chatModel    LangChain4j ChatModel for LLM calls
     * @param onCompletion callback fired after successful commentary completion
     * @return list of Workers, one per narrative descriptor
     */
    public static List<Worker> createNarrativeWorkers(List<AgentDescriptor> descriptors, ChatModel chatModel,
                                                      CommentaryCompletionCallback onCompletion) {
        List<AgentDescriptor> narrativeDescriptors = descriptors.stream()
                                                                .filter(d -> d.capabilities().stream().anyMatch(c -> c.name().equals("commentary-narrative")))
                                                                .toList();

        List<Worker> workers = new ArrayList<>(narrativeDescriptors.size());
        for (AgentDescriptor descriptor : narrativeDescriptors) {
            workers.add(createNarrativeWorker(descriptor, chatModel, onCompletion));
        }
        log.infof("[COMMENTARY] Created %d narrative commentary workers", workers.size());
        return workers;
    }

    private static Worker createReactiveWorker(AgentDescriptor descriptor, ChatModel chatModel,
                                               CommentaryCompletionCallback onCompletion) {
        String capabilityName = descriptor.capabilities().get(0).name();

        return Worker.builder()
                     .name(descriptor.agentId())
                     .capabilityName(capabilityName)
                     .function(new WorkerFunction.Sync<>(Map.class, Map.class, (input, scope) ->
                                                                            executeReactiveCommentary(descriptor, chatModel, input, onCompletion)))
                     .description("Reactive commentary worker: " + descriptor.name()
                                  + " (" + descriptor.agentId() + ")")
                     .build();
    }

    private static Worker createNarrativeWorker(AgentDescriptor descriptor, ChatModel chatModel,
                                                CommentaryCompletionCallback onCompletion) {
        String capabilityName = descriptor.capabilities().get(0).name();

        return Worker.builder()
                     .name(descriptor.agentId())
                     .capabilityName(capabilityName)
                     .function(new WorkerFunction.Sync<>(Map.class, Map.class, (input, scope) ->
                                                                            executeNarrativeCommentary(descriptor, chatModel, input, onCompletion)))
                     .description("Narrative commentary worker: " + descriptor.name()
                                  + " (" + descriptor.agentId() + ")")
                     .build();
    }

    private static WorkerResult executeReactiveCommentary(
            AgentDescriptor descriptor, ChatModel chatModel,
            Map<String, Object> input, CommentaryCompletionCallback onCompletion) {
        long   startNanos     = System.nanoTime();
        String capabilityName = descriptor.capabilities().get(0).name();

        try {
            SystemMessage systemMessage = new SystemMessage(buildReactiveSystemPrompt(descriptor));
            UserMessage   userMessage   = new UserMessage(buildReactiveUserMessage(input));

            ChatRequest request = ChatRequest.builder()
                                             .messages(systemMessage, userMessage)
                                             .build();

            ChatResponse response     = chatModel.chat(request);
            String       responseText = response.aiMessage().text();

            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            long gameFrame = getGameFrame(input, QuarkMindCaseFile.COMMENTARY_TRIGGER);

            // Fire completion callback
            onCompletion.onCompleted(descriptor.agentId(), capabilityName, gameFrame,
                                     responseText != null ? responseText : "",
                                     CommentaryType.REACTIVE, latencyMs);

            return WorkerResult.of(Map.of(
                    "agent.commentary.reactive.text", responseText != null ? responseText : ""
                                         ));
        } catch (Exception e) {
            log.warnf(e, "[COMMENTARY] Reactive %s failed: %s", descriptor.agentId(), e.getMessage());
            return WorkerResult.failed(
                    "Reactive commentary " + descriptor.agentId() + " failed: " + e.getMessage());
        }
    }

    private static WorkerResult executeNarrativeCommentary(
            AgentDescriptor descriptor, ChatModel chatModel,
            Map<String, Object> input, CommentaryCompletionCallback onCompletion) {
        long   startNanos     = System.nanoTime();
        String capabilityName = descriptor.capabilities().get(0).name();

        try {
            SystemMessage systemMessage = new SystemMessage(buildNarrativeSystemPrompt(descriptor));
            UserMessage   userMessage   = new UserMessage(buildNarrativeUserMessage(input));

            ChatRequest request = ChatRequest.builder()
                                             .messages(systemMessage, userMessage)
                                             .build();

            ChatResponse response     = chatModel.chat(request);
            String       responseText = response.aiMessage().text();

            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            long gameFrame = getGameFrame(input, QuarkMindCaseFile.COMMENTARY_NARRATIVE_TRIGGER);

            // Fire completion callback
            onCompletion.onCompleted(descriptor.agentId(), capabilityName, gameFrame,
                                     responseText != null ? responseText : "",
                                     CommentaryType.NARRATIVE, latencyMs);

            return WorkerResult.of(Map.of(
                    "agent.commentary.narrative.text", responseText != null ? responseText : ""
                                         ));
        } catch (Exception e) {
            log.warnf(e, "[COMMENTARY] Narrative %s failed: %s", descriptor.agentId(), e.getMessage());
            return WorkerResult.failed(
                    "Narrative commentary " + descriptor.agentId() + " failed: " + e.getMessage());
        }
    }

    /**
     * Builds system prompt for reactive commentary incorporating disposition traits.
     */
    static String buildReactiveSystemPrompt(AgentDescriptor descriptor) {
        AgentDisposition disposition = descriptor.disposition();
        StringBuilder    sb          = new StringBuilder();
        sb.append("You are a StarCraft II play-by-play commentator.\n");
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
        sb.append("(e.g. ZERG_ROACH_RUSH) and confidence score (0.0–1.0). ");
        sb.append("When present, call out the classification naturally.\n");
        sb.append("- CBR_CONTEXT: case-based reasoning from past games — similar game count, ");
        sb.append("predicted outcome, whether CBR influenced strategy selection. ");
        sb.append("When present, reference past game experience naturally.\n\n");
        sb.append("React with energy to the game moment. Keep it to 1-2 sentences.\n");
        sb.append("Your response should be plain text commentary (no labels or structure).\n");
        return sb.toString();
    }

    /**
     * Builds system prompt for narrative commentary incorporating disposition traits.
     */
    static String buildNarrativeSystemPrompt(AgentDescriptor descriptor) {
        AgentDisposition disposition = descriptor.disposition();
        StringBuilder    sb          = new StringBuilder();
        sb.append("You are a StarCraft II color commentator.\n");
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
        sb.append("When present, weave the strategic implications into your narrative.\n");
        sb.append("- CBR_CONTEXT: case-based reasoning from past games — similar game count, ");
        sb.append("predicted outcome, whether CBR influenced strategy selection. ");
        sb.append("When present, weave past game experience into your narrative.\n\n");
        sb.append("Narrate the strategic arc. Do NOT repeat moments just announced.\n");
        sb.append("Provide context and analysis. Keep it to 2-3 sentences.\n");
        sb.append("Your response should be plain text commentary (no labels or structure).\n");
        return sb.toString();
    }

    private static void appendTrait(StringBuilder sb, String label, String value) {
        if (value != null) {
            sb.append("- ").append(label).append(": ").append(value).append("\n");
        }
    }

    private static void appendPatternAssessment(StringBuilder sb, Map<?, ?> triggerMap) {
        Object pattern = triggerMap.get("patternAssessment");
        if (pattern instanceof Map<?, ?> patternMap) {
            Object archetype  = patternMap.get("archetype");
            Object confidence = patternMap.get("confidence");
            if (archetype != null) {
                sb.append("\nENEMY PATTERN: ").append(archetype);
                if (confidence != null) {
                    sb.append(" (confidence: ").append(confidence).append(")");
                }
                sb.append("\n");
            }
        }
    }

    private static void appendCbrContext(StringBuilder sb, Map<?, ?> triggerMap) {
        Object cbr = triggerMap.get("cbrContext");
        if (cbr instanceof Map<?, ?> cbrMap) {
            Object similarCount = cbrMap.get("similarCount");
            Object prediction   = cbrMap.get("prediction");
            Object influenced   = cbrMap.get("influenced");
            if (similarCount != null || prediction != null) {
                sb.append("\nPAST GAME EXPERIENCE:");
                if (similarCount != null) {
                    sb.append(" ").append(similarCount).append(" similar past games found");
                }
                if (prediction != null) {
                    sb.append(", predicted outcome: ").append(prediction);
                }
                if (Boolean.TRUE.equals(influenced)) {
                    sb.append(" [CBR influenced strategy selection]");
                }
                sb.append("\n");
            }
        }
    }


    /**
     * Builds user message for reactive commentary with trigger event and game state.
     */
    static String buildReactiveUserMessage(Map<String, Object> input) {
        StringBuilder sb      = new StringBuilder();
        Object        trigger = input.get(QuarkMindCaseFile.COMMENTARY_TRIGGER);
        if (trigger instanceof Map<?, ?> triggerMap) {
            Object momentTypes = triggerMap.get("momentTypes");
            if (momentTypes != null) {
                sb.append("MOMENT: ").append(momentTypes).append("\n\n");
            }

            sb.append("Game state:\n");
            Object frame = triggerMap.get("gameFrame");
            if (frame != null) {
                sb.append("- Frame: ").append(frame).append("\n");
            }
            Object minerals = triggerMap.get("minerals");
            if (minerals != null) {
                sb.append("- Minerals: ").append(minerals).append("\n");
            }
            Object supplyUsed = triggerMap.get("supplyUsed");
            if (supplyUsed != null) {
                sb.append("- Supply: ").append(supplyUsed).append("\n");
            }
            Object army = triggerMap.get("army");
            if (army != null) {
                sb.append("- Army size: ").append(army).append("\n");
            }

            appendPatternAssessment(sb, triggerMap);
            appendCbrContext(sb, triggerMap);
        }

        sb.append("\nProvide your immediate commentary on this moment.");
        return sb.toString();
    }

    /**
     * Builds user message for narrative commentary with accumulated events.
     */
    static String buildNarrativeUserMessage(Map<String, Object> input) {
        StringBuilder sb      = new StringBuilder();
        Object        trigger = input.get(QuarkMindCaseFile.COMMENTARY_NARRATIVE_TRIGGER);
        if (trigger instanceof Map<?, ?> triggerMap) {
            Object moments = triggerMap.get("moments");
            if (moments != null) {
                sb.append("Recent moments: ").append(moments).append("\n\n");
            }

            Object frame = triggerMap.get("gameFrame");
            if (frame != null) {
                sb.append("Frame: ").append(frame).append("\n");
            }

            appendPatternAssessment(sb, triggerMap);
            appendCbrContext(sb, triggerMap);
        }

        sb.append("\nProvide contextual narration of the strategic arc.");
        return sb.toString();
    }

    private static long getGameFrame(Map<String, Object> input, String triggerKey) {
        Object trigger = input.get(triggerKey);
        if (trigger instanceof Map<?, ?> triggerMap) {
            Object frame = triggerMap.get("gameFrame");
            if (frame instanceof Number num) {
                return num.longValue();
            }
        }
        return 0L;
    }
}

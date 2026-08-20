package io.quarkmind.chat.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.connectors.chat.model.ChatChannelRef;
import io.casehub.connectors.chat.model.ChatContent;
import io.casehub.connectors.chat.model.ChatMessageRef;
import io.casehub.neocortex.memory.Memory;
import io.casehub.neocortex.memory.personality.PersonalityWeights;
import io.casehub.neocortex.memory.reflection.ReflectionOrchestrator;
import io.quarkmind.agency.AgencyContext;
import io.quarkmind.agency.AgencyLoop;
import io.quarkmind.agency.chat.BotIdentityDetector;
import io.quarkmind.agency.chat.ChatDeltaReport;
import io.quarkmind.agency.llm.LlmPriority;
import io.quarkmind.agency.llm.LlmRequest;
import io.quarkmind.agency.llm.LlmRequestQueue;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.DispositionEvolution;
import io.quarkmind.agency.personality.PersonalityEvolutionPipeline;
import io.quarkmind.agency.schedule.IdleReflectionTrigger;
import io.quarkmind.chat.protocol.ChatIntent;
import io.quarkmind.chat.protocol.ChatPerception;
import io.quarkmind.chat.protocol.WakeReason;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ChatAgencyLoop implements AgencyLoop {

    private static final Logger LOG = Logger.getLogger(ChatAgencyLoop.class);

    @FunctionalInterface
    public interface LlmInvoker {
        String invoke(String systemPrompt, String userPrompt, String agentId);
    }

    record ParsedResponse(List<ChatIntent> intents, String observation) {}

    private final LlmInvoker llmInvoker;
    private final BotIdentityDetector identityDetector;
    private final LlmRequestQueue llmQueue;
    private final ObjectMapper mapper;
    private final ChatPerceptionBridge perceptionBridge;
    private final ChatMemoryFacade memoryFacade;
    private final IdleReflectionTrigger reflectionTrigger;
    private final ReflectionOrchestrator reflectionOrchestrator;
    private PersonalityEvolutionPipeline evolutionPipeline;
    private java.util.function.Supplier<AgentDescriptor> descriptorSupplier;
    private       LlmReflectionDispositionActivator dispositionActivator;

    private String systemPrompt = "";
    private String agentId = "chat-agent";
    private String tenantId = "default";
    private final Set<String> participatedThreadIds = new HashSet<>();
    private int consecutiveIdleTicks = 0;
    private Instant lastReflectionTimestamp = Instant.now();

    public ChatAgencyLoop(LlmInvoker llmInvoker, BotIdentityDetector identityDetector,
                          LlmRequestQueue llmQueue, ObjectMapper mapper,
                          ChatPerceptionBridge perceptionBridge) {
        this(llmInvoker, identityDetector, llmQueue, mapper, perceptionBridge, null, null, null);
    }

    public ChatAgencyLoop(LlmInvoker llmInvoker, BotIdentityDetector identityDetector,
                          LlmRequestQueue llmQueue, ObjectMapper mapper,
                          ChatPerceptionBridge perceptionBridge,
                          ChatMemoryFacade memoryFacade,
                          IdleReflectionTrigger reflectionTrigger) {
        this(llmInvoker, identityDetector, llmQueue, mapper, perceptionBridge,
                memoryFacade, reflectionTrigger, null);
    }

    public ChatAgencyLoop(LlmInvoker llmInvoker, BotIdentityDetector identityDetector,
                          LlmRequestQueue llmQueue, ObjectMapper mapper,
                          ChatPerceptionBridge perceptionBridge,
                          ChatMemoryFacade memoryFacade,
                          IdleReflectionTrigger reflectionTrigger,
                          ReflectionOrchestrator reflectionOrchestrator) {
        this.llmInvoker = llmInvoker;
        this.identityDetector = identityDetector;
        this.llmQueue = llmQueue;
        this.mapper = mapper;
        this.perceptionBridge = perceptionBridge;
        this.memoryFacade = memoryFacade;
        this.reflectionTrigger = reflectionTrigger;
        this.reflectionOrchestrator = reflectionOrchestrator;
    }

    public ChatAgencyLoop(LlmInvoker llmInvoker, BotIdentityDetector identityDetector,
                          LlmRequestQueue llmQueue, ObjectMapper mapper,
                          ChatPerceptionBridge perceptionBridge,
                          ChatMemoryFacade memoryFacade,
                          IdleReflectionTrigger reflectionTrigger,
                          ReflectionOrchestrator reflectionOrchestrator,
                          PersonalityEvolutionPipeline evolutionPipeline,
                          java.util.function.Supplier<AgentDescriptor> descriptorSupplier) {
        this(llmInvoker, identityDetector, llmQueue, mapper, perceptionBridge,
             memoryFacade, reflectionTrigger, reflectionOrchestrator);
        this.evolutionPipeline  = evolutionPipeline;
        this.descriptorSupplier = descriptorSupplier;
    }


    public void setSystemPrompt(String prompt) { this.systemPrompt = prompt; }
    public void setAgentId(String id) { this.agentId = id; }
    public void setTenantId(String id) { this.tenantId = id; }

    public void setDispositionActivator(LlmReflectionDispositionActivator activator) {
        this.dispositionActivator = activator;
    }


    @Override
    public void tick(AgencyContext context) {
        var perception = context.getAs("perception", ChatPerception.class);
        if (perception == null) return;

        if (perception.reason() == WakeReason.HEARTBEAT && !perception.hasActivity()) {
            consecutiveIdleTicks++;
            checkReflection();
            checkEvolution();
            context.put("intents", List.of());
            return;
        }

        if (!llmQueue.hasCapacity()) {
            context.put("intents", List.of());
            return;
        }

        consecutiveIdleTicks = 0;

        ChatDeltaReport report = perceptionBridge.buildDelta(
                perception, identityDetector, participatedThreadIds);
        String renderedContext = perceptionBridge.renderForLlm(report);

        List<Memory> memories = List.of();
        if (memoryFacade != null) {
            var participantIds = extractParticipantIds(perception);
            memories = memoryFacade.recall(agentId, tenantId, renderedContext,
                    participantIds, new PersonalityWeights(Map.of()), Instant.now());
        }

        String userPrompt = buildUserPrompt(renderedContext, context, memories);
        String response = llmInvoker.invoke(systemPrompt, userPrompt, agentId);
        ParsedResponse parsed = parseResponse(response);
        context.put("intents", parsed.intents());

        if (memoryFacade != null && parsed.observation() != null && !parsed.observation().isBlank()) {
            var sourceRefs = buildSourceRefs(perception);
            var participantIds = extractParticipantIds(perception);
            String memoryId = memoryFacade.ingest(agentId, tenantId,
                    parsed.observation(), sourceRefs, participantIds);
            submitImportanceScoring(memoryId, parsed.observation());
        }
    }

    private String buildUserPrompt(String renderedContext, AgencyContext context,
                                    List<Memory> memories) {
        var sb = new StringBuilder();
        sb.append("Needs: SOCIAL=%.0f, CURIOSITY=%.0f, EXPRESSION=%.0f\n".formatted(
                context.needState().get("SOCIAL"),
                context.needState().get("CURIOSITY"),
                context.needState().get("EXPRESSION")));

        if (!memories.isEmpty()) {
            sb.append("\nWhat I remember:\n");
            for (Memory m : memories) {
                sb.append("- ").append(m.text()).append("\n");
            }
        }

        sb.append("\n").append(renderedContext);
        sb.append("""

                Respond with JSON:
                {"action":"SEND|REPLY|REACT|WAIT","channel":"channel-id","text":"message","emoji":"emoji","messageId":"id-to-react-to","replyTo":"message-id","observation":"what I observed this tick"}
                Always include the observation field. Only include other fields relevant to the action.
                """);
        return sb.toString();
    }

    ParsedResponse parseResponse(String response) {
        var intents = new ArrayList<ChatIntent>();
        String observation = null;
        try {
            JsonNode root = mapper.readTree(response);

            observation = root.has("observation") ? root.get("observation").asText(null) : null;

            String action = root.has("action") ? root.get("action").asText() : null;
            if (action == null || "WAIT".equalsIgnoreCase(action)) {
                return new ParsedResponse(intents, observation);
            }

            switch (action.toUpperCase()) {
                case "SEND" -> {
                    String channel = root.has("channel") ? root.get("channel").asText() : null;
                    String text = root.has("text") ? root.get("text").asText() : null;
                    if (channel != null && text != null) {
                        intents.add(new ChatIntent.Send(channel, new ChatContent(text)));
                    }
                }
                case "REPLY" -> {
                    String replyTo = root.has("replyTo") ? root.get("replyTo").asText() : null;
                    String text = root.has("text") ? root.get("text").asText() : null;
                    if (replyTo != null && text != null) {
                        var parentRef = new ChatMessageRef(new ChatChannelRef(""), replyTo);
                        intents.add(new ChatIntent.Reply(parentRef, new ChatContent(text)));
                    }
                }
                case "REACT" -> {
                    String msgId = root.has("messageId") ? root.get("messageId").asText() : null;
                    String emoji = root.has("emoji") ? root.get("emoji").asText() : null;
                    if (msgId != null && emoji != null) {
                        var msgRef = new ChatMessageRef(new ChatChannelRef(""), msgId);
                        intents.add(new ChatIntent.React(msgRef, emoji));
                    }
                }
                default -> {}
            }
        } catch (Exception e) {
            LOG.debug("LLM response parse failure", e);
        }
        return new ParsedResponse(intents, observation);
    }


    private void checkReflection() {
        if (reflectionTrigger == null || reflectionOrchestrator == null) {return;}
        if (!reflectionTrigger.shouldReflect(consecutiveIdleTicks)) {return;}
        try {
            reflectionOrchestrator.reflect(agentId, tenantId, lastReflectionTimestamp, 50);
            lastReflectionTimestamp = Instant.now();
            reflectionTrigger.reset();
        } catch (Exception e) {
            LOG.warn("Reflection failed", e);
        }
    }

    private void checkEvolution() {
        if (evolutionPipeline == null || descriptorSupplier == null) {return;}
        try {
            var descriptor = descriptorSupplier.get();
            var result     = evolutionPipeline.checkEvolution(descriptor);
            result.ifPresent(r -> {
                if (r instanceof DispositionEvolution.EvolutionResult.Evolved evolved) {
                    LOG.infof("Personality evolved: %s → %s", evolved.previousTypeLabel(), evolved.newTypeLabel());
                    if (dispositionActivator != null) {
                        dispositionActivator.updateProfile(evolved.newProfile());
                    }
                } else if (r instanceof DispositionEvolution.EvolutionResult.Dampened dampened) {
                    LOG.infof("Personality evolution dampened (decay=%.2f)", dampened.decayFactor());
                }
            });
        } catch (Exception e) {
            LOG.warn("Evolution check failed", e);
        }
    }


    private void submitImportanceScoring(String memoryId, String observation) {
        String prompt = "Rate the importance of this experience on a scale of 0.0 to 1.0, " +
                "where 0.0 is mundane and 1.0 is life-changing. Respond with a single number.\n\n" +
                "Experience: " + observation;
        llmQueue.submit(new LlmRequest(prompt, LlmPriority.LOW, Map.of(), response -> {
            try {
                double score = Double.parseDouble(response.trim());
                if (score >= 0.0 && score <= 1.0) {
                    memoryFacade.scoreImportance(memoryId, tenantId, score);
                    if (reflectionTrigger != null) {
                        reflectionTrigger.accumulate(score);
                    }
                }
            } catch (Exception e) {
                LOG.warn("Importance scoring failed for memory " + memoryId, e);
            }
        }));
    }

    private Set<String> extractParticipantIds(ChatPerception perception) {
        var ids = new HashSet<String>();
        for (var messages : perception.channelDeltas().values()) {
            for (var msg : messages) {
                if (msg.sender() != null) {
                    ids.add(msg.sender().id());
                }
            }
        }
        return ids;
    }

    private Map<String, String> buildSourceRefs(ChatPerception perception) {
        var refs = new HashMap<String, String>();
        for (var entry : perception.channelDeltas().entrySet()) {
            var messages = entry.getValue();
            if (!messages.isEmpty()) {
                refs.put("source.channelId", entry.getKey());
                refs.put("source.firstMessageId", messages.get(0).messageRef().messageId());
                refs.put("source.lastMessageId", messages.get(messages.size() - 1).messageRef().messageId());
            }
        }
        return refs;
    }
}

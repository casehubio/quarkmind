package io.quarkmind.chat.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.blocks.agentic.social.InnerLifeOrchestrator;
import io.casehub.blocks.agentic.social.InnerLifeTick;
import io.casehub.blocks.agentic.social.drive.DriveOrchestrator;
import io.casehub.blocks.summarisation.EventLevel;
import io.casehub.blocks.summarisation.LevelEvent;
import io.casehub.connectors.chat.model.ChatChannelRef;
import io.casehub.connectors.chat.model.ChatContent;
import io.casehub.connectors.chat.model.ChatMessageRef;
import io.casehub.neocortex.memory.Memory;
import io.casehub.neocortex.memory.personality.PersonalityWeights;
import io.quarkmind.agency.AgencyContext;
import io.quarkmind.agency.AgencyLoop;
import io.quarkmind.agency.chat.ChatDeltaReport;
import io.quarkmind.agency.llm.LlmPriority;
import io.quarkmind.agency.llm.LlmRequest;
import io.quarkmind.agency.llm.LlmRequestQueue;
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

    private static final Logger     LOG        = Logger.getLogger(ChatAgencyLoop.class);
    private static final EventLevel CHAT_LEVEL = new EventLevel("chat", 0);

    @FunctionalInterface
    public interface LlmInvoker {
        String invoke(String systemPrompt, String userPrompt, String agentId);
    }

    record ParsedResponse(List<ChatIntent> intents, String observation) {}

    private final LlmInvoker            llmInvoker;
    private final LlmRequestQueue       llmQueue;
    private final ObjectMapper          mapper;
    private final ChatPerceptionBridge  perceptionBridge;
    private final ChatMemoryFacade      memoryFacade;
    private final InnerLifeOrchestrator innerLifeOrchestrator;
    private final DriveOrchestrator     driveOrchestrator;

    public ChatAgencyLoop(LlmInvoker llmInvoker, LlmRequestQueue llmQueue,
                          ObjectMapper mapper, ChatPerceptionBridge perceptionBridge,
                          ChatMemoryFacade memoryFacade,
                          InnerLifeOrchestrator innerLifeOrchestrator,
                          DriveOrchestrator driveOrchestrator) {
        this.llmInvoker            = llmInvoker;
        this.llmQueue              = llmQueue;
        this.mapper                = mapper;
        this.perceptionBridge      = perceptionBridge;
        this.memoryFacade          = memoryFacade;
        this.innerLifeOrchestrator = innerLifeOrchestrator;
        this.driveOrchestrator     = driveOrchestrator;
    }

    @Override
    public void tick(AgencyContext context) {
        var perception = context.getAs("perception", ChatPerception.class);
        if (perception == null) {return;}

        var character = context.getAs("character", CharacterContext.class);
        if (character == null) {return;}

        if (perception.reason() == WakeReason.HEARTBEAT && !perception.hasActivity()) {
            handleProactiveTick(context, character);
            return;
        }

        if (!llmQueue.hasCapacity()) {
            context.put("intents", List.of());
            return;
        }

        handleReactiveTick(context, perception, character);
    }

    private void handleReactiveTick(AgencyContext context, ChatPerception perception,
                                    CharacterContext character) {
        var descriptor = character.descriptorSupplier() != null
                         ? character.descriptorSupplier().get() : null;

        if (innerLifeOrchestrator != null && descriptor != null) {
            var event = new LevelEvent<>(renderPerceptionSummary(perception), System.currentTimeMillis(), CHAT_LEVEL);
            innerLifeOrchestrator.observe(event, descriptor);
        }

        ChatDeltaReport report = perceptionBridge.buildDelta(
                perception, character.identityDetector(), character.participatedThreadIds());
        String renderedContext = perceptionBridge.renderForLlm(report);

        List<Memory> memories = List.of();
        if (memoryFacade != null) {
            var participantIds = extractParticipantIds(perception);
            memories = memoryFacade.recall(character.agentId(), character.tenantId(),
                                           renderedContext, participantIds, new PersonalityWeights(Map.of()), Instant.now());
        }

        String         userPrompt = buildUserPrompt(renderedContext, character, memories);
        String         response   = llmInvoker.invoke(character.systemPrompt(), userPrompt, character.agentId());
        ParsedResponse parsed     = parseResponse(response);
        context.put("intents", parsed.intents());

        if (innerLifeOrchestrator != null && descriptor != null && !parsed.intents().isEmpty()) {
            innerLifeOrchestrator.observeResponse(descriptor);
        }

        if (memoryFacade != null && parsed.observation() != null && !parsed.observation().isBlank()) {
            var sourceRefs     = buildSourceRefs(perception);
            var participantIds = extractParticipantIds(perception);
            String memoryId = memoryFacade.ingest(character.agentId(), character.tenantId(),
                                                  parsed.observation(), sourceRefs, participantIds);
            submitImportanceScoring(character, memoryId, parsed.observation());
        }
    }

    private void handleProactiveTick(AgencyContext context, CharacterContext character) {
        if (innerLifeOrchestrator == null) {
            context.put("intents", List.of());
            return;
        }
        var descriptor = character.descriptorSupplier() != null
                         ? character.descriptorSupplier().get() : null;
        if (descriptor == null) {
            context.put("intents", List.of());
            return;
        }
        String channelContext = character.worldBridge() != null
                                ? "Watched channels: " + String.join(", ", character.worldBridge().watchedChannels())
                                : null;
        var result = innerLifeOrchestrator.tick(descriptor, channelContext);
        if (result instanceof InnerLifeTick.Initiated initiated) {
            String channel = initiated.channelHint();
            if (channel == null && character.worldBridge() != null
                && !character.worldBridge().watchedChannels().isEmpty()) {
                channel = character.worldBridge().watchedChannels().get(0);
            }
            if (channel != null) {
                var intents = List.<ChatIntent>of(
                        new ChatIntent.Send(channel, new ChatContent(initiated.content())));
                context.put("intents", intents);
            } else {
                context.put("intents", List.of());
            }
        } else {
            context.put("intents", List.of());
        }
    }

    private String buildUserPrompt(String renderedContext, CharacterContext character,
                                   List<Memory> memories) {
        var sb = new StringBuilder();

        if (driveOrchestrator != null) {
            var drives = driveOrchestrator.currentDrives(character.agentId(), character.tenantId());
            drives.ifPresent(dp -> sb.append("Drives: ").append(dp).append("\n"));
        }

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
        var    intents     = new ArrayList<ChatIntent>();
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
                    String text    = root.has("text") ? root.get("text").asText() : null;
                    if (channel != null && text != null) {
                        intents.add(new ChatIntent.Send(channel, new ChatContent(text)));
                    }
                }
                case "REPLY" -> {
                    String replyTo = root.has("replyTo") ? root.get("replyTo").asText() : null;
                    String text    = root.has("text") ? root.get("text").asText() : null;
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

    private void submitImportanceScoring(CharacterContext character, String memoryId, String observation) {
        String prompt = "Rate the importance of this experience on a scale of 0.0 to 1.0, " +
                        "where 0.0 is mundane and 1.0 is life-changing. Respond with a single number.\n\n" +
                        "Experience: " + observation;
        llmQueue.submit(new LlmRequest(prompt, LlmPriority.LOW, Map.of(), response -> {
            try {
                double score = Double.parseDouble(response.trim());
                if (score >= 0.0 && score <= 1.0 && memoryFacade != null) {
                    memoryFacade.scoreImportance(memoryId, character.tenantId(), score);
                }
            } catch (Exception e) {
                LOG.warn("Importance scoring failed for memory " + memoryId, e);
            }
        }));
    }

    private String renderPerceptionSummary(ChatPerception perception) {
        var sb = new StringBuilder();
        for (var entry : perception.channelDeltas().entrySet()) {
            for (var msg : entry.getValue()) {
                sb.append(msg.sender() != null ? msg.sender().id() : "unknown")
                  .append(": ").append(msg.content().text()).append("\n");
            }
        }
        return sb.toString();
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

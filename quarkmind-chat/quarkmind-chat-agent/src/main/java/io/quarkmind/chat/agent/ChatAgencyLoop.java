package io.quarkmind.chat.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.connectors.chat.model.ChatChannelRef;
import io.casehub.connectors.chat.model.ChatContent;
import io.casehub.connectors.chat.model.ChatMessageRef;
import io.quarkmind.agency.AgencyContext;
import io.quarkmind.agency.AgencyLoop;
import io.quarkmind.agency.chat.BotIdentityDetector;
import io.quarkmind.agency.chat.ChatDeltaReport;
import io.quarkmind.agency.llm.LlmRequestQueue;
import io.quarkmind.chat.protocol.ChatIntent;
import io.quarkmind.chat.protocol.ChatPerception;
import io.quarkmind.chat.protocol.WakeReason;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ChatAgencyLoop implements AgencyLoop {

    @FunctionalInterface
    public interface LlmInvoker {
        String invoke(String systemPrompt, String userPrompt, String agentId);
    }

    private final LlmInvoker llmInvoker;
    private final BotIdentityDetector identityDetector;
    private final LlmRequestQueue llmQueue;
    private final ObjectMapper mapper;
    private final ChatPerceptionBridge perceptionBridge;
    private String systemPrompt = "";
    private String agentId = "chat-agent";
    private final Set<String> participatedThreadIds = new HashSet<>();

    public ChatAgencyLoop(LlmInvoker llmInvoker, BotIdentityDetector identityDetector,
                          LlmRequestQueue llmQueue, ObjectMapper mapper,
                          ChatPerceptionBridge perceptionBridge) {
        this.llmInvoker = llmInvoker;
        this.identityDetector = identityDetector;
        this.llmQueue = llmQueue;
        this.mapper = mapper;
        this.perceptionBridge = perceptionBridge;
    }

    public void setSystemPrompt(String prompt) { this.systemPrompt = prompt; }
    public void setAgentId(String id) { this.agentId = id; }

    @Override
    public void tick(AgencyContext context) {
        var perception = context.getAs("perception", ChatPerception.class);
        if (perception == null) return;

        if (perception.reason() == WakeReason.HEARTBEAT && !perception.hasActivity()) {
            context.put("intents", List.of());
            return;
        }

        if (!llmQueue.hasCapacity()) {
            context.put("intents", List.of());
            return;
        }

        ChatDeltaReport report = perceptionBridge.buildDelta(
                perception, identityDetector, participatedThreadIds);
        String renderedContext = perceptionBridge.renderForLlm(report);

        String userPrompt = buildUserPrompt(renderedContext, context);
        String response = llmInvoker.invoke(systemPrompt, userPrompt, agentId);
        List<ChatIntent> intents = parseResponse(response);
        context.put("intents", intents);
    }

    private String buildUserPrompt(String renderedContext, AgencyContext context) {
        var sb = new StringBuilder();
        sb.append("Needs: SOCIAL=%.0f, CURIOSITY=%.0f, EXPRESSION=%.0f\n".formatted(
                context.needState().get("SOCIAL"),
                context.needState().get("CURIOSITY"),
                context.needState().get("EXPRESSION")));
        sb.append("\n").append(renderedContext);
        sb.append("""

                Respond with JSON:
                {"action":"SEND|REPLY|REACT|WAIT","channel":"channel-id","text":"message","emoji":"emoji","messageId":"id-to-react-to","replyTo":"message-id"}
                Only include fields relevant to the action.
                """);
        return sb.toString();
    }

    List<ChatIntent> parseResponse(String response) {
        var intents = new ArrayList<ChatIntent>();
        try {
            JsonNode root = mapper.readTree(response);
            String action = root.has("action") ? root.get("action").asText() : null;
            if (action == null || "WAIT".equalsIgnoreCase(action)) return intents;

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
            // LLM response parsing failures are expected — return empty intents
        }
        return intents;
    }
}

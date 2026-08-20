package io.quarkmind.chat.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.neocortex.memory.Memory;
import io.casehub.neocortex.memory.reflection.ReflectionEvent;
import io.casehub.neocortex.memory.reflection.ReflectionSynthesizer;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class LlmReflectionSynthesizer implements ReflectionSynthesizer {

    private static final Logger LOG = Logger.getLogger(LlmReflectionSynthesizer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatAgencyLoop.LlmInvoker llmInvoker;

    public LlmReflectionSynthesizer(ChatAgencyLoop.LlmInvoker llmInvoker) {
        this.llmInvoker = llmInvoker;
    }

    @Override
    public List<ReflectionEvent> synthesize(String agentId, String tenantId,
                                            List<Memory> sources, int targetLevel) {
        try {
            String sourceText = sources.stream()
                    .map(m -> "- " + m.text())
                    .collect(Collectors.joining("\n"));

            String prompt = "Given these recent experiences:\n" + sourceText +
                    "\n\nWhat patterns or insights do you notice? " +
                    "Produce 1-3 generalized reflections as a JSON array: " +
                    "[{\"insight\":\"...\"}]";

            String response = llmInvoker.invoke("You are a reflective observer.", prompt, agentId);
            JsonNode root = MAPPER.readTree(response);

            var events = new ArrayList<ReflectionEvent>();
            var sourceIds = sources.stream().map(Memory::memoryId).toList();

            if (root.isArray()) {
                for (JsonNode node : root) {
                    String insight = node.has("insight") ? node.get("insight").asText(null) : null;
                    if (insight != null && !insight.isBlank()) {
                        Double importance = Math.min(0.3 + (targetLevel * 0.2), 1.0);
                        events.add(new ReflectionEvent(agentId, tenantId, null,
                                insight, targetLevel, sourceIds, importance, Map.of()));
                    }
                }
            }
            return events;
        } catch (Exception e) {
            LOG.warn("Reflection synthesis failed", e);
            return List.of();
        }
    }
}

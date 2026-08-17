package io.quarkmind.ville.agent;

import io.quarkmind.agency.AgencyContext;
import io.quarkmind.agency.AgencyLoop;
import io.quarkmind.ville.protocol.*;
import java.util.ArrayList;
import java.util.List;

public class VilleAgencyLoop implements AgencyLoop {

    @FunctionalInterface
    public interface LlmInvoker {
        String invoke(String systemPrompt, String userPrompt, String agentId);
    }

    private final LlmInvoker llmInvoker;
    private String systemPrompt = "";
    private String agentId = "";

    public VilleAgencyLoop(LlmInvoker llmInvoker) {
        this.llmInvoker = llmInvoker;
    }

    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    @Override
    public void tick(AgencyContext context) {
        var perception = context.getAs("perception", VillePerception.class);
        if (perception == null) return;

        context.needState().set("SOCIAL", perception.self().needs().getOrDefault("SOCIAL", 0.0));
        context.needState().set("ENERGY", perception.self().needs().getOrDefault("ENERGY", 0.0));
        context.setTick(perception.tick());

        String userPrompt = buildUserPrompt(perception, context);
        String response = llmInvoker.invoke(systemPrompt, userPrompt, agentId);
        List<VilleIntent> intents = parseResponse(response);
        context.put("intents", intents);

        String thinking = extractField(response, "thinking");
        if (thinking != null) {
            context.put("thinking", thinking);
        }
    }

    private String buildUserPrompt(VillePerception perception, AgencyContext context) {
        var sb = new StringBuilder();
        sb.append("You are at position (%.1f, %.1f).\n".formatted(
                perception.self().position().x(), perception.self().position().y()));
        sb.append("Needs: SOCIAL=%.0f/100, ENERGY=%.0f/100\n".formatted(
                context.needState().get("SOCIAL"), context.needState().get("ENERGY")));

        if (!perception.nearby().isEmpty()) {
            sb.append("Nearby:\n");
            for (var c : perception.nearby()) {
                sb.append("  - %s at (%.1f, %.1f)".formatted(c.id(), c.position().x(), c.position().y()));
                if (c.lastDialogue() != null) sb.append(" said: \"%s\"".formatted(c.lastDialogue()));
                sb.append("\n");
            }
        } else {
            sb.append("No one nearby.\n");
        }

        sb.append("""

                Respond with JSON:
                {"thinking": "your private thoughts", "action": "MOVE|TALK|REST|EMOTE", "x": 0, "y": 0, "z": 0, "text": "", "emote": ""}
                Only include fields relevant to the action. MOVE needs x,y,z. TALK needs text. EMOTE needs emote. REST needs nothing extra.
                """);
        return sb.toString();
    }

    static List<VilleIntent> parseResponse(String response) {
        var intents = new ArrayList<VilleIntent>();
        try {
            String action = extractField(response, "action");
            if (action == null) return intents;

            switch (action.toUpperCase()) {
                case "MOVE" -> {
                    double x = Double.parseDouble(extractField(response, "x"));
                    double y = Double.parseDouble(extractField(response, "y"));
                    String zStr = extractField(response, "z");
                    double z = zStr != null ? Double.parseDouble(zStr) : 0.0;
                    intents.add(new VilleIntent.Move(new Position(x, y, z)));
                }
                case "TALK" -> {
                    String text = extractField(response, "text");
                    if (text != null) intents.add(new VilleIntent.Talk(text));
                }
                case "REST" -> intents.add(new VilleIntent.Rest());
                case "EMOTE" -> {
                    String emote = extractField(response, "emote");
                    if (emote != null) intents.add(new VilleIntent.Emote(emote));
                }
                default -> {}
            }
        } catch (Exception e) {
            // LLM response parsing failures are expected
        }
        return intents;
    }

    private static String extractField(String json, String field) {
        String pattern = "\"" + field + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + pattern.length());
        if (colon < 0) return null;
        int start = colon + 1;
        while (start < json.length() && json.charAt(start) == ' ') start++;
        if (start >= json.length()) return null;
        if (json.charAt(start) == '"') {
            int end = json.indexOf('"', start + 1);
            return end > start ? json.substring(start + 1, end) : null;
        }
        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
        return json.substring(start, end).strip();
    }
}

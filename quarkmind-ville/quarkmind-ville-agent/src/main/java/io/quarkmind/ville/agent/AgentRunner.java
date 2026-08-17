package io.quarkmind.ville.agent;

import io.quarkmind.agency.AgencyContext;
import io.quarkmind.agency.intent.IntentQueue;
import io.quarkmind.agency.needs.NeedState;
import io.quarkmind.ville.protocol.CharacterSnapshot;
import io.quarkmind.ville.protocol.Position;
import io.quarkmind.ville.protocol.VilleIntent;
import io.quarkmind.ville.protocol.VillePerception;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;

public class AgentRunner {

    private final String serverUrl;
    private final String characterId;
    private final VilleAgencyLoop.LlmInvoker llmInvoker;

    public AgentRunner(String serverUrl, String characterId, VilleAgencyLoop.LlmInvoker llmInvoker) {
        this.serverUrl = serverUrl;
        this.characterId = characterId;
        this.llmInvoker = llmInvoker;
    }

    public void run() throws Exception {
        var bridge = new VilleWorldBridge();
        var loop = new VilleAgencyLoop(llmInvoker);
        loop.setAgentId(characterId);

        var shutdownLatch = new CountDownLatch(1);
        var ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create(serverUrl), new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        String msg = data.toString();
                        if (msg.contains("\"type\":\"PERCEPTION\"") && msg.contains("\"self\"")) {
                            var perception = parsePerception(msg);
                            if (perception != null) {
                                bridge.onPerception(perception);
                            }
                        }
                        webSocket.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        shutdownLatch.countDown();
                        return null;
                    }
                }).join();

        bridge.setSendFunction(text -> ws.sendText(text, true));
        ws.sendText("{\"type\":\"CONNECT\",\"role\":\"agent\",\"characterId\":\"%s\"}".formatted(characterId), true);

        var context = new AgencyContext(new NeedState());
        var intentQueue = new IntentQueue<VilleIntent>();

        System.out.printf("[%s] Connected to %s%n", characterId, serverUrl);

        while (!Thread.currentThread().isInterrupted()) {
            var perception = bridge.perceive();
            if (perception == null) break;

            context.put("perception", perception);
            loop.tick(context);

            @SuppressWarnings("unchecked")
            var intents = (List<VilleIntent>) context.get("intents");
            if (intents != null) {
                for (var intent : intents) {
                    intentQueue.enqueue(intent);
                }
                bridge.dispatch(intentQueue);
            }

            var thinking = (String) context.get("thinking");
            if (thinking != null) {
                ws.sendText("{\"type\":\"THOUGHT\",\"thinking\":\"%s\"}".formatted(
                        thinking.replace("\"", "\\\"")), true);
            }
        }

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown").join();
    }

    public static void runAll(String serverUrl, List<String> characterIds, VilleAgencyLoop.LlmInvoker llmInvoker) {
        for (var id : characterIds) {
            Thread.ofVirtual().name("ville-agent-" + id).start(() -> {
                try {
                    new AgentRunner(serverUrl, id, llmInvoker).run();
                } catch (Exception e) {
                    System.err.printf("[%s] Agent failed: %s%n", id, e.getMessage());
                }
            });
        }
    }

    static VillePerception parsePerception(String json) {
        try {
            String selfId = extractNestedField(json, "self", "id");
            double selfX = Double.parseDouble(extractNestedField(json, "self", "x"));
            double selfY = Double.parseDouble(extractNestedField(json, "self", "y"));
            double selfZ = Double.parseDouble(extractNestedField(json, "self", "z"));
            long tick = Long.parseLong(extractField(json, "tick"));

            double social = 0, energy = 0;
            int needsIdx = json.indexOf("\"needs\"", json.indexOf("\"self\""));
            if (needsIdx >= 0) {
                String needsSection = json.substring(needsIdx, json.indexOf('}', needsIdx) + 1);
                String socialStr = extractField(needsSection, "SOCIAL");
                String energyStr = extractField(needsSection, "ENERGY");
                if (socialStr != null) social = Double.parseDouble(socialStr);
                if (energyStr != null) energy = Double.parseDouble(energyStr);
            }

            var self = new CharacterSnapshot(selfId, new Position(selfX, selfY, selfZ),
                    Map.of("SOCIAL", social, "ENERGY", energy), null);

            return new VillePerception(tick, self, List.of(), List.of());
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractNestedField(String json, String parent, String field) {
        int parentIdx = json.indexOf("\"" + parent + "\"");
        if (parentIdx < 0) return null;
        String sub = json.substring(parentIdx);
        return extractField(sub, field);
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

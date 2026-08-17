package io.quarkmind.ville.agent;

import io.quarkmind.agency.intent.IntentQueue;
import io.quarkmind.agency.spi.WorldBridge;
import io.quarkmind.ville.protocol.VilleIntent;
import io.quarkmind.ville.protocol.VillePerception;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

public class VilleWorldBridge implements WorldBridge<VillePerception, VilleIntent> {

    private final BlockingQueue<VillePerception> perceptionQueue = new LinkedBlockingQueue<>();
    private Consumer<String> sendFunction = msg -> {};

    public void setSendFunction(Consumer<String> fn) { this.sendFunction = fn; }

    public void onPerception(VillePerception perception) {
        perceptionQueue.offer(perception);
    }

    @Override
    public void connect() {}

    @Override
    public void disconnect() {}

    @Override
    public VillePerception perceive() {
        try {
            return perceptionQueue.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    @Override
    public void dispatch(IntentQueue<VilleIntent> intents) {
        var drained = intents.drainAll();
        for (var intent : drained) {
            sendFunction.accept(intentToJson(intent));
        }
    }

    private String intentToJson(VilleIntent intent) {
        return switch (intent) {
            case VilleIntent.Move m -> "{\"type\":\"INTENT\",\"action\":\"MOVE\",\"target\":{\"x\":%.1f,\"y\":%.1f,\"z\":%.1f}}".formatted(
                    m.target().x(), m.target().y(), m.target().z());
            case VilleIntent.Talk t -> "{\"type\":\"INTENT\",\"action\":\"TALK\",\"text\":\"%s\"}".formatted(
                    t.text().replace("\"", "\\\""));
            case VilleIntent.Rest r -> "{\"type\":\"INTENT\",\"action\":\"REST\"}";
            case VilleIntent.Emote e -> "{\"type\":\"INTENT\",\"action\":\"EMOTE\",\"emote\":\"%s\"}".formatted(e.emote());
        };
    }
}

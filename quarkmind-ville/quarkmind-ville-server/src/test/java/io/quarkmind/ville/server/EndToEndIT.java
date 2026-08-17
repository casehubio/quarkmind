package io.quarkmind.ville.server;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

@QuarkusTest
class EndToEndIT {

    @Inject VilleServer server;

    @TestHTTPResource("/ws/ville")
    URI wsUri;

    private URI wsUrl() {
        return URI.create(wsUri.toString().replace("http://", "ws://"));
    }

    @Test
    void agentConnectsReceivesPerceptionSendsIntentAndSeesResult() throws Exception {
        var perceptionReceived = new CompletableFuture<String>();
        var secondPerception = new CompletableFuture<String>();
        var ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(wsUrl(), new WebSocket.Listener() {
                    int perceptionCount = 0;
                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        String msg = data.toString();
                        if (msg.contains("PERCEPTION")) {
                            perceptionCount++;
                            if (perceptionCount == 1) perceptionReceived.complete(msg);
                            else if (perceptionCount == 2) secondPerception.complete(msg);
                        }
                        webSocket.request(1);
                        return null;
                    }
                }).join();

        ws.sendText("{\"type\":\"CONNECT\",\"role\":\"agent\",\"characterId\":\"alice\"}", true);
        server.tick();
        String perception = perceptionReceived.get(5, TimeUnit.SECONDS);
        assertThat(perception).contains("alice");

        ws.sendText("{\"type\":\"INTENT\",\"intentId\":\"e2e-1\",\"action\":\"TALK\",\"text\":\"Hello world!\"}", true);
        Thread.sleep(50);
        server.tick();
        secondPerception.get(5, TimeUnit.SECONDS);

        assertThat(server.worldState().character("alice").lastDialogue()).isEqualTo("Hello world!");
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    @Test
    void moveIntentUpdatesPositionOverTicks() throws Exception {
        var initialPerception = new CompletableFuture<String>();
        var movedPerception = new CompletableFuture<String>();
        var ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(wsUrl(), new WebSocket.Listener() {
                    int count = 0;
                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        String msg = data.toString();
                        if (msg.contains("PERCEPTION")) {
                            count++;
                            if (count == 1) initialPerception.complete(msg);
                            else if (count == 2) movedPerception.complete(msg);
                        }
                        webSocket.request(1);
                        return null;
                    }
                }).join();

        ws.sendText("{\"type\":\"CONNECT\",\"role\":\"agent\",\"characterId\":\"bob\"}", true);
        server.tick();
        initialPerception.get(5, TimeUnit.SECONDS);

        var bobBefore = server.worldState().character("bob").position();
        ws.sendText("{\"type\":\"INTENT\",\"intentId\":\"e2e-2\",\"action\":\"MOVE\",\"target\":{\"x\":40.0,\"y\":40.0,\"z\":0.0}}", true);
        Thread.sleep(50);
        server.tick();
        movedPerception.get(5, TimeUnit.SECONDS);

        var bobAfter = server.worldState().character("bob").position();
        assertThat(bobAfter).isNotEqualTo(bobBefore);
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    @Test
    void observerReceivesThoughtBroadcast() throws Exception {
        var thoughtReceived = new CompletableFuture<String>();
        var agentReady = new CompletableFuture<Void>();

        var observer = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(wsUrl(), new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        String msg = data.toString();
                        if (msg.contains("THOUGHT") && !thoughtReceived.isDone()) {
                            thoughtReceived.complete(msg);
                        }
                        webSocket.request(1);
                        return null;
                    }
                }).join();
        observer.sendText("{\"type\":\"CONNECT\",\"role\":\"observer\"}", true);
        Thread.sleep(50);

        var agent = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(wsUrl(), new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        if (data.toString().contains("PERCEPTION") && !agentReady.isDone()) {
                            agentReady.complete(null);
                        }
                        webSocket.request(1);
                        return null;
                    }
                }).join();
        agent.sendText("{\"type\":\"CONNECT\",\"role\":\"agent\",\"characterId\":\"alice\"}", true);
        server.tick();
        agentReady.get(5, TimeUnit.SECONDS);

        agent.sendText("{\"type\":\"THOUGHT\",\"thinking\":\"I wonder what Bob is doing\"}", true);
        String thought = thoughtReceived.get(5, TimeUnit.SECONDS);
        assertThat(thought).contains("alice");
        assertThat(thought).contains("I wonder what Bob is doing");

        agent.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        observer.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }
}

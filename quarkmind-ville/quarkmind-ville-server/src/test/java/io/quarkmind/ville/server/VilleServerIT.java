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
class VilleServerIT {

    @Inject VilleServer server;

    @TestHTTPResource("/ws/ville")
    URI wsUri;

    private URI wsUrl() {
        return URI.create(wsUri.toString().replace("http://", "ws://"));
    }

    @Test
    void agentConnectsAndReceivesPerception() throws Exception {
        var received = new CompletableFuture<String>();
        var ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(wsUrl(), new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        String msg = data.toString();
                        if (msg.contains("PERCEPTION") && !received.isDone()) {
                            received.complete(msg);
                        }
                        webSocket.request(1);
                        return null;
                    }
                }).join();

        ws.sendText("{\"type\":\"CONNECT\",\"role\":\"agent\",\"characterId\":\"alice\"}", true);
        server.tick();
        String perception = received.get(5, TimeUnit.SECONDS);
        assertThat(perception).contains("alice");
        assertThat(perception).contains("PERCEPTION");
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    @Test
    void observerReceivesAllCharacters() throws Exception {
        var received = new CompletableFuture<String>();
        var ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(wsUrl(), new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        String msg = data.toString();
                        if (msg.contains("characters") && !received.isDone()) {
                            received.complete(msg);
                        }
                        webSocket.request(1);
                        return null;
                    }
                }).join();

        ws.sendText("{\"type\":\"CONNECT\",\"role\":\"observer\"}", true);
        Thread.sleep(100);

        world().character("alice").setConnected(true);
        world().character("bob").setConnected(true);
        server.tick();

        String perception = received.get(5, TimeUnit.SECONDS);
        assertThat(perception).contains("alice");
        assertThat(perception).contains("bob");
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    @Test
    void intentIsApplied() throws Exception {
        var received = new CompletableFuture<String>();
        var ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(wsUrl(), new WebSocket.Listener() {
                    int count = 0;
                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        String msg = data.toString();
                        if (msg.contains("PERCEPTION")) {
                            count++;
                            if (count >= 2 && !received.isDone()) {
                                received.complete(msg);
                            }
                        }
                        webSocket.request(1);
                        return null;
                    }
                }).join();

        ws.sendText("{\"type\":\"CONNECT\",\"role\":\"agent\",\"characterId\":\"alice\"}", true);
        server.tick();
        Thread.sleep(50);

        ws.sendText("{\"type\":\"INTENT\",\"intentId\":\"t1\",\"action\":\"TALK\",\"text\":\"Hello!\"}", true);
        Thread.sleep(50);
        server.tick();

        String perception = received.get(5, TimeUnit.SECONDS);
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();

        assertThat(world().character("alice").lastDialogue()).isEqualTo("Hello!");
    }

    private WorldState world() {
        return server.worldState();
    }
}

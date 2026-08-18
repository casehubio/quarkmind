package io.quarkmind.qa.workbench;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkmind.agent.plugin.PatternAssessmentPublished;
import io.quarkmind.domain.AssessmentSource;
import io.quarkmind.domain.PatternAssessment;
import io.quarkmind.domain.StrategyArchetype;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class WorkbenchSocketIT {

    @Inject Event<PatternAssessmentPublished> patternEvent;
    @Inject WorkbenchBroadcaster broadcaster;

    @Test
    void pattern_event_arrives_via_websocket() throws Exception {
        var received = new CompletableFuture<String>();

        var ws = HttpClient.newHttpClient().newWebSocketBuilder()
            .buildAsync(URI.create("ws://localhost:8081/ws/workbench"), new WebSocket.Listener() {
                @Override
                public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                    received.complete(data.toString());
                    return CompletableFuture.completedFuture(null);
                }
            }).get(5, TimeUnit.SECONDS);

        broadcaster.waitForSession(5000);

        patternEvent.fire(new PatternAssessmentPublished(
            List.of(new PatternAssessment(StrategyArchetype.ZERG_ZERGLING_RUSH, 0.87, 1000, "6+ lings", AssessmentSource.DROOLS))));

        String json = received.get(5, TimeUnit.SECONDS);
        assertTrue(json.contains("\"type\":\"pattern\""));
        assertTrue(json.contains("ZERG_ZERGLING_RUSH"));

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    @Test
    void reconnect_receives_snapshot() throws Exception {
        patternEvent.fire(new PatternAssessmentPublished(
            List.of(new PatternAssessment(StrategyArchetype.ZERG_ZERGLING_RUSH, 0.5, 500, "snap", AssessmentSource.DROOLS))));

        var received = new CompletableFuture<String>();
        var ws = HttpClient.newHttpClient().newWebSocketBuilder()
            .buildAsync(URI.create("ws://localhost:8081/ws/workbench"), new WebSocket.Listener() {
                @Override
                public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                    received.complete(data.toString());
                    return CompletableFuture.completedFuture(null);
                }
            }).get(5, TimeUnit.SECONDS);

        String json = received.get(5, TimeUnit.SECONDS);
        assertTrue(json.contains("\"type\":\"pattern\""));

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }
}

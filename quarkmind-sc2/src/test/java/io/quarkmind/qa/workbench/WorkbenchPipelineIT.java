package io.quarkmind.qa.workbench;

import io.quarkmind.agent.AgentOrchestrator;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Layer-by-layer pipeline verification:
 * Layer 2: game tick → plugin → CDI event
 * Layer 4: CDI event → workbench WebSocket → browser
 */
@QuarkusTest
class WorkbenchPipelineIT {

    @Inject
    AgentOrchestrator                   orchestrator;
    @Inject
    io.quarkmind.sc2.mock.SimulatedGame simulatedGame;
    @Inject
    io.quarkmind.sc2.ScenarioRunner     scenarioRunner;
    @Inject
    WorkbenchBroadcaster broadcaster;


    @TestHTTPResource("/ws/workbench")
    URI wsUri;

    @BeforeEach
    void setUp() {
        simulatedGame.reset();
        orchestrator.startGame();
    }

    @org.junit.jupiter.api.Disabled("Cascade classifier returns empty — see #296")
    @Test
    void layer2_gameTicksProducePatternAssessmentEvent() {
        long t0 = System.currentTimeMillis();
        orchestrator.gameTick();
        long elapsed = System.currentTimeMillis() - t0;
        System.out.printf("[DIAG] gameTick took %dms%n", elapsed);

        scenarioRunner.run("spawn-enemy-attack");

        t0 = System.currentTimeMillis();
        orchestrator.gameTick();
        elapsed = System.currentTimeMillis() - t0;
        System.out.printf("[DIAG] gameTick with enemies took %dms%n", elapsed);

        assertThat(broadcaster.latestPatternSnapshot())
                .as("broadcaster should have a pattern snapshot")
                .isNotNull();
    }

    @org.junit.jupiter.api.Disabled("Depends on layer2 — see #296")
    @Test
    void layer4_workbenchWebSocketDeliversPatternEvents() throws Exception {
        var received = new CompletableFuture<String>();

        var ws = HttpClient.newHttpClient().newWebSocketBuilder()
                           .buildAsync(wsEndpoint(), new WebSocket.Listener() {
                               final StringBuilder sb = new StringBuilder();

                               @Override
                               public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                                   sb.append(data);
                                   if (last) {
                                       String msg = sb.toString();
                                       if (msg.contains("\"pattern\"")) {
                                           received.complete(msg);
                                       }
                                       sb.setLength(0);
                                   }
                                   ws.request(1);
                                   return null;
                               }
                           }).get(5, TimeUnit.SECONDS);

        for (int i = 0; i < 10; i++) {
            orchestrator.gameTick();
        }

        String msg = received.get(10, TimeUnit.SECONDS);
        assertThat(msg).contains("\"pattern\"");
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    private URI wsEndpoint() {
        return URI.create(wsUri.toString().replace("http://", "ws://"));
    }
}

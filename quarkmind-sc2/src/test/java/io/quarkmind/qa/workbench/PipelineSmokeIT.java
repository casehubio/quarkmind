package io.quarkmind.qa.workbench;

import io.quarkmind.agent.AgentOrchestrator;
import io.quarkmind.sc2.ScenarioRunner;
import io.quarkmind.sc2.mock.SimulatedGame;
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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class PipelineSmokeIT {

    @Inject AgentOrchestrator orchestrator;
    @Inject SimulatedGame simulatedGame;
    @Inject ScenarioRunner scenarioRunner;
    @Inject WorkbenchBroadcaster broadcaster;

    @TestHTTPResource("/ws/workbench")
    URI wsUri;

    @BeforeEach
    void setUp() {
        simulatedGame.reset();
        orchestrator.startGame();
        scenarioRunner.run("spawn-enemy-attack");
    }

    @Test
    void scoutingProducesPatternAssessment() {
        for (int i = 0; i < 30; i++) orchestrator.gameTick();

        WorkbenchEvent pattern = broadcaster.latestPatternSnapshot();
        assertThat(pattern)
                .as("scouting pipeline should produce a pattern assessment within 30 ticks")
                .isNotNull();
        assertThat(pattern.type()).isEqualTo("pattern");
    }

    @Test
    void strategySelectionFollowsPatternAssessment() {
        for (int i = 0; i < 30; i++) orchestrator.gameTick();

        WorkbenchEvent strategy = broadcaster.latestStrategySnapshot();
        assertThat(strategy)
                .as("strategy pipeline should select a strategy within 30 ticks")
                .isNotNull();
        assertThat(strategy.type()).isEqualTo("strategy");
    }

    @Test
    void tickResultHasNonNullCaseContext() {
        for (int i = 0; i < 5; i++) {orchestrator.gameTick();}

        var result = orchestrator.getLastTickResult();
        assertThat(result).as("tick result exists after 5 ticks").isNotNull();
        assertThat(result.solveSucceeded())
                .as("CaseHub solve should succeed — plugins ran without error")
                .isTrue();
    }


    @Test
    void webSocketDeliversPatternEvent() throws Exception {
        LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        WebSocket ws = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(
                        URI.create(wsUri.toString().replace("http://", "ws://")),
                        new MessageCollector(messages))
                .join();
        broadcaster.waitForSession(5000);

        for (int i = 0; i < 30; i++) orchestrator.gameTick();

        String msg = messages.poll(5, TimeUnit.SECONDS);
        assertThat(msg)
                .as("WebSocket should deliver at least one workbench event within 30 ticks")
                .isNotNull();
        assertThat(msg).contains("\"type\"");

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    static class MessageCollector implements WebSocket.Listener {
        private final LinkedBlockingQueue<String> queue;
        private final StringBuilder buffer = new StringBuilder();

        MessageCollector(LinkedBlockingQueue<String> queue) {
            this.queue = queue;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                queue.offer(buffer.toString());
                buffer.setLength(0);
            }
            webSocket.request(1);
            return new CompletableFuture<>();
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }
    }
}

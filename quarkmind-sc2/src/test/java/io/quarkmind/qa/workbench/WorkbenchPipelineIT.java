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
    jakarta.enterprise.event.Event<io.quarkmind.plugin.commentary.CommentaryCompleted>   commentaryEvent;
    @Inject
    jakarta.enterprise.event.Event<io.quarkmind.plugin.coaching.CoachingAdvicePublished> coachingEvent;


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

    @Test
    void layer2_gameTicksProduceStrategySelectionEvent() {
        orchestrator.gameTick();
        scenarioRunner.run("spawn-enemy-attack");
        orchestrator.gameTick();

        assertThat(broadcaster.latestStrategySnapshot())
                .as("broadcaster should have a strategy snapshot")
                .isNotNull();
    }


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

    @Test
    void layer4_lateConnectReceivesStrategySnapshot() throws Exception {
        // Run ticks FIRST so strategy fires before any WebSocket connects
        orchestrator.gameTick();
        scenarioRunner.run("spawn-enemy-attack");
        orchestrator.gameTick();

        assertThat(broadcaster.latestStrategySnapshot())
                .as("strategy should be cached before client connects")
                .isNotNull();

        // NOW connect — pushSnapshot should deliver cached strategy
        var received = new CompletableFuture<String>();

        var ws = HttpClient.newHttpClient().newWebSocketBuilder()
                           .buildAsync(wsEndpoint(), new WebSocket.Listener() {
                               final StringBuilder sb = new StringBuilder();

                               @Override
                               public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                                   sb.append(data);
                                   if (last) {
                                       String msg = sb.toString();
                                       if (msg.contains("\"strategy\"")) {
                                           received.complete(msg);
                                       }
                                       sb.setLength(0);
                                   }
                                   ws.request(1);
                                   return null;
                               }
                           }).get(5, TimeUnit.SECONDS);

        String msg = received.get(5, TimeUnit.SECONDS);
        assertThat(msg).contains("\"strategy\"");
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    @Test
    void layer4_commentaryEventDeliveredToWebSocket() throws Exception {
        var received = new CompletableFuture<String>();
        var ws = HttpClient.newHttpClient().newWebSocketBuilder()
                           .buildAsync(wsEndpoint(), new WebSocket.Listener() {
                               final StringBuilder sb = new StringBuilder();

                               @Override
                               public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                                   sb.append(data);
                                   if (last) {
                                       String msg = sb.toString();
                                       if (msg.contains("\"commentary\"")) {received.complete(msg);}
                                       sb.setLength(0);
                                   }
                                   ws.request(1);
                                   return null;
                               }
                           }).get(5, TimeUnit.SECONDS);

        broadcaster.waitForSession(5000);
        commentaryEvent.fire(new io.quarkmind.plugin.commentary.CommentaryCompleted(
                "test-commentator", "commentary-reactive", 1000,
                "Zerg aggression detected — roaches massing at the natural",
                io.quarkmind.plugin.commentary.CommentaryType.REACTIVE, 250));

        String msg = received.get(5, TimeUnit.SECONDS);
        assertThat(msg).contains("\"commentary\"");
        assertThat(msg).contains("roaches massing");
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    @Test
    void layer4_coachingEventDeliveredToWebSocket() throws Exception {
        var received = new CompletableFuture<String>();
        var ws = HttpClient.newHttpClient().newWebSocketBuilder()
                           .buildAsync(wsEndpoint(), new WebSocket.Listener() {
                               final StringBuilder sb = new StringBuilder();

                               @Override
                               public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                                   sb.append(data);
                                   if (last) {
                                       String msg = sb.toString();
                                       if (msg.contains("\"coaching\"")) {received.complete(msg);}
                                       sb.setLength(0);
                                   }
                                   ws.request(1);
                                   return null;
                               }
                           }).get(5, TimeUnit.SECONDS);

        broadcaster.waitForSession(5000);
        coachingEvent.fire(new io.quarkmind.plugin.coaching.CoachingAdvicePublished(
                new io.quarkmind.plugin.coaching.CoachingAdvice(
                        "Build a forge and add cannons at your natural", io.quarkmind.plugin.coaching.CoachingDomain.BUILD, null, 0),
                io.quarkmind.plugin.coaching.CoachingUrgencyTier.STRATEGIC, 1500, "test-corr-001"));

        String msg = received.get(5, TimeUnit.SECONDS);
        assertThat(msg).contains("\"coaching\"");
        assertThat(msg).contains("Build a forge");
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    @Test
    void layer2_momentDetectionProducesCommentaryTrigger() {
        // Publish scouting intel to L1 bus before tick — simulates early enemy contact
        var broker = io.quarkus.arc.Arc.container()
                                       .instance(io.quarkmind.agent.ScoutingIntelBroker.class).get();
        var LEVEL_1 = new io.casehub.blocks.summarisation.EventLevel("intel", 1);
        broker.level1Bus().publish(new io.casehub.blocks.summarisation.LevelEvent<>(
                new io.quarkmind.agent.plugin.ScoutingIntelPayload.ThreatPosition(
                        new io.quarkmind.domain.Point2d(50, 50)), 100, LEVEL_1, "default"));
        broker.level1Bus().publish(new io.casehub.blocks.summarisation.LevelEvent<>(
                new io.quarkmind.agent.plugin.ScoutingIntelPayload.ArmySize(8), 100, LEVEL_1, "default"));

        orchestrator.gameTick();
        scenarioRunner.run("spawn-enemy-attack");
        orchestrator.gameTick();

        var ctx = orchestrator.getLastTickResult().caseContext();
        var moments = ctx.getList(io.quarkmind.agent.QuarkMindCaseFile.MOMENTS_LATEST,
                                  io.quarkmind.plugin.summarisation.GameMoment.class);

        // Moments detected proves: L1 bus → MomentDetectionTask → Drools rules → moments
        // Commentary trigger builder consumes these during GameTickExecutor (cooldown prevents re-read)
        assertThat(moments).as("moment detection should produce moments from scouting intel")
                           .isNotEmpty();
        assertThat(moments.stream().map(m -> m.type().name()).toList())
                .as("should detect enemy contact moments")
                .containsAnyOf("FIRST_CONTACT", "NEXUS_UNDER_ATTACK", "BATTLE_STARTED");
    }

    @Test
    void layer3_commentaryTriggerSignalProducesLlmEvent() throws Exception {
        // Wire a listener for CommentaryCompleted CDI events
        var completedFuture = new CompletableFuture<String>();
        var ws = HttpClient.newHttpClient().newWebSocketBuilder()
                           .buildAsync(wsEndpoint(), new WebSocket.Listener() {
                               final StringBuilder sb = new StringBuilder();

                               @Override
                               public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                                   sb.append(data);
                                   if (last) {
                                       String msg = sb.toString();
                                       if (msg.contains("\"commentary\"")) {completedFuture.complete(msg);}
                                       sb.setLength(0);
                                   }
                                   ws.request(1);
                                   return null;
                               }
                           }).get(5, TimeUnit.SECONDS);

        broadcaster.waitForSession(5000);

        // Publish scouting intel + run ticks to trigger commentary
        var broker = io.quarkus.arc.Arc.container()
                                       .instance(io.quarkmind.agent.ScoutingIntelBroker.class).get();
        var LEVEL_1 = new io.casehub.blocks.summarisation.EventLevel("intel", 1);
        broker.level1Bus().publish(new io.casehub.blocks.summarisation.LevelEvent<>(
                new io.quarkmind.agent.plugin.ScoutingIntelPayload.ThreatPosition(
                        new io.quarkmind.domain.Point2d(50, 50)), 100, LEVEL_1, "default"));
        broker.level1Bus().publish(new io.casehub.blocks.summarisation.LevelEvent<>(
                new io.quarkmind.agent.plugin.ScoutingIntelPayload.ArmySize(8), 100, LEVEL_1, "default"));

        orchestrator.gameTick();
        scenarioRunner.run("spawn-enemy-attack");
        orchestrator.gameTick();

        // Wait for LLM worker to complete — Claude CLI call via Vertex
        try {
            String msg = completedFuture.get(30, TimeUnit.SECONDS);
            assertThat(msg).contains("\"commentary\"");
            System.out.printf("[DIAG] LLM commentary received: %s%n", msg.substring(0, Math.min(200, msg.length())));
        } catch (java.util.concurrent.TimeoutException e) {
            // Log what we know for diagnostics
            var ctx = orchestrator.getLastTickResult().caseContext();
            var moments = ctx.getList(io.quarkmind.agent.QuarkMindCaseFile.MOMENTS_LATEST,
                                      io.quarkmind.plugin.summarisation.GameMoment.class);
            System.out.printf("[DIAG] Moments existed: %s%n", moments != null && !moments.isEmpty());
            System.out.printf("[DIAG] Commentary trigger would have fired (moments present)%n");
            System.out.printf("[DIAG] LLM worker did not complete within 30s — check Claude CLI / Vertex config%n");
            throw new AssertionError("Commentary LLM worker did not produce an event within 30s. " +
                                     "Moments were detected, trigger was built, but the engine worker (Claude CLI via Vertex) " +
                                     "did not complete. Check: claude CLI auth, CLAUDE_CODE_USE_VERTEX, ANTHROPIC_VERTEX_PROJECT_ID");
        } finally {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        }
    }

    @Test
    void reset_clears_state_and_commentary_fires_again() throws Exception {
        // Phase 1: produce commentary
        var broker = io.quarkus.arc.Arc.container()
                                       .instance(io.quarkmind.agent.ScoutingIntelBroker.class).get();
        var LEVEL_1 = new io.casehub.blocks.summarisation.EventLevel("intel", 1);
        broker.level1Bus().publish(new io.casehub.blocks.summarisation.LevelEvent<>(
                new io.quarkmind.agent.plugin.ScoutingIntelPayload.ThreatPosition(
                        new io.quarkmind.domain.Point2d(50, 50)), 100, LEVEL_1, "default"));

        orchestrator.gameTick();
        scenarioRunner.run("spawn-enemy-attack");
        orchestrator.gameTick();

        var ctx1 = orchestrator.getLastTickResult().caseContext();
        var moments1 = ctx1.getList(io.quarkmind.agent.QuarkMindCaseFile.MOMENTS_LATEST,
                                    io.quarkmind.plugin.summarisation.GameMoment.class);
        assertThat(moments1).as("first run should produce moments").isNotEmpty();

        // Phase 2: simulate reset — stopGame + startGame fires GameStarted which resets all state
        orchestrator.stopGame();
        simulatedGame.reset();
        orchestrator.startGame();

        // Phase 3: re-publish intel and tick again
        broker.level1Bus().publish(new io.casehub.blocks.summarisation.LevelEvent<>(
                new io.quarkmind.agent.plugin.ScoutingIntelPayload.ThreatPosition(
                        new io.quarkmind.domain.Point2d(50, 50)), 100, LEVEL_1, "default"));

        orchestrator.gameTick();
        scenarioRunner.run("spawn-enemy-attack");
        orchestrator.gameTick();

        var ctx2 = orchestrator.getLastTickResult().caseContext();
        var moments2 = ctx2.getList(io.quarkmind.agent.QuarkMindCaseFile.MOMENTS_LATEST,
                                    io.quarkmind.plugin.summarisation.GameMoment.class);
        assertThat(moments2).as("after reset, moments should fire again (state cleared)")
                            .isNotEmpty();
    }


    private URI wsEndpoint() {
        return URI.create(wsUri.toString().replace("http://", "ws://"));
    }
}

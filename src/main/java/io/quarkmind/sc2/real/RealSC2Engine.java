package io.quarkmind.sc2.real;

import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkmind.domain.GameState;
import io.quarkmind.sc2.SC2Engine;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Real SC2 engine — delegates to {@link QuarkusSC2Transport} for all game lifecycle
 * operations. Active only in the {@code %sc2} profile.
 *
 * <p>The SC2Engine contract:
 * <ul>
 *   <li>{@link #tick()} — no-op; the transport's game loop owns the game clock.</li>
 *   <li>{@link #dispatch()} — no-op; {@link SC2BotAgent#onStep} dispatches from the loop.</li>
 *   <li>{@link #observe()} — returns the latest {@link GameState} stored by SC2BotAgent.</li>
 *   <li>{@link #isConnected()} — delegates to {@link QuarkusSC2Transport#isRunning()}.</li>
 * </ul>
 *
 * <p>Config properties (sc2.port, map, difficulty, ai.race, race, retry) are owned by the
 * transport. {@code RealSC2Engine} is a thin lifecycle coordinator.
 */
@IfBuildProfile("sc2")
@ApplicationScoped
public class RealSC2Engine implements SC2Engine {

    private static final Logger log = Logger.getLogger(RealSC2Engine.class);

    @Inject QuarkusSC2Transport transport;
    @Inject SC2BotAgent         botAgent;

    // --- Lifecycle ---

    @Override
    @Fallback(fallbackMethod = "connectFallback")
    public void connect() {
        log.info("[SC2] Connecting to StarCraft II...");
        transport.connect();
        log.info("[SC2] Connected — transport ready");
    }

    public void connectFallback() {
        log.error("[SC2] Failed to connect after retries — bot will run without SC2");
    }

    @Override
    public void joinGame() {
        transport.createGame();
        transport.joinGame();
        transport.runGameLoop(botAgent);
        log.info("[SC2] Game loop started");
    }

    @Override
    public void leaveGame() {
        transport.quit();
        log.info("[SC2] Left game");
    }

    @Override
    public boolean isConnected() {
        // Delegates to transport.running — set false first in finally block on any exit.
        // This causes AgentOrchestrator.gameTick() to stop dispatching immediately.
        return transport.isRunning();
    }

    // --- Per-tick: no-ops for real SC2 ---

    /** No-op — the game loop virtual thread owns the SC2 game clock. */
    @Override
    public void tick() {}

    /** Polls the GameState stored by the most recent {@link SC2BotAgent#onStep} call. */
    @Override
    public GameState observe() {
        GameState state = botAgent.getLatestGameState();
        if (state == null) {
            log.debug("[SC2] No observation yet — first frame pending");
            return emptyState();
        }
        return state;
    }

    /** No-op — {@link SC2BotAgent#onStep} dispatches actions from within the game loop. */
    @Override
    public void dispatch() {}

    // --- Extension point for SC2DebugScenarioRunner ---

    /** Returns the bot agent. Used by {@link SC2DebugScenarioRunner} to enqueue debug commands. */
    public SC2BotAgent getBotAgent() {
        return botAgent;
    }

    // --- Helpers ---

    private static GameState emptyState() {
        return new GameState(0, 0, 0, 0,
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 0L);
    }
}

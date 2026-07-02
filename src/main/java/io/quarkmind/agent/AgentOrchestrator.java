package io.quarkmind.agent;

import io.casehub.api.context.CaseContext;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import io.quarkmind.sc2.GameResult;
import io.quarkmind.sc2.GameStarted;
import io.quarkmind.sc2.GameStopped;
import io.quarkmind.sc2.SC2Engine;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@ApplicationScoped
public class AgentOrchestrator {
    private static final Logger log = Logger.getLogger(AgentOrchestrator.class);

    @Inject SC2Engine engine;
    @Inject GameTickExecutor tickExecutor;
    @Inject QuarkMindCaseHub caseHub;
    @Inject GameStateTranslator translator;
    @Inject Event<GameStarted> gameStartedEvent;
    @Inject Event<GameStopped> gameStoppedEvent;
    @Inject GameSession gameSession;

    /**
     * Per-phase timing from the most recent completed tick.
     * Written after every gameTick(); read by GameLoopBenchmarkTest.
     */
    public record TickTimings(long physicsMs, long pluginsMs, long dispatchMs, long brokerMs) {
        public long totalMs() { return physicsMs + pluginsMs + dispatchMs + brokerMs; }
    }

    public record TickResult(CaseContext caseContext, AgentOrchestrator.TickTimings timings) {
        public boolean solveSucceeded() { return caseContext != null; }
    }

    private final AtomicReference<TickResult> lastTickResult = new AtomicReference<>();

    private volatile boolean schedulerPaused    = false;
    private volatile int     speedMultiplier     = 1;

    // Game-end detection — see design spec §6 for full threading model rationale.
    // gameActive: armed in startGame(); CAS'd to false exactly once per game by fireGameStoppedOnce().
    //   AtomicBoolean because stopGame() competes from a different thread than gameTick().
    // engineWasConnected: tracks the connected→disconnected transition for natural game-end detection.
    //   Only written/read by the scheduler thread (SKIP concurrent execution) except for stopGame(),
    //   which writes it to block the natural-end detection window before leaveGame().
    //   volatile is sufficient — no need for atomic CAS on this flag.
    private final AtomicBoolean gameActive          = new AtomicBoolean(false);
    private volatile boolean    engineWasConnected  = false;

    public void pauseScheduler()  { schedulerPaused = true; }
    public void resumeScheduler() { schedulerPaused = false; }
    public boolean isSchedulerPaused() { return schedulerPaused; }
    public void setSpeedMultiplier(int x) { speedMultiplier = Math.max(0, Math.min(8, x)); }
    public int getSpeedMultiplier() { return speedMultiplier; }

    public TickResult getLastTickResult() { return lastTickResult.get(); }

    /** Backward compatibility for GameLoopBenchmarkTest. */
    public TickTimings getLastTickTimings() {
        TickResult r = lastTickResult.get();
        return r != null ? r.timings() : null;
    }

    public void startGame() {
        gameActive.set(true);          // arm before connect — game lifecycle starts here
        engineWasConnected = false;    // reset for new game (defensive; handles continuous loop)
        gameSession.reset();
        engine.connect();
        engine.joinGame();

        // Create a durable engine case for this game — the case ID becomes the game session ID.
        // Called after connect/joinGame so observe() returns the initial game state.
        try {
            Map<String, Object> initialData = translator.toMap(engine.observe());
            UUID caseId = caseHub.startCase(initialData).toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
            gameSession.setCaseId(caseId);
            log.infof("Engine case started: %s", caseId);
        } catch (Exception e) {
            log.errorf(e, "Failed to start engine case — falling back to local session ID");
            // Game continues with the locally-generated session ID from reset()
        }

        gameStartedEvent.fire(new GameStarted());
        log.info("Game started");
    }

    public void stopGame() {
        engineWasConnected = false;                   // close natural-end detection window first
        engine.leaveGame();                           // interrupt the game loop

        // Cancel the engine case — safe even if startCase failed (gameSession.id is still valid)
        try {
            caseHub.cancelCase(gameSession.id());
            log.infof("Engine case cancelled: %s", gameSession.id());
        } catch (Exception e) {
            log.warnf("Engine case cancel failed for %s: %s", gameSession.id(), e.getMessage());
        }

        fireGameStoppedOnce(GameResult.UNKNOWN);      // manual stop — outcome unresolved
        log.info("Game stopped");
    }

    @Scheduled(every = "${starcraft.tick.interval:500ms}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void gameTick() {
        if (schedulerPaused) return;
        if (engine.isConnected()) {
            engineWasConnected = true;                          // game is running
            lastTickResult.set(tickExecutor.execute());
        } else if (engineWasConnected) {
            engineWasConnected = false;
            fireGameStoppedOnce(engine.lastOutcome());          // natural game end
        }
    }

    /**
     * Fires {@link GameStopped} exactly once per game lifecycle.
     * Uses a CAS so both the natural-end path (gameTick) and manual-stop path (stopGame)
     * can compete safely — only the first to win the CAS fires the event.
     */
    private void fireGameStoppedOnce(GameResult result) {
        if (gameActive.compareAndSet(true, false)) {
            gameStoppedEvent.fire(new GameStopped(result));
        }
    }
}

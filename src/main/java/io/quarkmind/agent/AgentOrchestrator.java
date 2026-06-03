package io.quarkmind.agent;

import io.casehub.core.CaseFile;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import io.quarkmind.sc2.GameStarted;
import io.quarkmind.sc2.GameStopped;
import io.quarkmind.sc2.SC2Engine;
import org.jboss.logging.Logger;

import java.util.concurrent.atomic.AtomicReference;

@ApplicationScoped
public class AgentOrchestrator {
    private static final Logger log = Logger.getLogger(AgentOrchestrator.class);

    @Inject SC2Engine engine;
    @Inject GameTickExecutor tickExecutor;
    @Inject Event<GameStarted> gameStartedEvent;
    @Inject Event<GameStopped> gameStoppedEvent;

    /**
     * Per-phase timing from the most recent completed tick.
     * Written after every gameTick(); read by GameLoopBenchmarkTest.
     */
    public record TickTimings(long physicsMs, long pluginsMs, long dispatchMs) {
        public long totalMs() { return physicsMs + pluginsMs + dispatchMs; }
    }

    public record TickResult(CaseFile caseFile, AgentOrchestrator.TickTimings timings) {
        public boolean solveSucceeded() { return caseFile != null; }
    }

    private final AtomicReference<TickResult> lastTickResult = new AtomicReference<>();

    private volatile boolean schedulerPaused = false;
    private volatile int speedMultiplier = 1;

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
        engine.connect();
        engine.joinGame();
        gameStartedEvent.fire(new GameStarted());
        log.info("Game started");
    }

    public void stopGame() {
        engine.leaveGame();
        gameStoppedEvent.fire(new GameStopped());
        log.info("Game stopped");
    }

    @Scheduled(every = "${starcraft.tick.interval:500ms}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void gameTick() {
        if (schedulerPaused) return;
        if (!engine.isConnected()) return;
        lastTickResult.set(tickExecutor.execute());
    }
}

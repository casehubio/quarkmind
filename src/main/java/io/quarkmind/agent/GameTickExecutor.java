package io.quarkmind.agent;

import io.casehub.coordination.CaseEngine;
import io.casehub.core.CaseFile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkmind.sc2.SC2Engine;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Map;

@ApplicationScoped
class GameTickExecutor {

    private static final Logger log = Logger.getLogger(GameTickExecutor.class);

    @Inject SC2Engine           engine;
    @Inject GameStateTranslator translator;
    @Inject CaseEngine          caseEngine;

    AgentOrchestrator.TickResult execute() {
        long t0 = System.currentTimeMillis();
        engine.tick();
        var gameState = engine.observe();
        long t1 = System.currentTimeMillis();

        Map<String, Object> caseData = translator.toMap(gameState);
        CaseFile caseFile = null;
        try {
            caseFile = caseEngine.createAndSolve("starcraft-game", caseData, Duration.ofSeconds(5));
        } catch (Exception e) {
            log.errorf("CaseEngine decision cycle failed at frame %d: %s",
                       gameState.gameFrame(), e.getMessage());
        }
        long t2 = System.currentTimeMillis();

        // dispatch() reads IntentQueue (plugin-populated), not CaseFile — safe to call even on failed solve
        engine.dispatch();
        long t3 = System.currentTimeMillis();

        var timings = new AgentOrchestrator.TickTimings(t1 - t0, t2 - t1, t3 - t2);
        log.debugf("Tick %d — physics=%dms plugins=%dms dispatch=%dms total=%dms | minerals=%d supply=%d/%d",
            gameState.gameFrame(), timings.physicsMs(), timings.pluginsMs(),
            timings.dispatchMs(), timings.totalMs(),
            gameState.minerals(), gameState.supplyUsed(), gameState.supply());
        return new AgentOrchestrator.TickResult(caseFile, timings);
    }
}

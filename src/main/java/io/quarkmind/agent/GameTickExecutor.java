package io.quarkmind.agent;

import io.casehub.coordination.CaseEngine;
import io.casehub.core.CaseFile;
import io.quarkmind.agent.plugin.SummarisationTickable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkmind.sc2.SC2Engine;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Map;

@ApplicationScoped
class GameTickExecutor {

    private static final Logger log = Logger.getLogger(GameTickExecutor.class);

    @Inject SC2Engine              engine;
    @Inject GameStateTranslator    translator;
    @Inject CaseEngine             caseEngine;
    @Inject PluginDispatchBroker   pluginDispatchBroker;
    @Inject SummarisationTickable  summarisationLifecycle;

    AgentOrchestrator.TickResult execute() {
        long t0 = System.currentTimeMillis();
        engine.tick();
        var gameState = engine.observe();
        long t1 = System.currentTimeMillis();        // physics end: engine.tick + observe

        Map<String, Object> caseData = translator.toMap(gameState);
        pluginDispatchBroker.recordTick(caseData);   // commitment signals before engine
        long t1b = System.currentTimeMillis();       // broker end: toMap + recordTick

        CaseFile caseFile = null;
        try {
            caseFile = caseEngine.createAndSolve("starcraft-game", caseData, Duration.ofSeconds(5));
        } catch (Exception e) {
            log.errorf("CaseEngine decision cycle failed at frame %d: %s",
                       gameState.gameFrame(), e.getMessage());
        }
        long t2 = System.currentTimeMillis();        // plugins end: createAndSolve

        // Summarisation: tick L2→L3 and L3→L4 runners (after CaseEngine, before dispatch)
        summarisationLifecycle.tick(gameState.gameFrame());

        // dispatch() reads IntentQueue (plugin-populated), not CaseFile — safe even on failed solve
        engine.dispatch();
        long t3 = System.currentTimeMillis();        // dispatch end

        var timings = new AgentOrchestrator.TickTimings(t1 - t0, t2 - t1b, t3 - t2, t1b - t1);
        log.debugf("Tick %d — physics=%dms broker=%dms plugins=%dms dispatch=%dms total=%dms | minerals=%d supply=%d/%d",
            gameState.gameFrame(), timings.physicsMs(), timings.brokerMs(),
            timings.pluginsMs(), timings.dispatchMs(), timings.totalMs(),
            gameState.minerals(), gameState.supplyUsed(), gameState.supply());
        return new AgentOrchestrator.TickResult(caseFile, timings);
    }
}

package io.quarkmind.agent;

import io.casehub.api.context.CaseContext;
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
    private static final Duration TICK_TIMEOUT = Duration.ofSeconds(5);

    @Inject SC2Engine              engine;
    @Inject GameStateTranslator    translator;
    @Inject QuarkMindCaseHub       caseHub;
    @Inject GameSession            gameSession;
    @Inject PluginDispatchBroker   pluginDispatchBroker;
    @Inject SummarisationTickable  summarisationLifecycle;
    @Inject DeferredAdvisoryEvaluator deferredAdvisoryEvaluator;

    AgentOrchestrator.TickResult execute() {
        long t0 = System.currentTimeMillis();
        engine.tick();
        var gameState = engine.observe();
        long t1 = System.currentTimeMillis();        // physics end: engine.tick + observe

        Map<String, Object> caseData = translator.toMap(gameState);
        pluginDispatchBroker.recordTick(caseData);   // commitment signals before engine
        long t1b = System.currentTimeMillis();       // broker end: toMap + recordTick

        CaseContext ctx = null;
        try {
            ctx = caseHub.signalAndAwaitSync(gameSession.id(), caseData, TICK_TIMEOUT);
        } catch (Exception e) {
            log.errorf("Engine signal+settle failed at frame %d: %s",
                       gameState.gameFrame(), e.getMessage());
        }
        long t2 = System.currentTimeMillis();        // plugins end: signalAndAwaitSync

        // Summarisation: tick L2→L3 and L3→L4 runners (after engine settle, before dispatch)
        summarisationLifecycle.tick(gameState.gameFrame());

        // Deferred advisory evaluation: compare mature advisories against current game state
        if (ctx != null) {
            deferredAdvisoryEvaluator.evaluate(ctx, gameState.gameFrame());
        }

        // Advisory trigger: fire-and-forget signal for advisory Workers
        if (ctx != null) {
            Map<String, Object> triggers = AdvisoryTriggerBuilder.buildTriggers(ctx, gameState.gameFrame());
            if (!triggers.isEmpty()) {
                caseHub.signal(gameSession.id(), triggers)
                    .exceptionally(ex -> {
                        log.warnf("Advisory signal failed at frame %d: %s", gameState.gameFrame(), ex.getMessage());
                        return null;
                    });
            }
        }

        // dispatch() reads IntentQueue (plugin-populated), not CaseContext — safe even on failed settle
        engine.dispatch();
        long t3 = System.currentTimeMillis();        // dispatch end

        var timings = new AgentOrchestrator.TickTimings(t1 - t0, t2 - t1b, t3 - t2, t1b - t1);
        log.debugf("Tick %d — physics=%dms broker=%dms plugins=%dms dispatch=%dms total=%dms | minerals=%d supply=%d/%d",
            gameState.gameFrame(), timings.physicsMs(), timings.brokerMs(),
            timings.pluginsMs(), timings.dispatchMs(), timings.totalMs(),
            gameState.minerals(), gameState.supplyUsed(), gameState.supply());
        return new AgentOrchestrator.TickResult(ctx, timings);
    }
}

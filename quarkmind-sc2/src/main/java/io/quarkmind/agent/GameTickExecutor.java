package io.quarkmind.agent;

import io.casehub.api.context.CaseContext;
import io.quarkmind.agent.cbr.TimelineSampler;
import io.quarkmind.agent.plugin.SummarisationTickable;
import io.quarkmind.plugin.coaching.CoachingComplianceEvaluator;
import io.quarkmind.plugin.coaching.CoachingTriggerBuilder;
import io.quarkmind.plugin.commentary.CommentaryAccumulator;
import io.quarkmind.plugin.commentary.CommentaryTriggerBuilder;
import io.quarkmind.plugin.commentary.NarrativeContextHolder;
import io.quarkmind.sc2.SC2Engine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
class GameTickExecutor {

    private static final Logger   log          = Logger.getLogger(GameTickExecutor.class);
    private static final Duration TICK_TIMEOUT = Duration.ofSeconds(5);

    @Inject
    SC2Engine                        engine;
    @Inject
    GameStateTranslator              translator;
    @Inject
    QuarkMindCaseHub                 caseHub;
    @Inject
    GameSession                      gameSession;
    @Inject
    PluginDispatchBroker             pluginDispatchBroker;
    @Inject
    SummarisationTickable            summarisationLifecycle;
    @Inject
    DeferredAdvisoryEvaluator        deferredAdvisoryEvaluator;
    @Inject
    MilestoneOutcomeRecorder         milestoneOutcomeRecorder;
    @Inject
    AdvisoryMilestoneOutcomeRecorder advisoryMilestoneOutcomeRecorder;

    @Inject
    CommentaryTriggerBuilder    commentaryTriggerBuilder;
    @Inject
    CommentaryAccumulator       commentaryAccumulator;
    @Inject
    NarrativeContextHolder narrativeContextHolder;

    @Inject
    CoachingTriggerBuilder      coachingTriggerBuilder;
    @Inject
    CoachingComplianceEvaluator coachingComplianceEvaluator;
    @Inject
    io.quarkmind.plugin.commentary.InlineCommentaryDispatcher inlineCommentaryDispatcher;

    @Inject
    TimelineSampler             timelineSampler;


    @ConfigProperty(name = "quarkmind.game.mode", defaultValue = "ai")
    String gameMode;
    @ConfigProperty(name = "quarkmind.replay.sync.mode", defaultValue = "none")
    String replaySyncMode;

    @ConfigProperty(name = "quarkmind.replay.sync.timeout-seconds", defaultValue = "15")
    int replaySyncTimeoutSeconds;
    private volatile String syncModeOverride;

    public void setSyncMode(String mode) {
        this.syncModeOverride = mode;
    }

    public String getSyncMode() {
        String override = syncModeOverride;
        return override != null ? override : replaySyncMode;
    }

    public int getSyncTimeoutSeconds() {
        return replaySyncTimeoutSeconds;
    }


    AgentOrchestrator.TickResult execute() {return execute(1);}

    AgentOrchestrator.TickResult execute(int speed) {
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < speed - 1; i++) {
            engine.tick();
        }
        engine.tick();
        var gameState = engine.observe();
        timelineSampler.tick(gameState);
        long t1 = System.currentTimeMillis();

        Map<String, Object> caseData = translator.toMap(gameState);
        caseData = new HashMap<>(caseData);
        caseData.put(QuarkMindCaseFile.GAME_MODE, gameMode);
        try {pluginDispatchBroker.recordTick(caseData);} catch (Exception | Error e) {
            log.debugf("Broker recordTick skipped: %s", e.getMessage());
        }
        long t1b = System.currentTimeMillis();

        CaseContext ctx = null;
        try {
            ctx = caseHub.signalAndAwaitSync(gameSession.id(), caseData, TICK_TIMEOUT);
        } catch (Exception | Error e) {
            log.warnf("Engine signal+settle failed at frame %d: %s",
                      gameState.gameFrame(), e.getMessage());
        }
        long t2 = System.currentTimeMillis();

        narrativeContextHolder.updateCbr(ctx);
        summarisationLifecycle.tick(gameState.gameFrame());
        Map<String, Object> narrativeTriggers = commentaryAccumulator.tick(gameState.gameFrame());

        boolean isCoachMode = "coach".equals(gameMode);

        if (!isCoachMode) {
            milestoneOutcomeRecorder.evaluateMilestones(gameState);
            advisoryMilestoneOutcomeRecorder.evaluateMilestones(gameState);
        }

        if (ctx != null && !isCoachMode) {
            deferredAdvisoryEvaluator.evaluate(ctx, gameState.gameFrame());
        }

        if (isCoachMode) {
            coachingComplianceEvaluator.evaluate(gameState, gameState.gameFrame());
        }

        // Reactive commentary — sync or async based on mode
        if (ctx != null) {
            Map<String, Object> reactiveTriggers = commentaryTriggerBuilder.build(ctx, gameState.gameFrame());
            if (!reactiveTriggers.isEmpty() && inlineCommentaryDispatcher.isAvailable()) {
                if (syncReactive()) {
                    inlineCommentaryDispatcher.executeWithTimeout(
                            reactiveTriggers, io.quarkmind.plugin.commentary.CommentaryType.REACTIVE, replaySyncTimeoutSeconds);
                } else {
                    inlineCommentaryDispatcher.executeAsync(reactiveTriggers, io.quarkmind.plugin.commentary.CommentaryType.REACTIVE);
                }
            }
        }

        // Narrative commentary — inline dispatch (sync or async based on mode)
        if (!narrativeTriggers.isEmpty() && inlineCommentaryDispatcher.isAvailable()) {
            if (syncNarrative()) {
                inlineCommentaryDispatcher.executeWithTimeout(
                        narrativeTriggers, io.quarkmind.plugin.commentary.CommentaryType.NARRATIVE, replaySyncTimeoutSeconds);
            } else {
                inlineCommentaryDispatcher.executeAsync(narrativeTriggers, io.quarkmind.plugin.commentary.CommentaryType.NARRATIVE);
            }
        }

        // Advisory trigger: fire-and-forget signal — only in AI mode
        if (ctx != null && !isCoachMode) {
            Map<String, Object> triggers = AdvisoryTriggerBuilder.buildTriggers(ctx, gameState.gameFrame());
            if (!triggers.isEmpty()) {
                try {
                    caseHub.signal(gameSession.id(), triggers);
                } catch (Exception ex) {
                    log.warnf("Advisory signal failed at frame %d: %s", gameState.gameFrame(), ex.getMessage());
                }
            }
        }

        // Coaching trigger: fire-and-forget signal — only in coach mode
        if (ctx != null && isCoachMode) {
            Map<String, Object> coachingTriggers = coachingTriggerBuilder.build(ctx, gameState.gameFrame());
            if (!coachingTriggers.isEmpty()) {
                try {
                    caseHub.signal(gameSession.id(), coachingTriggers);
                } catch (Exception ex) {
                    log.warnf("Coaching trigger failed at frame %d: %s", gameState.gameFrame(), ex.getMessage());
                }
            }
        }

        engine.dispatch();
        long t3 = System.currentTimeMillis();

        var timings = new AgentOrchestrator.TickTimings(t1 - t0, t2 - t1b, t3 - t2, t1b - t1);
        log.debugf("Tick %d — physics=%dms broker=%dms plugins=%dms dispatch=%dms total=%dms | minerals=%d supply=%d/%d",
                   gameState.gameFrame(), timings.physicsMs(), timings.brokerMs(),
                   timings.pluginsMs(), timings.dispatchMs(), timings.totalMs(),
                   gameState.minerals(), gameState.supplyUsed(), gameState.supply());
        return new AgentOrchestrator.TickResult(ctx, timings);
    }

    private boolean syncReactive() {
        String mode = getSyncMode();
        return "full".equals(mode) || "reactive-only".equals(mode);
    }

    private boolean syncNarrative() {
        return "full".equals(getSyncMode());
    }
}

package io.quarkmind.agent;

import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.ledger.api.model.OutcomeRecord;
import io.casehub.ledger.api.spi.OutcomeRecorder;
import io.quarkmind.agency.milestone.MilestoneSession;
import io.quarkmind.domain.GameState;
import io.quarkmind.sc2.GameResult;
import io.quarkmind.sc2.GameStarted;
import io.quarkmind.sc2.GameStopped;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

@ApplicationScoped
public class MilestoneOutcomeRecorder {

    private static final Logger log = Logger.getLogger(MilestoneOutcomeRecorder.class);

    private final OutcomeRecorder                              outcomeRecorder;
    private final io.quarkmind.agent.cbr.SC2StrategyRouterTask strategyRouter;
    private final GameSession       gameSession;
    private final MilestoneSession  milestoneSession;
    private final DominanceAssessor dominanceAssessor;
    private final List<MilestoneTrigger>                       triggers;
    private final boolean                                      milestonesEnabled;
    private final double                                       deadZoneThreshold;

    private final List<StrategySpan> strategySpans = new java.util.ArrayList<>();
    private       long               lastSeenFrame;

    record StrategySpan(String strategyId, long startFrame) {}

    @Inject
    MilestoneOutcomeRecorder(
            OutcomeRecorder outcomeRecorder,
            io.quarkmind.agent.cbr.SC2StrategyRouterTask strategyRouter,
            GameSession gameSession,
            MilestoneSession milestoneSession,
            DominanceAssessor dominanceAssessor,
            @Any Instance<MilestoneTrigger> triggerInstances,
            MilestoneConfig config) {
        this.outcomeRecorder   = outcomeRecorder;
        this.strategyRouter    = strategyRouter;
        this.gameSession       = gameSession;
        this.milestoneSession  = milestoneSession;
        this.dominanceAssessor = dominanceAssessor;
        this.triggers          = triggerInstances.stream().toList();
        this.milestonesEnabled = config.enabled();
        this.deadZoneThreshold = config.deadZoneThreshold();
    }

    MilestoneOutcomeRecorder(
            OutcomeRecorder outcomeRecorder,
            io.quarkmind.agent.cbr.SC2StrategyRouterTask strategyRouter,
            GameSession gameSession,
            MilestoneSession milestoneSession,
            DominanceAssessor dominanceAssessor,
            List<MilestoneTrigger> triggers,
            boolean milestonesEnabled,
            double deadZoneThreshold) {
        this.outcomeRecorder   = outcomeRecorder;
        this.strategyRouter    = strategyRouter;
        this.gameSession       = gameSession;
        this.milestoneSession  = milestoneSession;
        this.dominanceAssessor = dominanceAssessor;
        this.triggers          = triggers;
        this.milestonesEnabled = milestonesEnabled;
        this.deadZoneThreshold = deadZoneThreshold;
    }

    void onGameStarted(@Observes GameStarted event) {
        milestoneSession.reset();
        strategySpans.clear();
        lastSeenFrame = 0;
    }

    void onStrategySelected(@Observes io.quarkmind.agent.cbr.StrategySelectionPublished event) {
        strategySpans.add(new StrategySpan(event.strategyId(), event.gameFrame()));
    }

    public void evaluateMilestones(GameState state) {
        lastSeenFrame = state.gameFrame();
        if (!milestonesEnabled) {return;}

        log.debugf("[MILESTONE] SPI not available — milestone evaluation skipped at frame %d", state.gameFrame());
    }

    void onGameStopped(@Observes GameStopped event) {
        if (event.result() == GameResult.UNKNOWN) {
            log.infof("[MILESTONE] Game ended with unknown result — skipped (strategy=%s)",
                      strategyRouter.lastSelectedId());
            return;
        }

        String context = "strategy";
        AttestationVerdict verdict = switch (event.result()) {
            case WIN -> AttestationVerdict.ENDORSED;
            case LOSS -> AttestationVerdict.CHALLENGED;
            case TIE -> AttestationVerdict.SOUND;
            case UNKNOWN -> throw new AssertionError("unreachable — guarded above");
        };

        if (strategySpans.size() <= 1) {
            String strategyId = strategySpans.size() == 1
                                ? strategySpans.getFirst().strategyId()
                                : strategyRouter.lastSelectedId();
            outcomeRecorder.record(OutcomeRecord.of(strategyId, gameSession.id(), context, verdict, 1.0));
            log.infof("[MILESTONE] Recorded game-end: strategy=%s result=%s verdict=%s confidence=1.0",
                      strategyId, event.result(), verdict);
        } else {
            long totalFrames = lastSeenFrame;
            for (int i = 0; i < strategySpans.size(); i++) {
                StrategySpan span = strategySpans.get(i);
                long endFrame = (i + 1 < strategySpans.size())
                                ? strategySpans.get(i + 1).startFrame()
                                : totalFrames;
                double proportion = totalFrames > 0
                                    ? (double) (endFrame - span.startFrame()) / totalFrames
                                    : 1.0 / strategySpans.size();
                outcomeRecorder.record(OutcomeRecord.of(
                        span.strategyId(), gameSession.id(), context, verdict, proportion));
                log.infof("[MILESTONE] Recorded proportional game-end: strategy=%s result=%s verdict=%s confidence=%.3f (frames %d-%d of %d)",
                          span.strategyId(), event.result(), verdict, proportion, span.startFrame(), endFrame, totalFrames);
            }
        }
    }
}

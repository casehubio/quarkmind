package io.quarkmind.agent;

import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.ledger.api.model.OutcomeRecord;
import io.casehub.ledger.api.spi.OutcomeRecorder;
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

    private final OutcomeRecorder outcomeRecorder;
    private final io.quarkmind.agent.cbr.SC2StrategyRouterTask strategyRouter;
    private final GameSession gameSession;
    private final MilestoneSession milestoneSession;
    private final DominanceAssessor dominanceAssessor;
    private final List<MilestoneTrigger> triggers;
    private final boolean milestonesEnabled;
    private final double deadZoneThreshold;

    @Inject
    MilestoneOutcomeRecorder(
            OutcomeRecorder outcomeRecorder,
            io.quarkmind.agent.cbr.SC2StrategyRouterTask strategyRouter,
            GameSession gameSession,
            MilestoneSession milestoneSession,
            DominanceAssessor dominanceAssessor,
            @Any Instance<MilestoneTrigger> triggerInstances,
            MilestoneConfig config) {
        this.outcomeRecorder = outcomeRecorder;
        this.strategyRouter = strategyRouter;
        this.gameSession = gameSession;
        this.milestoneSession = milestoneSession;
        this.dominanceAssessor = dominanceAssessor;
        this.triggers = triggerInstances.stream().toList();
        this.milestonesEnabled = config.enabled();
        this.deadZoneThreshold = config.deadZoneThreshold();
    }

    /** Test constructor — no CDI. */
    MilestoneOutcomeRecorder(
            OutcomeRecorder outcomeRecorder,
            io.quarkmind.agent.cbr.SC2StrategyRouterTask strategyRouter,
            GameSession gameSession,
            MilestoneSession milestoneSession,
            DominanceAssessor dominanceAssessor,
            List<MilestoneTrigger> triggers,
            boolean milestonesEnabled,
            double deadZoneThreshold) {
        this.outcomeRecorder = outcomeRecorder;
        this.strategyRouter = strategyRouter;
        this.gameSession = gameSession;
        this.milestoneSession = milestoneSession;
        this.dominanceAssessor = dominanceAssessor;
        this.triggers = triggers;
        this.milestonesEnabled = milestonesEnabled;
        this.deadZoneThreshold = deadZoneThreshold;
    }

    void onGameStarted(@Observes GameStarted event) {
        milestoneSession.reset();
    }

    public void evaluateMilestones(GameState state) {
        if (!milestonesEnabled) return;

        // SPI gate: milestone recording requires AttestingOutcomeRecorder (engine#648)
        // Without it, evaluateMilestones is a no-op — game-end records via record() only.
        // TODO: activate when engine#648 ships AttestingOutcomeRecorder sub-interface
        log.debugf("[MILESTONE] SPI not available — milestone evaluation skipped at frame %d", state.gameFrame());
    }

    void onGameStopped(@Observes GameStopped event) {
        if (event.result() == GameResult.UNKNOWN) {
            log.infof("[MILESTONE] Game ended with unknown result — skipped (strategy=%s)",
                strategyRouter.lastSelectedId());
            return;
        }
        String strategyId = strategyRouter.lastSelectedId();
        String context = "strategy";
        AttestationVerdict verdict = switch (event.result()) {
            case WIN     -> AttestationVerdict.ENDORSED;
            case LOSS    -> AttestationVerdict.CHALLENGED;
            case TIE     -> AttestationVerdict.SOUND;
            case UNKNOWN -> throw new AssertionError("unreachable — guarded above");
        };

        // If a milestone entry already exists for this strategy, append game-end attestation.
        // Otherwise, create entry with game-end outcome (identical to pre-milestone behavior).
        // Until engine#648: always creates via record() (no milestone entries exist).
        outcomeRecorder.record(OutcomeRecord.of(
            strategyId,
            gameSession.id(),
            context,
            verdict,
            1.0
        ));
        log.infof("[MILESTONE] Recorded game-end: strategy=%s context=%s result=%s verdict=%s",
            strategyId, context, event.result(), verdict);
    }
}

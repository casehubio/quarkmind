package io.quarkmind.agent;

import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.ledger.api.model.OutcomeRecord;
import io.casehub.ledger.api.spi.OutcomeRecorder;
import io.quarkmind.sc2.GameResult;
import io.quarkmind.sc2.GameStopped;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Records the strategy outcome at game end (L6).
 *
 * <p>Uses {@code @Observes} (synchronous) to capture {@link StrategySelector} state
 * before it can be reset by the next {@code startGame()} call — identical rationale
 * to {@link GameSession} not resetting in {@code stopGame()}.
 *
 * <p>WIN → ENDORSED (trust increases), LOSS → CHALLENGED (trust decreases),
 * TIE → SOUND (neutral). UNKNOWN is skipped — no ledger write.
 *
 * <p><b>Transactional dependency:</b> {@code OutcomeRecordSaveService.save()} is
 * {@code @Transactional}. {@code IncrementalTrustUpdateObserver} fires at
 * {@code TransactionPhase.AFTER_SUCCESS} of that transaction. Trust score updates are
 * silently dropped if {@code record()} is called from inside a rolled-back transaction.
 *
 * <p>Confidence 1.0: a completed game is a session-level outcome for the strategic
 * commitment made at game start (OutcomeRecord Javadoc: 0.1=tick, 0.7=game event, 1.0=session).
 */
@ApplicationScoped
public class GameOutcomeRecorder {

    private static final Logger log = Logger.getLogger(GameOutcomeRecorder.class);

    @Inject OutcomeRecorder  outcomeRecorder;
    @Inject StrategySelector strategySelector;
    @Inject GameSession      gameSession;

    void onGameStopped(@Observes GameStopped event) {
        if (event.result() == GameResult.UNKNOWN) {
            log.infof("[OUTCOME] Game ended with unknown result — skipped (strategy=%s context=%s)",
                strategySelector.getSelectedId(), strategySelector.getOpponentContext());
            return;
        }
        String strategyId = strategySelector.getSelectedId();
        String context    = strategySelector.getOpponentContext();
        AttestationVerdict verdict = switch (event.result()) {
            case WIN     -> AttestationVerdict.ENDORSED;
            case LOSS    -> AttestationVerdict.CHALLENGED;
            case TIE     -> AttestationVerdict.SOUND;
            case UNKNOWN -> throw new AssertionError("unreachable — guarded above");
        };
        outcomeRecorder.record(OutcomeRecord.of(
            strategyId,
            gameSession.id(),
            context,
            verdict,
            1.0
        ));
        log.infof("[OUTCOME] Recorded: strategy=%s context=%s result=%s verdict=%s",
            strategyId, context, event.result(), verdict);
    }
}

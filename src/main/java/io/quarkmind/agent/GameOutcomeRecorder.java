package io.quarkmind.agent;

import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.ledger.api.model.OutcomeRecord;
import io.casehub.ledger.api.spi.OutcomeRecorder;
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
 * <p>Records {@link AttestationVerdict#SOUND} for all games in L6 (real win/loss detection
 * deferred — see open issue in spec). With uniform SOUND verdicts, trust routing learns
 * nothing useful until real outcome signals are wired.
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
        String strategyId = strategySelector.getSelectedId();
        String context    = strategySelector.getOpponentContext();
        outcomeRecorder.record(OutcomeRecord.of(
            strategyId,
            gameSession.id(),
            context,
            AttestationVerdict.SOUND,
            1.0
        ));
        log.infof("[OUTCOME] Recorded: strategy=%s context=%s verdict=SOUND",
            strategyId, context);
    }
}

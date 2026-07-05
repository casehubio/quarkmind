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

import java.util.Set;

/**
 * Records advisory outcomes at game end.
 *
 * <p>Uses {@code @Observes} (synchronous) to capture {@link AdvisoryInvocationCounter} state
 * before it can be reset by the next {@code GameStarted} event — matching the same lifecycle
 * rationale as {@link MilestoneOutcomeRecorder}.
 *
 * <p>WIN → ENDORSED (trust increases), LOSS → CHALLENGED (trust decreases),
 * TIE → SOUND (neutral). UNKNOWN is skipped — no ledger write.
 *
 * <p>Dimension: {@code game-outcome} — advisory performance scoped to full game session.
 *
 * <p>Each invoked advisor receives one {@link OutcomeRecord} with flat weight 1.0
 * (equal attribution regardless of invocation frequency). Empty invocation set is skipped.
 *
 * <p>Confidence 1.0: a completed game is a session-level outcome for the advisory
 * commitments made during that game (OutcomeRecord Javadoc: 0.1=tick, 0.7=game event, 1.0=session).
 *
 * <p>Refs #180
 */
@ApplicationScoped
public class AdvisoryGameOutcomeRecorder {

    private static final Logger log = Logger.getLogger(AdvisoryGameOutcomeRecorder.class);

    private final OutcomeRecorder outcomeRecorder;
    private final AdvisoryInvocationCounter invocationCounter;
    private final GameSession gameSession;

    @Inject
    public AdvisoryGameOutcomeRecorder(
            OutcomeRecorder outcomeRecorder,
            AdvisoryInvocationCounter invocationCounter,
            GameSession gameSession) {
        this.outcomeRecorder = outcomeRecorder;
        this.invocationCounter = invocationCounter;
        this.gameSession = gameSession;
    }

    void onGameStopped(@Observes GameStopped event) {
        if (event.result() == GameResult.UNKNOWN) {
            log.infof("[ADVISORY-OUTCOME] Game ended with unknown result — skipped (advisors=%s)",
                invocationCounter.snapshot());
            return;
        }

        Set<String> invokedAdvisors = invocationCounter.snapshot();
        if (invokedAdvisors.isEmpty()) {
            log.infof("[ADVISORY-OUTCOME] No advisors invoked this game — skipped");
            return;
        }

        AttestationVerdict verdict = switch (event.result()) {
            case WIN     -> AttestationVerdict.ENDORSED;
            case LOSS    -> AttestationVerdict.CHALLENGED;
            case TIE     -> AttestationVerdict.SOUND;
            case UNKNOWN -> throw new AssertionError("unreachable — guarded above");
        };

        for (String advisorId : invokedAdvisors) {
            outcomeRecorder.record(OutcomeRecord.of(
                advisorId,
                gameSession.id(),
                "game-outcome",
                verdict,
                1.0
            ));
        }

        log.infof("[ADVISORY-OUTCOME] Recorded: advisors=%s result=%s verdict=%s",
            invokedAdvisors, event.result(), verdict);
    }
}

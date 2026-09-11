package io.quarkmind.qa.workbench;

import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.ledger.api.model.OutcomeRecord;
import io.casehub.ledger.api.spi.OutcomeRecorder;
import io.quarkmind.agent.GameSession;
import io.quarkus.arc.profile.UnlessBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@UnlessBuildProfile("prod")
@ApplicationScoped
public class CommentaryFeedbackHandler {

    @Inject OutcomeRecorder outcomeRecorder;
    @Inject GameSession gameSession;

    public void recordFeedback(String workerId, String dimension, boolean positive) {
        double score = positive ? 1.0 : 0.01;
        AttestationVerdict verdict = positive ? AttestationVerdict.ENDORSED : AttestationVerdict.CHALLENGED;
        outcomeRecorder.record(OutcomeRecord.of(
            workerId,
            gameSession.id(),
            dimension,
            verdict,
            score
        ));
    }
}

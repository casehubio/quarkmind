package io.quarkmind.agent;

import io.casehub.ledger.api.model.OutcomeRecord;
import io.casehub.ledger.api.spi.OutcomeRecorder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Async CDI observer that writes a LedgerEntry for every plugin decision transition.
 * Lives in agent/ (cross-cutting infrastructure, not a game plugin).
 *
 * OutcomeRecorder @DefaultBean is from casehub-ledger runtime; delegates to
 * InMemoryLedgerEntryRepository in mock/emulated/test/replay profiles.
 */
@ApplicationScoped
public class PluginOutcomeAuditor {

    private static final Logger log = Logger.getLogger(PluginOutcomeAuditor.class);

    @Inject
    OutcomeRecorder outcomeRecorder;

    public void onDecision(@ObservesAsync PluginDecisionEvent e) {
        outcomeRecorder.record(OutcomeRecord.of(
                e.actorId(),
                e.gameSessionId(),
                e.capabilityTag(),
                e.verdict(),
                0.7   // game-level decision scope per OutcomeRecord Javadoc
        ));
        log.debugf("Ledger: actor=%s capability=%s verdict=%s frame=%d",
                e.actorId(), e.capabilityTag(), e.verdict(), e.gameFrame());
    }
}

package io.quarkmind.agent;

import io.casehub.ledger.memory.InMemoryLedgerEntryRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import io.quarkmind.sc2.GameStopped;

/**
 * Bridges GameStopped to InMemoryLedgerEntryRepository.clear().
 *
 * clear() is not on the LedgerEntryRepository SPI, so the concrete type is
 * injected via @Any Instance<> — isUnsatisfied() guards the call so this is
 * a no-op when the JPA implementation is active (%sc2 profile).
 *
 * Follows the EconomicsLifecycle pattern (plugin/flow/EconomicsLifecycle.java).
 */
@ApplicationScoped
public class LedgerLifecycleAdapter {

    private static final Logger log = Logger.getLogger(LedgerLifecycleAdapter.class);

    @Inject @Any
    Instance<InMemoryLedgerEntryRepository> memoryLedger;

    void onGameStop(@Observes GameStopped event) {
        if (!memoryLedger.isUnsatisfied()) {
            memoryLedger.get().clear();
            log.debug("In-memory ledger cleared at game stop");
        }
    }
}

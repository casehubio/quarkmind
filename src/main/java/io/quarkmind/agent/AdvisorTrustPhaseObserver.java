package io.quarkmind.agent;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Observes trust phase transitions and fires {@link AdvisorExcludedEvent} when
 * an advisor transitions to EXCLUDED phase.
 *
 * <p><b>Placeholder implementation:</b> Currently logs only. Will be wired to the
 * actual trust phase transition event from casehub-ledger or casehub-engine once
 * that event is available.
 *
 * <p>The casehub-ledger trust scoring system tracks trust phases (EXPLORATORY,
 * PROVISIONAL, ESTABLISHED, EXCLUDED) and transitions advisors based on performance.
 * When the phase transition event is added to the engine, this observer will listen
 * for transitions to EXCLUDED and fire {@link AdvisorExcludedEvent} for downstream
 * monitoring and logging.
 *
 * <p>Refs #180
 */
@ApplicationScoped
public class AdvisorTrustPhaseObserver {

    private static final Logger log = Logger.getLogger(AdvisorTrustPhaseObserver.class);

    @Inject
    Event<AdvisorExcludedEvent> excludedEvent;

    /**
     * Placeholder method for future trust phase transition observation.
     *
     * <p>Once casehub-ledger or casehub-engine provides a trust phase transition event,
     * this method will be annotated with {@code @Observes TrustPhaseTransitionEvent}
     * and will fire {@link AdvisorExcludedEvent} when the new phase is EXCLUDED.
     *
     * <p>Example future implementation:
     * <pre>
     * void onPhaseTransition(@Observes TrustPhaseTransitionEvent event) {
     *     if (event.newPhase() == TrustPhase.EXCLUDED) {
     *         excludedEvent.fire(new AdvisorExcludedEvent(
     *             event.actorId(),
     *             event.capability()
     *         ));
     *         log.infof("[ADVISOR-EXCLUDED] Advisor excluded: id=%s capability=%s",
     *             event.actorId(), event.capability());
     *     }
     * }
     * </pre>
     */
    public void placeholder() {
        log.debug("AdvisorTrustPhaseObserver placeholder — awaiting trust phase transition event from engine");
    }
}

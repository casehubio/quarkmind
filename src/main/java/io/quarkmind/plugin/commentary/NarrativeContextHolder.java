package io.quarkmind.plugin.commentary;

import io.casehub.blocks.summarisation.EventStreamBus;
import io.quarkmind.plugin.summarisation.GameArc;
import io.quarkmind.plugin.summarisation.GamePhase;
import io.quarkmind.plugin.summarisation.SummarisationLifecycle;
import io.quarkmind.sc2.GameStarted;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.Map;

/**
 * Holds live L3/L4 context for snapshotting by {@link CommentaryAccumulator}.
 *
 * <p>Subscribes to the L3 phase bus and L4 arc bus at {@code @PostConstruct},
 * maintaining the latest {@link GamePhase} and {@link GameArc} as volatile fields.
 *
 * <p>When {@link CommentaryAccumulator} emits a narrative trigger, it calls
 * {@link #snapshot()} to capture the current strategic context. The snapshot is
 * written to the CaseFile trigger map — Workers read the frozen snapshot, NOT
 * live context (which may change during the 1–5s LLM call latency).
 *
 * <p>Context is cleared on {@link GameStarted}, but bus subscriptions persist
 * across games (application-scoped lifecycle).
 *
 * <p>Package-private constructor and setters enable testing without CDI.
 *
 * <p>Refs #181 Task 6
 */
@ApplicationScoped
public class NarrativeContextHolder {

    private volatile GamePhase latestPhase;
    private volatile GameArc latestArc;

    @Inject SummarisationLifecycle summarisationLifecycle;

    /** CDI constructor */
    public NarrativeContextHolder() {}

    /** Package-private constructor for testing without CDI */
    NarrativeContextHolder(EventStreamBus<GamePhase> phaseBus,
                           EventStreamBus<GameArc> arcBus) {
        this.phaseBus = phaseBus;
        this.arcBus = arcBus;
    }

    private EventStreamBus<GamePhase> phaseBus;
    private EventStreamBus<GameArc> arcBus;

    @PostConstruct
    void init() {
        if (phaseBus == null) {
            phaseBus = summarisationLifecycle.phaseBus();
        }
        if (arcBus == null) {
            arcBus = summarisationLifecycle.arcBus();
        }

        // Subscribe to L3 phase bus — captures latest phase
        phaseBus.subscribe(p -> true, event -> latestPhase = event.payload());

        // Subscribe to L4 arc bus — captures latest arc
        arcBus.subscribe(a -> true, event -> latestArc = event.payload());
    }

    /**
     * Snapshot current L3/L4 context for CaseFile serialization.
     *
     * <p>Returns a map with phase name, rationale, and arc narrative (when present).
     * Called by {@link CommentaryAccumulator} when a narrative window emits.
     *
     * @return map with "phase", "phase_rationale", "arc_narrative" keys (partial map if context null)
     */
    public Map<String, String> snapshot() {
        var snapshot = new HashMap<String, String>();
        if (latestPhase != null) {
            snapshot.put("phase", latestPhase.phase());
            snapshot.put("phase_rationale", latestPhase.rationale());
        }
        if (latestArc != null) {
            snapshot.put("arc_narrative", latestArc.narrative());
        }
        return snapshot;
    }

    /**
     * Clears context on game restart.
     *
     * <p>NOTE: Does NOT clear bus subscriptions — they persist across games
     * (application-scoped lifecycle).
     */
    void onGameStarted(@Observes GameStarted event) {
        latestPhase = null;
        latestArc = null;
    }

    /** Package-private accessor for testing */
    GamePhase latestPhase() { return latestPhase; }

    /** Package-private accessor for testing */
    GameArc latestArc() { return latestArc; }

    /** Package-private setter for testing without CDI */
    void setLatestPhase(GamePhase phase) { this.latestPhase = phase; }

    /** Package-private setter for testing without CDI */
    void setLatestArc(GameArc arc) { this.latestArc = arc; }
}

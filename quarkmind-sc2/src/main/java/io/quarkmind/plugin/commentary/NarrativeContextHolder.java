package io.quarkmind.plugin.commentary;

import io.casehub.blocks.summarisation.EventStreamBus;
import io.quarkmind.plugin.summarisation.GameArc;
import io.quarkmind.plugin.summarisation.SummarisationLifecycle;
import io.quarkmind.plugin.summarisation.TacticalPosture;
import io.casehub.api.context.CaseContext;
import io.quarkmind.agent.QuarkMindCaseFile;
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
 * maintaining the latest {@link TacticalPosture} and {@link GameArc} as volatile fields.
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

    private volatile TacticalPosture latestPosture;
    private volatile GameArc         latestArc;
    private volatile Boolean         cbrInfluenced;
    private volatile Integer         cbrSimilarCount;
    private volatile String          cbrPrediction;


    @Inject SummarisationLifecycle summarisationLifecycle;

    /** CDI constructor */
    public NarrativeContextHolder() {}

    /** Package-private constructor for testing without CDI */
    NarrativeContextHolder(EventStreamBus<TacticalPosture> phaseBus,
                           EventStreamBus<GameArc> arcBus) {
        this.phaseBus = phaseBus;
        this.arcBus = arcBus;
    }

    private EventStreamBus<TacticalPosture> phaseBus;
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
        phaseBus.subscribe(p -> true, event -> latestPosture = event.payload());

        // Subscribe to L4 arc bus — captures latest arc
        arcBus.subscribe(a -> true, event -> latestArc = event.payload());
    }


    /**
     * Update CBR context from the latest CaseContext after engine settle.
     * Called from {@link io.quarkmind.agent.GameTickExecutor} each tick.
     */
    public void updateCbr(CaseContext ctx) {
        if (ctx == null) {return;}
        this.cbrInfluenced   = ctx.getAs(QuarkMindCaseFile.CBR_INFLUENCED_SELECTION, Boolean.class);
        this.cbrSimilarCount = ctx.getAs(QuarkMindCaseFile.TEMPORAL_SIMILAR_COUNT, Integer.class);
        this.cbrPrediction   = ctx.getAs(QuarkMindCaseFile.TEMPORAL_PREDICTION, String.class);
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
        if (latestPosture != null) {
            snapshot.put("phase", latestPosture.posture());
            snapshot.put("phase_rationale", latestPosture.rationale());
        }
        if (latestArc != null) {
            snapshot.put("arc_narrative", latestArc.narrative());
        }
        if (cbrInfluenced != null && cbrInfluenced) {
            snapshot.put("cbr_influenced", "true");
        }
        if (cbrSimilarCount != null && cbrSimilarCount > 0) {
            snapshot.put("cbr_similar_count", String.valueOf(cbrSimilarCount));
        }
        if (cbrPrediction != null) {
            snapshot.put("cbr_prediction", cbrPrediction);
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
        latestPosture  = null;
        latestArc      = null;
        cbrInfluenced  = null;
        cbrSimilarCount = null;
        cbrPrediction  = null;
    }

    /** Package-private accessor for testing */
    TacticalPosture latestPosture() { return latestPosture; }

    /** Package-private accessor for testing */
    GameArc latestArc() { return latestArc; }

    /** Package-private setter for testing without CDI */
    void setLatestPosture(TacticalPosture phase) {this.latestPosture = phase; }

    /** Package-private setter for testing without CDI */
    void setLatestArc(GameArc arc) { this.latestArc = arc; }
}

package io.quarkmind.agent;

import io.casehub.annotation.CaseType;
import io.casehub.api.context.CaseContext;
import io.casehub.ledger.runtime.service.TrustGateService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Four-phase trust maturity model for strategy selection (L6).
 *
 * <p>Phases per candidate:
 * <ul>
 *   <li>BOOTSTRAP — decisionCount &lt; minimumObservations or no score: phaseScore = 0.5</li>
 *   <li>BORDERLINE — score within ±borderlineMargin of threshold: phaseScore = 0.0</li>
 *   <li>QUALIFIED — score &gt; threshold+margin: phaseScore = score*blendFactor + 1.0*(1-blendFactor)</li>
 *   <li>EXCLUDED — score &lt; threshold−margin: phaseScore = 0.0</li>
 * </ul>
 *
 * <p>Tiebreaker: when multiple candidates share the highest phaseScore, the designated
 * fallback ({@value #DESIGNATED_FALLBACK}) wins — not list iteration order.
 *
 * <p>Designated fallback is exempt from BORDERLINE exclusion: it is selected whenever
 * all non-fallback candidates are BORDERLINE or EXCLUDED.
 */
@ApplicationScoped
@CaseType("starcraft-game")
public class StrategyTrustRouter implements TaskDefinition {

    static final String DESIGNATED_FALLBACK = "strategy.drools";

    private static final Logger log = Logger.getLogger(StrategyTrustRouter.class);

    private final TrustGateService trustGateService;

    @Inject StrategySelector strategySelector;

    @ConfigProperty(name = "quarkmind.trust.strategy.min-observations", defaultValue = "10")
    int minimumObservations;
    @ConfigProperty(name = "quarkmind.trust.strategy.threshold",         defaultValue = "0.65")
    double threshold;
    @ConfigProperty(name = "quarkmind.trust.strategy.borderline-margin", defaultValue = "0.08")
    double borderlineMargin;
    @ConfigProperty(name = "quarkmind.trust.strategy.blend-factor",      defaultValue = "0.6")
    double blendFactor;

    @Inject
    public StrategyTrustRouter(TrustGateService trustGateService) {
        this.trustGateService = trustGateService;
    }

    /** Package-private constructor for unit tests (bypasses CDI config injection). */
    StrategyTrustRouter(TrustGateService trustGateService,
                        int minimumObservations, double threshold,
                        double borderlineMargin, double blendFactor) {
        this(trustGateService, minimumObservations, threshold, borderlineMargin, blendFactor,
             new StrategySelector());
    }

    StrategyTrustRouter(TrustGateService trustGateService,
                        int minimumObservations, double threshold,
                        double borderlineMargin, double blendFactor,
                        StrategySelector strategySelector) {
        this.trustGateService    = trustGateService;
        this.minimumObservations = minimumObservations;
        this.threshold           = threshold;
        this.borderlineMargin    = borderlineMargin;
        this.blendFactor         = blendFactor;
        this.strategySelector    = strategySelector;
    }

    // ── TaskDefinition (Phase 1 — execute() used in Phase 2 sequence step) ────

    @Override public String getId()   { return "trust-routing"; }
    @Override public String getName() { return "Strategy Trust Router"; }

    @Override
    public void execute(final CaseContext ctx) {
        // In Phase 1, StrategyTrustObserver (CDI event observer) drives selection at game start
        // and mid-game checkpoint. This execute() writes the current selection to context so
        // Phase 2's SequenceWorker can read it — and will fully replace the observer in Phase 2.
        ctx.set(QuarkMindCaseFile.STRATEGY_SELECTED_ID, strategySelector.getSelectedId());
    }

    @Override public Set<String> produces() { return Set.of(QuarkMindCaseFile.STRATEGY_SELECTED_ID); }

    // ── Trust selection logic ────────────────────────────────────────────────

    /** Select the best strategy from {@code candidates} given the current opponent context. */
    public String select(List<String> candidates, String opponentContext) {
        String winner      = DESIGNATED_FALLBACK;
        double bestScore   = -1.0;
        boolean allZero    = true; // true when all non-fallback candidates scored 0.0

        for (String candidateId : candidates) {
            double phaseScore = computePhaseScore(candidateId, opponentContext);
            log.debugf("[TRUST] candidate=%s context=%s phaseScore=%.3f",
                candidateId, opponentContext, phaseScore);

            if (phaseScore > 0.0) allZero = false;

            if (phaseScore > bestScore
                    || (phaseScore == bestScore && DESIGNATED_FALLBACK.equals(candidateId))) {
                bestScore = phaseScore;
                winner    = candidateId;
            }
        }

        // If all candidates scored 0.0 (all BORDERLINE/EXCLUDED), force the designated fallback.
        if (allZero) {
            winner = DESIGNATED_FALLBACK;
        }

        return winner;
    }

    private double computePhaseScore(String candidateId, String opponentContext) {
        int count = trustGateService.decisionCount(candidateId, opponentContext);
        OptionalDouble score = trustGateService.currentScore(candidateId, opponentContext);

        if (count < minimumObservations || score.isEmpty()) {
            // BOOTSTRAP: 0.5 — strictly below the minimum QUALIFIED phaseScore
            // (min QUALIFIED = threshold+margin+ε)*blendFactor + 1.0*(1-blendFactor) ≥ 0.838
            // so any QUALIFIED candidate outranks any BOOTSTRAP candidate
            return 0.5;
        }

        double s = score.getAsDouble();
        double margin = Math.abs(s - threshold);

        if (margin <= borderlineMargin) {
            return 0.0; // BORDERLINE — score excluded from scoring pool
        }
        if (s >= threshold) {
            return s * blendFactor + 1.0 * (1.0 - blendFactor); // QUALIFIED
        }
        return 0.0; // EXCLUDED
    }
}

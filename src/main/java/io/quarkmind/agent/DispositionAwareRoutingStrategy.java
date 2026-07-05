package io.quarkmind.agent;

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.api.spi.routing.AgentAssignment;
import io.casehub.api.spi.routing.AgentCandidate;
import io.casehub.api.spi.routing.AgentRoutingContext;
import io.casehub.api.spi.routing.AgentRoutingStrategy;
import io.casehub.api.spi.routing.EscalationReason;
import io.casehub.api.spi.routing.TrustRoutingPolicy;
import io.casehub.api.spi.routing.TrustRoutingPolicyProvider;
import io.casehub.eidos.api.AgentDisposition;
import io.casehub.ledger.api.spi.TrustScoreSource;
import io.casehub.ledger.routing.TrustCandidateClassifier;
import io.casehub.ledger.routing.TrustCandidateClassifier.ClassifiedCandidate;
import io.casehub.ledger.routing.TrustCandidateClassifier.Phase;
import io.casehub.ledger.routing.TrustCandidateClassifier.ScoredCandidate;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;

/**
 * QuarkMind-specific {@link AgentRoutingStrategy} that composes trust classification with
 * game-context-dependent disposition scoring.
 *
 * <p>Extends the four-phase trust maturity model (from {@link TrustCandidateClassifier}) with
 * a disposition multiplier (0.8–1.2) derived from the current game state. The multiplier adjusts
 * the trust-based score so that advisors whose behavioural disposition fits the game context are
 * soft-preferred — without ever hard-excluding a candidate.
 *
 * <h3>Disposition mapping</h3>
 * <table>
 *   <tr><th>Game context</th><th>Preferred disposition</th><th>Rationale</th></tr>
 *   <tr><td>Enemy AGGRESSIVE</td><td>riskAppetite: conservative</td><td>Defensive counsel under pressure</td></tr>
 *   <tr><td>Enemy ECONOMIC</td><td>riskAppetite: bold</td><td>Exploit the opponent's greed window</td></tr>
 *   <tr><td>Early game</td><td>ruleFollowing: strict</td><td>Follow established build orders</td></tr>
 *   <tr><td>Late game</td><td>ruleFollowing: flexible</td><td>Adapt to evolving situation</td></tr>
 * </table>
 *
 * <h3>Scoring per phase (extended from TrustWeightedAgentStrategy)</h3>
 * <ul>
 *   <li><b>BOOTSTRAP</b>: workloadScore × dispositionMultiplier</li>
 *   <li><b>QUALIFIED</b>: (trust × blendFactor + workload × (1-blendFactor)) × dispositionMultiplier</li>
 *   <li><b>BORDERLINE/EXCLUDED</b>: 0.0</li>
 * </ul>
 *
 * <p>Priority 2 overrides {@code TrustWeightedAgentStrategy} (Priority 1) when quarkmind is
 * on the classpath.
 */
@Alternative
@Priority(2)
@ApplicationScoped
public class DispositionAwareRoutingStrategy implements AgentRoutingStrategy {

    @Override
    public String id() { return "quarkmind-disposition-aware"; }

    private final TrustCandidateClassifier classifier;
    private final TrustRoutingPolicyProvider policyProvider;
    private final TrustScoreSource scoreSource;

    @Inject
    public DispositionAwareRoutingStrategy(
            final TrustCandidateClassifier classifier,
            final TrustRoutingPolicyProvider policyProvider,
            final TrustScoreSource scoreSource) {
        this.classifier = classifier;
        this.policyProvider = policyProvider;
        this.scoreSource = scoreSource;
    }

    @Override
    public Uni<AgentAssignment> select(
            final AgentRoutingContext context, final List<AgentCandidate> candidates) {
        if (candidates.isEmpty()) {
            return Uni.createFrom().item(AgentAssignment.unresolvable("no candidates provided"));
        }

        final String capability = context.capabilityName();
        final TrustRoutingPolicy policy = policyProvider.forCapability(capability);
        final List<ClassifiedCandidate> classified =
                classifier.classify(candidates, capability, policy, scoreSource);

        // Bootstrap guard: identical to TrustWeightedAgentStrategy
        if (policy.bootstrapEscalationRequired()) {
            final boolean hasQualified = classified.stream().anyMatch(c -> c.phase() == Phase.QUALIFIED);
            final boolean hasBootstrap = classified.stream().anyMatch(c -> c.phase() == Phase.BOOTSTRAP);
            if (!hasQualified && hasBootstrap) {
                return Uni.createFrom()
                        .item(AgentAssignment.escalate(capability, EscalationReason.NO_QUALIFIED_AGENT,
                                "no qualified agent for capability '%s' — only bootstrap candidates".formatted(capability)));
            }
        }

        // Strip BOOTSTRAP when guard is active (only reached if QUALIFIED exists)
        final List<ClassifiedCandidate> eligible =
                policy.bootstrapEscalationRequired()
                        ? classified.stream().filter(c -> c.phase() != Phase.BOOTSTRAP).toList()
                        : classified;

        // Resolve disposition preference from game state
        final JsonNode gameState = context.caseContext();
        final DispositionPreference pref = resolvePreference(gameState);

        // Score each eligible candidate: trust score × disposition multiplier
        final List<ScoredCandidate> scored = new ArrayList<>(eligible.size());
        for (final ClassifiedCandidate cc : eligible) {
            final double trustScore = trustScore(cc, policy);
            final double multiplier = dispositionMultiplier(cc.candidate(), pref);
            scored.add(new ScoredCandidate(cc, trustScore * multiplier,
                "trust=%.3f disposition=%.2f".formatted(trustScore, multiplier)));
        }

        // Delegate decision logic to classifier — handles escalation for
        // all-borderline pools and bootstrapEscalationRequired guard
        return Uni.createFrom().item(classifier.decide(eligible, scored, capability));
    }

    // ── Disposition logic ───────────────────────────────────────────────

    /**
     * Resolves the game context to a disposition preference.
     *
     * <p>Reads {@code game.enemy.posture} and {@code game.phase} from the case context's
     * working panel. Null/missing values result in a neutral preference (multiplier 1.0).
     */
    static DispositionPreference resolvePreference(final JsonNode gameState) {
        if (gameState == null || gameState.isNull() || gameState.isMissingNode()) {
            return DispositionPreference.NEUTRAL;
        }

        final JsonNode working = gameState.path("working");
        final String enemyPosture = textOrNull(working, "game.enemy.posture");
        final String gamePhase = textOrNull(working, "game.phase");

        final String preferredRisk = resolveRiskPreference(enemyPosture);
        final String preferredRule = resolveRulePreference(gamePhase);

        if (preferredRisk == null && preferredRule == null) {
            return DispositionPreference.NEUTRAL;
        }
        return new DispositionPreference(preferredRisk, preferredRule);
    }

    private static String resolveRiskPreference(final String enemyPosture) {
        if (enemyPosture == null) return null;
        return switch (enemyPosture.toUpperCase()) {
            case "AGGRESSIVE" -> "conservative"; // Defensive counsel under pressure
            case "ECONOMIC"   -> "bold";          // Exploit the opponent's greed window
            default           -> null;            // Unknown posture — no preference
        };
    }

    private static String resolveRulePreference(final String gamePhase) {
        if (gamePhase == null) return null;
        return switch (gamePhase.toLowerCase()) {
            case "early" -> "strict";    // Follow established build orders
            case "late"  -> "flexible";  // Adapt to evolving situation
            default      -> null;        // Mid-game or unknown — no preference
        };
    }

    private static String textOrNull(final JsonNode node, final String fieldName) {
        if (node == null || node.isMissingNode()) return null;
        final JsonNode field = node.path(fieldName);
        return field.isMissingNode() || field.isNull() ? null : field.asText();
    }

    // ── Trust scoring ───────────────────────────────────────────────────

    private double dispositionMultiplier(final AgentCandidate candidate, final DispositionPreference pref) {
        if (candidate.agentDescriptor() == null) {
            return 1.0; // No descriptor → neutral multiplier
        }
        final AgentDisposition disposition = candidate.agentDescriptor().disposition();
        return pref.computeMultiplier(disposition);
    }

    /**
     * Trust score per phase — mirrors {@link io.casehub.ledger.routing.TrustWeightedAgentStrategy}
     * scoring (BOOTSTRAP=workload, QUALIFIED=blend, BORDERLINE/EXCLUDED=0.0).
     */
    private static double trustScore(final ClassifiedCandidate cc, final TrustRoutingPolicy policy) {
        return switch (cc.phase()) {
            case Phase.BOOTSTRAP -> cc.workloadScore();
            case Phase.QUALIFIED -> {
                final double t = cc.trustScore().getAsDouble();
                yield t * policy.blendFactor() + cc.workloadScore() * (1.0 - policy.blendFactor());
            }
            case Phase.BORDERLINE, Phase.EXCLUDED_PHASE2B, Phase.EXCLUDED_PHASE3 -> 0.0;
        };
    }
}

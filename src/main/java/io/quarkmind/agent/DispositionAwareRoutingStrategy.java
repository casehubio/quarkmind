package io.quarkmind.agent;

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.api.spi.routing.AgentCandidate;
import io.casehub.api.spi.routing.AgentRoutingContext;
import io.casehub.api.spi.routing.AgentRoutingStrategy;
import io.casehub.api.spi.routing.EscalationReason;
import io.casehub.api.spi.routing.RoutingResult;
import io.casehub.api.spi.routing.TrustRoutingPolicy;
import io.casehub.api.spi.routing.TrustRoutingPolicyProvider;
import io.casehub.eidos.api.AgentDisposition;
import io.casehub.ledger.api.spi.TrustScoreSource;
import io.casehub.ledger.routing.TrustCandidateClassifier;
import io.casehub.ledger.routing.TrustCandidateClassifier.ClassifiedCandidate;
import io.casehub.ledger.routing.TrustCandidateClassifier.Phase;
import io.casehub.ledger.routing.TrustCandidateClassifier.ScoredCandidate;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import java.util.List;

@Alternative
@Priority(2)
@ApplicationScoped
public class DispositionAwareRoutingStrategy implements AgentRoutingStrategy {

    private final TrustCandidateClassifier   classifier;
    private final TrustRoutingPolicyProvider policyProvider;
    private final TrustScoreSource           scoreSource;
    @Inject
    public DispositionAwareRoutingStrategy(
            final TrustCandidateClassifier classifier,
            final TrustRoutingPolicyProvider policyProvider,
            final TrustScoreSource scoreSource) {
        this.classifier     = classifier;
        this.policyProvider = policyProvider;
        this.scoreSource    = scoreSource;
    }

    static DispositionPreference resolvePreference(final JsonNode gameState) {
        if (gameState == null || gameState.isNull() || gameState.isMissingNode()) {
            return DispositionPreference.NEUTRAL;
        }

        final JsonNode working      = gameState.path("working");
        final String   enemyPosture = textOrNull(working, "game.enemy.posture");
        final String   gamePhase    = textOrNull(working, "game.phase");

        final String preferredRisk = resolveRiskPreference(enemyPosture);
        final String preferredRule = resolveRulePreference(gamePhase);

        if (preferredRisk == null && preferredRule == null) {
            return DispositionPreference.NEUTRAL;
        }
        return new DispositionPreference(preferredRisk, preferredRule);
    }

    private static String resolveRiskPreference(final String enemyPosture) {
        if (enemyPosture == null) {return null;}
        return switch (enemyPosture.toUpperCase()) {
            case "AGGRESSIVE" -> "conservative";
            case "ECONOMIC" -> "bold";
            default -> null;
        };
    }

    private static String resolveRulePreference(final String gamePhase) {
        if (gamePhase == null) {return null;}
        return switch (gamePhase.toLowerCase()) {
            case "early" -> "strict";
            case "late" -> "flexible";
            default -> null;
        };
    }

    private static String textOrNull(final JsonNode node, final String fieldName) {
        if (node == null || node.isMissingNode()) {return null;}
        final JsonNode field = node.path(fieldName);
        return field.isMissingNode() || field.isNull() ? null : field.asText();
    }

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

    @Override
    public String id() {return "quarkmind-disposition-aware";}

    @Override
    public io.smallrye.mutiny.Uni<RoutingResult> select(
            final AgentRoutingContext context, final List<AgentCandidate> candidates) {
        if (candidates.isEmpty()) {
            return io.smallrye.mutiny.Uni.createFrom().item(RoutingResult.unresolvable("no candidates provided"));
        }

        final String             capability = context.capabilityName();
        final TrustRoutingPolicy policy     = policyProvider.forCapability(capability);
        final List<ClassifiedCandidate> classified =
                classifier.classify(candidates, capability, policy, scoreSource);

        if (policy.bootstrapEscalationRequired()) {
            final boolean hasQualified = classified.stream().anyMatch(c -> c.phase() == Phase.QUALIFIED);
            final boolean hasBootstrap = classified.stream().anyMatch(c -> c.phase() == Phase.BOOTSTRAP);
            if (!hasQualified && hasBootstrap) {
                return io.smallrye.mutiny.Uni.createFrom().item(RoutingResult.escalate(capability, EscalationReason.NO_QUALIFIED_AGENT,
                                                                                       "no qualified agent for capability '%s' — only bootstrap candidates".formatted(capability)));
            }
        }

        final List<ClassifiedCandidate> eligible =
                policy.bootstrapEscalationRequired()
                ? classified.stream().filter(c -> c.phase() != Phase.BOOTSTRAP).toList()
                : classified;

        final JsonNode              gameState = context.caseContext();
        final DispositionPreference pref      = resolvePreference(gameState);

        final List<ScoredCandidate> scored = new java.util.ArrayList<>(eligible.size());
        for (final ClassifiedCandidate cc : eligible) {
            final double ts         = trustScore(cc, policy);
            final double multiplier = dispositionMultiplier(cc.candidate(), pref);
            scored.add(new ScoredCandidate(cc, ts * multiplier,
                                           "trust=%.3f disposition=%.2f".formatted(ts, multiplier)));
        }

        return io.smallrye.mutiny.Uni.createFrom().item(classifier.decide(eligible, scored, capability));
    }

    private double dispositionMultiplier(final AgentCandidate candidate, final DispositionPreference pref) {
        if (candidate.agentDescriptor() == null) {
            return 1.0;
        }
        final AgentDisposition disposition = candidate.agentDescriptor().disposition();
        return pref.computeMultiplier(disposition);
    }
}

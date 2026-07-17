package io.quarkmind.agent.cbr;

import io.casehub.api.spi.routing.*;
import io.casehub.ledger.api.spi.TrustScoreSource;
import io.casehub.ledger.routing.TrustCandidateClassifier;
import io.casehub.ledger.routing.TrustCandidateClassifier.ClassifiedCandidate;
import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.stream.Collectors;

public class SC2ImplementationRoutingStrategy implements ImplementationRoutingStrategy {

    private static final Logger log = Logger.getLogger(SC2ImplementationRoutingStrategy.class);

    private final TrustCandidateClassifier classifier;
    private final TrustScoreSource scoreSource;
    private final TrustRoutingPolicyProvider policyProvider;

    public SC2ImplementationRoutingStrategy(
            TrustCandidateClassifier classifier,
            TrustScoreSource scoreSource,
            TrustRoutingPolicyProvider policyProvider) {
        this.classifier = classifier;
        this.scoreSource = scoreSource;
        this.policyProvider = policyProvider;
    }

    @Override
    public String id() { return "sc2-cbr-routing"; }

    @Override
    public Uni<ImplementationSelection> select(
            ImplementationRoutingContext context,
            List<ImplementationCandidate> candidates) {

        if (candidates.size() <= 1) {
            return Uni.createFrom().item(new ImplementationSelection.RunAll());
        }

        TrustRoutingPolicy policy = policyProvider.forCapability(context.capabilityName());
        double cbrWeight = policy.cbrWeight();

        List<AgentCandidate> agentCandidates = candidates.stream()
                .map(c -> new AgentCandidate(c.workerName(), Set.of(c.capabilityName()),
                        0, AgentHealth.READY, null, null))
                .toList();

        List<ClassifiedCandidate> classified = classifier.classify(
                agentCandidates, context.capabilityName(), policy, scoreSource);

        Map<String, Double> experienceWeights = computeExperienceWeights(
                context.experiences(), candidates);

        String bestBinding = null;
        double bestScore = -1.0;

        for (int i = 0; i < classified.size(); i++) {
            ClassifiedCandidate cc = classified.get(i);
            ImplementationCandidate ic = candidates.get(i);

            double trustScore = computeTrustScore(cc, policy, ic.bindingName());
            double expWeight = experienceWeights.getOrDefault(ic.bindingName(), 0.5);
            double finalScore = (1.0 - cbrWeight) * trustScore + cbrWeight * expWeight;

            log.debugf("[CBR-ROUTE] candidate=%s trust=%.3f cbr=%.3f final=%.3f (phase=%s)",
                    ic.bindingName(), trustScore, expWeight, finalScore, cc.phase());

            if (finalScore > bestScore
                    || (finalScore == bestScore && ic.bindingName().equals(policy.fallbackBinding()))) {
                bestScore = finalScore;
                bestBinding = ic.bindingName();
            }
        }

        if (bestScore <= 0.0 && policy.fallbackBinding() != null) {
            bestBinding = policy.fallbackBinding();
        }

        log.infof("[CBR-ROUTE] Selected: %s (score=%.3f, cbrWeight=%.2f, experiences=%d)",
                bestBinding, bestScore, cbrWeight, context.experiences().size());

        return Uni.createFrom().item(
                new ImplementationSelection.Selected(List.of(bestBinding)));
    }

    private double computeTrustScore(ClassifiedCandidate cc, TrustRoutingPolicy policy,
                                     String bindingName) {
        return switch (cc.phase()) {
            case BOOTSTRAP -> cc.workloadScore();
            case QUALIFIED -> {
                double t = cc.trustScore().getAsDouble();
                yield t * policy.blendFactor() + cc.workloadScore() * (1.0 - policy.blendFactor());
            }
            case BORDERLINE -> bindingName.equals(policy.fallbackBinding()) ? 0.01 : 0.0;
            case EXCLUDED_PHASE2B, EXCLUDED_PHASE3 -> 0.0;
        };
    }

    private Map<String, Double> computeExperienceWeights(
            List<RetrievedExperience> experiences, List<ImplementationCandidate> candidates) {
        if (experiences == null || experiences.isEmpty()) {
            return Map.of();
        }

        Map<String, List<RetrievedExperience>> bySolution = experiences.stream()
                .filter(e -> e.outcome() != null)
                .collect(Collectors.groupingBy(RetrievedExperience::solution));

        Map<String, Double> weights = new HashMap<>();
        for (ImplementationCandidate c : candidates) {
            List<RetrievedExperience> matching = bySolution.getOrDefault(c.bindingName(), List.of());
            if (matching.isEmpty()) continue;

            double weightedOutcome = 0.0;
            double totalSimilarity = 0.0;
            for (RetrievedExperience exp : matching) {
                double outcomeValue = switch (exp.outcome()) {
                    case "WIN"  -> 1.0;
                    case "LOSS" -> 0.0;
                    case "TIE"  -> 0.5;
                    default     -> 0.5;
                };
                weightedOutcome += exp.similarityScore() * outcomeValue;
                totalSimilarity += exp.similarityScore();
            }
            weights.put(c.bindingName(), totalSimilarity > 0 ? weightedOutcome / totalSimilarity : 0.5);
        }
        return weights;
    }
}

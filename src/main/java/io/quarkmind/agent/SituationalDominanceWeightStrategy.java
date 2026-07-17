package io.quarkmind.agent;

import io.quarkmind.domain.DominanceWeights;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class SituationalDominanceWeightStrategy implements DominanceWeightStrategy {

    private static final Logger log = Logger.getLogger(SituationalDominanceWeightStrategy.class);
    private static final Map<String, double[]> PHASE_MODIFIERS = Map.of(
        "DEFENSIVE_HOLD",    new double[]{-0.10, +0.15, -0.05,  0.00},
        "EARLY_AGGRESSION",  new double[]{-0.05, +0.10, -0.05,  0.00},
        "EARLY_MACRO",       new double[]{+0.10, -0.10, +0.05, -0.05},
        "MID_SKIRMISH",      new double[]{-0.05, +0.10, -0.05,  0.00},
        "TRANSITIONING",     new double[]{ 0.00,  0.00,  0.00,  0.00}
    );

    private final AnchorInterpolator interpolator;

    @Inject
    SituationalDominanceWeightStrategy(MilestoneConfig config) {
        this(config.dominance().anchors());
    }

    SituationalDominanceWeightStrategy(List<MilestoneConfig.Dominance.WeightAnchor> anchors) {
        this.interpolator = new AnchorInterpolator(anchors);
    }

    @Override
    public String id() {
        return "situational";
    }

    @Override
    public DominanceWeights resolve(WeightContext context) {
        DominanceWeights baseline = interpolator.interpolate(context.gameFrame());
        double[] mod = context.currentPhase() != null
            ? PHASE_MODIFIERS.getOrDefault(context.currentPhase(), new double[4])
            : new double[4];

        double economy = Math.max(MINIMUM_WEIGHT, baseline.economy() + mod[0]);
        double army    = Math.max(MINIMUM_WEIGHT, baseline.army()    + mod[1]);
        double tech    = Math.max(MINIMUM_WEIGHT, baseline.tech()    + mod[2]);
        double bases   = Math.max(MINIMUM_WEIGHT, baseline.bases()   + mod[3]);

        if (economy != baseline.economy() + mod[0] || army != baseline.army() + mod[1]
                || tech != baseline.tech() + mod[2] || bases != baseline.bases() + mod[3]) {
            log.debugf("[DOMINANCE] Clamping fired: phase=%s", context.currentPhase());
        }

        double sum = economy + army + tech + bases;
        return new DominanceWeights(economy / sum, army / sum, tech / sum, bases / sum);
    }
}

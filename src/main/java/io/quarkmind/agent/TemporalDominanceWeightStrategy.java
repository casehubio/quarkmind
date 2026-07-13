package io.quarkmind.agent;

import io.quarkmind.domain.DominanceWeights;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@ApplicationScoped
public class TemporalDominanceWeightStrategy implements DominanceWeightStrategy {

    private final AnchorInterpolator interpolator;

    @Inject
    TemporalDominanceWeightStrategy(MilestoneConfig config) {
        this(config.dominance().anchors());
    }

    TemporalDominanceWeightStrategy(List<MilestoneConfig.Dominance.WeightAnchor> anchors) {
        this.interpolator = new AnchorInterpolator(anchors);
    }

    @Override
    public String id() {
        return "temporal";
    }

    @Override
    public DominanceWeights resolve(WeightContext context) {
        return interpolator.interpolate(context.gameFrame());
    }
}

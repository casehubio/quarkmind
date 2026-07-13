package io.quarkmind.agent;

import io.quarkmind.domain.DominanceWeights;

import java.util.List;

class AnchorInterpolator {

    private final List<MilestoneConfig.Dominance.WeightAnchor> anchors;

    AnchorInterpolator(List<MilestoneConfig.Dominance.WeightAnchor> anchors) {
        if (anchors.isEmpty()) {
            throw new IllegalArgumentException("Anchors must contain at least one entry");
        }
        for (int i = 1; i < anchors.size(); i++) {
            if (anchors.get(i).frame() <= anchors.get(i - 1).frame()) {
                throw new IllegalArgumentException(
                    "Anchor frames must be strictly ascending: frame[" + (i - 1) + "]="
                    + anchors.get(i - 1).frame() + " >= frame[" + i + "]=" + anchors.get(i).frame());
            }
        }
        this.anchors = List.copyOf(anchors);
    }

    DominanceWeights interpolate(long gameFrame) {
        if (anchors.size() == 1 || gameFrame <= anchors.get(0).frame()) {
            return toWeights(anchors.get(0));
        }
        if (gameFrame >= anchors.get(anchors.size() - 1).frame()) {
            return toWeights(anchors.get(anchors.size() - 1));
        }
        for (int i = 0; i < anchors.size() - 1; i++) {
            var lo = anchors.get(i);
            var hi = anchors.get(i + 1);
            if (gameFrame >= lo.frame() && gameFrame <= hi.frame()) {
                double t = (double) (gameFrame - lo.frame()) / (hi.frame() - lo.frame());
                return new DominanceWeights(
                    lerp(lo.economyWeight(), hi.economyWeight(), t),
                    lerp(lo.armyWeight(), hi.armyWeight(), t),
                    lerp(lo.techWeight(), hi.techWeight(), t),
                    lerp(lo.basesWeight(), hi.basesWeight(), t));
            }
        }
        return toWeights(anchors.get(anchors.size() - 1));
    }

    private static DominanceWeights toWeights(MilestoneConfig.Dominance.WeightAnchor a) {
        return new DominanceWeights(a.economyWeight(), a.armyWeight(), a.techWeight(), a.basesWeight());
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}

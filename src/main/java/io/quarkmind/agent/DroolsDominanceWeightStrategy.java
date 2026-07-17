package io.quarkmind.agent;

import io.quarkmind.domain.DominanceWeights;

import java.util.List;

public class DroolsDominanceWeightStrategy {

    static DominanceWeights applyModifiers(DominanceWeights baseline,
                                           List<WeightModifier> modifiers) {
        if (modifiers.isEmpty()) return baseline;

        double economy = baseline.economy();
        double army = baseline.army();
        double tech = baseline.tech();
        double bases = baseline.bases();

        for (WeightModifier mod : modifiers) {
            economy += mod.economyDelta();
            army += mod.armyDelta();
            tech += mod.techDelta();
            bases += mod.basesDelta();
        }

        economy = Math.max(DominanceWeightStrategy.MINIMUM_WEIGHT, economy);
        army = Math.max(DominanceWeightStrategy.MINIMUM_WEIGHT, army);
        tech = Math.max(DominanceWeightStrategy.MINIMUM_WEIGHT, tech);
        bases = Math.max(DominanceWeightStrategy.MINIMUM_WEIGHT, bases);

        double sum = economy + army + tech + bases;
        return new DominanceWeights(
            economy / sum, army / sum, tech / sum, bases / sum);
    }
}

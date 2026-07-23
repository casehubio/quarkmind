package io.quarkmind.agent;

import io.quarkmind.domain.DominanceWeights;
import io.quarkmind.plugin.drools.DominanceWeightRuleUnit;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.drools.ruleunits.api.RuleUnit;
import org.drools.ruleunits.api.RuleUnitInstance;

import java.util.List;

@ApplicationScoped
public class DroolsDominanceWeightStrategy implements DominanceWeightStrategy {

    private final RuleUnit<DominanceWeightRuleUnit> ruleUnit;
    private final AnchorInterpolator interpolator;

    @Inject
    DroolsDominanceWeightStrategy(
            RuleUnit<DominanceWeightRuleUnit> ruleUnit,
            MilestoneConfig config) {
        this.ruleUnit = ruleUnit;
        this.interpolator = new AnchorInterpolator(config.dominance().anchors());
    }

    DroolsDominanceWeightStrategy(
            RuleUnit<DominanceWeightRuleUnit> ruleUnit,
            List<MilestoneConfig.Dominance.WeightAnchor> anchors) {
        this.ruleUnit = ruleUnit;
        this.interpolator = new AnchorInterpolator(anchors);
    }

    @Override
    public String id() {
        return "drools";
    }

    @Override
    public DominanceWeights resolve(WeightContext context) {
        DominanceWeights baseline = interpolator.interpolate(context.gameFrame());
        DominanceWeightRuleUnit data = new DominanceWeightRuleUnit();

        if (context.currentPhase() != null) {
            data.getTacticalPostureStore().add(context.currentPhase());
        }
        for (var a : context.patternAssessments()) {
            data.getPatternStore().add(a);
        }

        try (RuleUnitInstance<DominanceWeightRuleUnit> instance =
                 ruleUnit.createInstance(data)) {
            instance.fire();
        }

        return applyModifiers(baseline, data.getModifiers());
    }

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

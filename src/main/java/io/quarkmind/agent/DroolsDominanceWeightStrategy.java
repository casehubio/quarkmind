package io.quarkmind.agent;

import io.quarkmind.domain.DominanceWeights;
import io.quarkmind.domain.PatternAssessment;
import io.quarkmind.plugin.drools.DominanceWeightRuleUnit;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.drools.ruleunits.api.RuleUnit;
import org.drools.ruleunits.api.RuleUnitInstance;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

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
        DominanceWeights        baseline = interpolator.interpolate(context.gameFrame());
        DominanceWeightRuleUnit data     = new DominanceWeightRuleUnit();

        if (context.currentPhase() != null) {
            data.getTacticalPostureStore().add(context.currentPhase());
        }
        for (var a : deduplicateByCategory(context.patternAssessments())) {
            data.getPatternStore().add(a);
        }

        try (RuleUnitInstance<DominanceWeightRuleUnit> instance =
                     ruleUnit.createInstance(data)) {
            instance.fire();
        }

        return applyModifiers(baseline, data.getModifiers());}

    static DominanceWeights applyModifiers(DominanceWeights baseline,
                                           List<WeightModifier> modifiers) {
        if (modifiers.isEmpty()) return baseline;

        double economy    = baseline.economy();
        double army       = baseline.army();
        double tech       = baseline.tech();
        double bases      = baseline.bases();
        double mapControl = baseline.mapControl();

        for (WeightModifier mod : modifiers) {
            economy    += mod.economyDelta();
            army       += mod.armyDelta();
            tech       += mod.techDelta();
            bases      += mod.basesDelta();
            mapControl += mod.mapControlDelta();
        }

        economy    = Math.max(DominanceWeightStrategy.MINIMUM_WEIGHT, economy);
        army       = Math.max(DominanceWeightStrategy.MINIMUM_WEIGHT, army);
        tech       = Math.max(DominanceWeightStrategy.MINIMUM_WEIGHT, tech);
        bases      = Math.max(DominanceWeightStrategy.MINIMUM_WEIGHT, bases);
        mapControl = Math.max(DominanceWeightStrategy.MINIMUM_WEIGHT, mapControl);

        double sum = economy + army + tech + bases + mapControl;
        return new DominanceWeights(
            economy / sum, army / sum, tech / sum, bases / sum, mapControl / sum);
    }

    static List<PatternAssessment> deduplicateByCategory(List<PatternAssessment> assessments) {
        if (assessments.size() <= 1) {return assessments;}
        return new ArrayList<>(assessments.stream()
                                          .collect(Collectors.toMap(
                                                  a -> a.archetype().category(),
                                                  a -> a,
                                                  (a, b) -> a.confidence() >= b.confidence() ? a : b))
                                          .values());
    }

}

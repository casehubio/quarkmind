package io.quarkmind.plugin.drools;

import io.quarkmind.plugin.scouting.PatternClassificationRuleUnit;
import io.quarkmind.plugin.scouting.ScoutingRuleUnit;
import io.quarkmind.plugin.summarisation.MomentDetectionRuleUnit;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.drools.ruleunits.api.RuleUnit;
import org.drools.ruleunits.api.RuleUnitProvider;

@ApplicationScoped
public class RuleUnitProducer {

    @Produces @ApplicationScoped
    RuleUnit<TacticsRuleUnit> tacticsRuleUnit() {
        return RuleUnitProvider.get().getRuleUnit(new TacticsRuleUnit());
    }

    @Produces @ApplicationScoped
    RuleUnit<StrategyRuleUnit> strategyRuleUnit() {
        return RuleUnitProvider.get().getRuleUnit(new StrategyRuleUnit());
    }

    @Produces @ApplicationScoped
    RuleUnit<MomentDetectionRuleUnit> momentDetectionRuleUnit() {
        return RuleUnitProvider.get().getRuleUnit(new MomentDetectionRuleUnit());
    }

    @Produces @ApplicationScoped
    RuleUnit<ScoutingRuleUnit> scoutingRuleUnit() {
        return RuleUnitProvider.get().getRuleUnit(new ScoutingRuleUnit());
    }

    @Produces @ApplicationScoped
    RuleUnit<PatternClassificationRuleUnit> patternClassificationRuleUnit() {
        return RuleUnitProvider.get().getRuleUnit(new PatternClassificationRuleUnit());
    }

    @Produces @ApplicationScoped
    RuleUnit<DominanceWeightRuleUnit> dominanceWeightRuleUnit() {
        return RuleUnitProvider.get().getRuleUnit(new DominanceWeightRuleUnit());
    }
}

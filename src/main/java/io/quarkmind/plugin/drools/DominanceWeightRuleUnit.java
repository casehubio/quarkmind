package io.quarkmind.plugin.drools;

import io.quarkmind.agent.WeightModifier;
import io.quarkmind.domain.EnemyPatternAssessment;
import org.drools.ruleunits.api.DataSource;
import org.drools.ruleunits.api.DataStore;
import org.drools.ruleunits.api.RuleUnitData;

import java.util.ArrayList;
import java.util.List;

public class DominanceWeightRuleUnit implements RuleUnitData {

    private final DataStore<EnemyPatternAssessment> patternStore = DataSource.createStore();
    private final DataStore<String>                 phaseStore   = DataSource.createStore();

    private final List<WeightModifier> modifiers = new ArrayList<>();

    public DataStore<EnemyPatternAssessment> getPatternStore() { return patternStore; }
    public DataStore<String>                 getPhaseStore()   { return phaseStore; }
    public List<WeightModifier>              getModifiers()    { return modifiers; }
}

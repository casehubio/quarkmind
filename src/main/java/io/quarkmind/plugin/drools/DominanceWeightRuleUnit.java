package io.quarkmind.plugin.drools;

import io.quarkmind.agent.WeightModifier;
import io.quarkmind.domain.PatternAssessment;
import org.drools.ruleunits.api.DataSource;
import org.drools.ruleunits.api.DataStore;
import org.drools.ruleunits.api.RuleUnitData;

import java.util.ArrayList;
import java.util.List;

public class DominanceWeightRuleUnit implements RuleUnitData {

    private final DataStore<PatternAssessment> patternStore         = DataSource.createStore();
    private final DataStore<String>            tacticalPostureStore = DataSource.createStore();

    private final List<WeightModifier> modifiers = new ArrayList<>();

    public DataStore<PatternAssessment> getPatternStore() {return patternStore;}

    public DataStore<String> getTacticalPostureStore()    {return tacticalPostureStore;}

    public List<WeightModifier> getModifiers()            {return modifiers;}
}

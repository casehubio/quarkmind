package io.quarkmind.plugin.scouting;

import io.quarkmind.plugin.scouting.events.EnemyArmyNearBase;
import io.quarkmind.plugin.scouting.events.EnemyExpansionSeen;
import io.quarkmind.plugin.scouting.events.EnemyUnitFirstSeen;
import org.drools.ruleunits.api.DataSource;
import org.drools.ruleunits.api.DataStore;
import org.drools.ruleunits.api.RuleUnitData;

import java.util.ArrayList;
import java.util.List;

public class PatternClassificationRuleUnit implements RuleUnitData {

    private final DataStore<EnemyUnitFirstSeen>  unitEvents         = DataSource.createStore();
    private final DataStore<EnemyExpansionSeen>  expansionEvents    = DataSource.createStore();
    private final DataStore<EnemyArmyNearBase>   armyNearBaseEvents = DataSource.createStore();
    private final DataStore<Double>              gameTimeStore      = DataSource.createStore();

    private final List<EvidenceMarker> evidence = new ArrayList<>();

    public DataStore<EnemyUnitFirstSeen>  getUnitEvents()         { return unitEvents; }
    public DataStore<EnemyExpansionSeen>  getExpansionEvents()    { return expansionEvents; }
    public DataStore<EnemyArmyNearBase>   getArmyNearBaseEvents() { return armyNearBaseEvents; }
    public DataStore<Double>              getGameTimeStore()      { return gameTimeStore; }

    public List<EvidenceMarker> getEvidence() { return evidence; }
}

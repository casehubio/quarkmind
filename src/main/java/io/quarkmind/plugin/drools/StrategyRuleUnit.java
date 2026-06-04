package io.quarkmind.plugin.drools;

import org.drools.ruleunits.api.DataSource;
import org.drools.ruleunits.api.DataStore;
import org.drools.ruleunits.api.RuleUnitData;
import io.quarkmind.domain.Building;
import io.quarkmind.domain.Resource;
import io.quarkmind.domain.Unit;

import java.util.ArrayList;
import java.util.List;

/**
 * Rule Unit data context for Drools strategy evaluation.
 *
 * <p>Only JDK and Drools-known types appear as non-{@link DataStore} fields. Application
 * domain classes ({@code ResourceBudget}, {@code IntentQueue}) must NOT appear as plain
 * field types here — {@code drools-ruleunits-impl}'s {@code SimpleRuleUnitVariable}
 * calls {@code Class.forName()} on each field type during static initialisation of the
 * generated CDI bean, and application classes may not be visible in the Drools classloader
 * at that point (see GE-0053).
 *
 * <p>DataStore generic type parameters are erased at runtime, so {@code DataStore<Unit>}
 * etc. are safe — only the raw type {@code DataStore} is loaded.
 *
 * <p>Enemy posture and timing-attack signal are fed as {@link DataStore} facts rather than
 * plain fields because {@code eval()} + {@code accumulate()} in the same rule does not
 * compile in the current Drools version (generated lambda loses field scope). DataStore
 * pattern matching ({@code not /postureStore[...]}) avoids this limitation entirely.
 *
 * <p><b>Architecture:</b> rules are declarative — they add string decisions to
 * {@link #buildDecisions} and {@link #strategyDecisions}. Budget enforcement and intent
 * dispatch are handled by {@link io.quarkmind.plugin.DroolsStrategyTask}.
 */
public class StrategyRuleUnit implements RuleUnitData {

    /** Designated builder probe — 0 or 1 items (pre-selected per tick). */
    private final DataStore<Unit>     builders    = DataSource.createStore();

    /** All player buildings (complete and in-progress). */
    private final DataStore<Building> buildings   = DataSource.createStore();

    /** All army units (non-probe). */
    private final DataStore<Unit>     army        = DataSource.createStore();

    /**
     * First unoccupied vespene geyser — 0 or 1 items.
     * Pre-filtered by {@link io.quarkmind.plugin.DroolsStrategyTask}.
     */
    private final DataStore<Resource> geysers     = DataSource.createStore();

    /**
     * Single-element store holding the current enemy posture string:
     * {@code "ALL_IN"}, {@code "MACRO"}, or {@code "UNKNOWN"}.
     * One item always inserted before {@code fire()}.
     */
    private final DataStore<String>  postureStore = DataSource.createStore();

    /**
     * Single-element store holding whether a timing attack is incoming.
     * One item always inserted before {@code fire()}.
     */
    private final DataStore<Boolean> timingStore  = DataSource.createStore();

    /**
     * Build decisions written by rules. Strings from the set
     * {@code "GATEWAY", "ASSIMILATOR", "CYBERNETICS_CORE", "STALKER:<gatewayTag>"}.
     * Java handles budget enforcement and intent dispatch after fire().
     */
    private final List<String> buildDecisions   = new ArrayList<>();

    /**
     * Strategic posture written by rules: {@code "DEFEND"} or {@code "ATTACK"}.
     * Empty = default {@code "MACRO"}.
     */
    private final List<String> strategyDecisions = new ArrayList<>();

    public DataStore<Unit>     getBuilders()         { return builders; }
    public DataStore<Building> getBuildings()        { return buildings; }
    public DataStore<Unit>     getArmy()             { return army; }
    public DataStore<Resource> getGeysers()          { return geysers; }
    public DataStore<String>   getPostureStore()     { return postureStore; }
    public DataStore<Boolean>  getTimingStore()      { return timingStore; }
    public List<String>        getBuildDecisions()   { return buildDecisions; }
    public List<String>        getStrategyDecisions(){ return strategyDecisions; }
}

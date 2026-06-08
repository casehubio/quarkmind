package io.quarkmind.plugin;

import io.casehub.annotation.CaseType;
import io.casehub.core.CaseFile;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.Preferences;
import io.casehub.platform.api.preferences.SettingsScope;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.ResourceBudget;
import io.quarkmind.agent.ScoutingIntelBroker;
import io.quarkmind.agent.plugin.ScoutingIntelConsumer;
import io.quarkmind.agent.plugin.ScoutingIntelPayload;
import io.quarkmind.agent.plugin.ScoutingIntelPreferences;
import io.quarkmind.agent.plugin.ScoutingIntelType;
import io.quarkmind.agent.plugin.StrategyTask;
import io.quarkmind.domain.*;
import io.quarkmind.plugin.drools.StrategyRuleUnit;
import io.quarkmind.sc2.IntentQueue;
import io.quarkmind.sc2.intent.BuildIntent;
import io.quarkmind.sc2.intent.TrainIntent;
import org.drools.ruleunits.api.RuleUnit;
import org.drools.ruleunits.api.RuleUnitInstance;
import org.jboss.logging.Logger;

import io.casehub.ledger.api.model.AttestationVerdict;
import io.quarkmind.agent.GameSession;
import io.quarkmind.agent.PluginDecisionEvent;
import io.quarkmind.agent.QuarkMindCapabilityTag;
import jakarta.enterprise.event.Event;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Drools-backed {@link StrategyTask} — first real R&D integration.
 *
 * <p>Each tick, game state from the CaseFile is loaded into a {@link StrategyRuleUnit}
 * and a fresh Drools session is fired. Rules decide what to build and the strategic
 * posture; this class enforces the budget and dispatches intents.
 *
 * <p>Rules write string decisions to avoid Drools classloader constraints (see GE-0053):
 * application types ({@link ResourceBudget}, {@link IntentQueue}) must not appear
 * as plain field types in {@link StrategyRuleUnit}.
 *
 * <p>Replaces the earlier hand-coded strategy implementation as the active CDI bean.
 */
@ApplicationScoped
@CaseType("starcraft-game")
public class DroolsStrategyTask implements StrategyTask, ScoutingIntelConsumer {

    static final Point2d GATEWAY_POS          = new Point2d(17, 18);
    static final Point2d CYBERNETICS_CORE_POS = new Point2d(20, 18);

    private static final Logger log = Logger.getLogger(DroolsStrategyTask.class);

    private final RuleUnit<StrategyRuleUnit> ruleUnit;
    private final IntentQueue intentQueue;
    private final ScoutingIntelBroker broker;

    @Inject Event<PluginDecisionEvent> decisionEvents;
    @Inject GameSession gameSession;
    @Inject PreferenceProvider preferenceProvider;
    private volatile String prevStrategy = null;

    // Safe default before @PostConstruct fires
    Set<ScoutingIntelType> subscribedTypes = Set.of();

    @PostConstruct
    void init() {
        refreshSubscriptions(preferenceProvider.resolve(SettingsScope.root()));
    }

    @Override
    public void refreshSubscriptions(Preferences prefs) {
        // Strategy subscribes to POSTURE and TIMING_ALERT; BUILD_ORDER deferred until rules use it
        subscribedTypes = Arrays.stream(new ScoutingIntelType[]{
                ScoutingIntelType.POSTURE,
                ScoutingIntelType.TIMING_ALERT})
            .filter(t -> prefs.getOrDefault(ScoutingIntelPreferences.consumerKey(getId(), t)).asBoolean())
            .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public Set<ScoutingIntelType> subscribedIntelTypes() { return subscribedTypes; }

    @Inject
    public DroolsStrategyTask(RuleUnit<StrategyRuleUnit> ruleUnit, IntentQueue intentQueue,
                               ScoutingIntelBroker broker) {
        this.ruleUnit    = ruleUnit;
        this.intentQueue = intentQueue;
        this.broker      = broker;
    }

    @Override public String getId()   { return "strategy.drools"; }
    @Override public String getName() { return "Drools Strategy"; }
    @Override public Set<String> entryCriteria() {
        // ENEMY_ARMY_SIZE: ordering dependency — scouting always writes this (even as 0),
        // ensuring strategy runs after scouting in the CaseEngine re-evaluation loop (L5 invariant)
        return Set.of(QuarkMindCaseFile.READY, QuarkMindCaseFile.ENEMY_ARMY_SIZE);
    }
    @Override public Set<String> producedKeys()  { return Set.of(QuarkMindCaseFile.STRATEGY); }

    /**
     * Overrides the {@code TaskDefinition} default, which unconditionally returns {@code true}
     * in the installed casehub-core snapshot — ignoring {@link #entryCriteria()}.
     * Override required until the foundation corrects the default.
     * Also gates on broker having a posture value (Stack 1 in-memory delivery).
     */
    @Override
    public boolean canActivate(CaseFile caseFile) {
        return entryCriteria().stream().allMatch(caseFile::contains)
            && broker.current(ScoutingIntelType.POSTURE).isPresent();
    }

    @Override
    @SuppressWarnings("unchecked")
    public void execute(CaseFile caseFile) {
        int armySize = caseFile.get(QuarkMindCaseFile.ENEMY_ARMY_SIZE, Integer.class).orElse(0);
        // Read posture and timing from broker (Stack 1) — CaseFile writes are observability only
        String posture = broker.current(ScoutingIntelType.POSTURE,
                ScoutingIntelPayload.PostureUpdate.class)
            .map(ScoutingIntelPayload.PostureUpdate::posture)
            .orElse("UNKNOWN");
        boolean timing = broker.current(ScoutingIntelType.TIMING_ALERT,
                ScoutingIntelPayload.TimingAlert.class)
            .map(ScoutingIntelPayload.TimingAlert::incoming)
            .orElse(false);
        List<Unit>     workers   = (List<Unit>)     caseFile.get(QuarkMindCaseFile.WORKERS,      List.class).orElse(List.of());
        List<Unit>     army      = (List<Unit>)     caseFile.get(QuarkMindCaseFile.ARMY,         List.class).orElse(List.of());
        List<Building> buildings = (List<Building>) caseFile.get(QuarkMindCaseFile.MY_BUILDINGS, List.class).orElse(List.of());
        List<Resource> geysers   = (List<Resource>) caseFile.get(QuarkMindCaseFile.GEYSERS,      List.class).orElse(List.of());
        ResourceBudget budget    = caseFile.get(QuarkMindCaseFile.RESOURCE_BUDGET, ResourceBudget.class)
            .orElse(new ResourceBudget(0, 0));

        StrategyRuleUnit data = buildRuleUnit(workers, army, buildings, geysers, posture, timing);

        try (RuleUnitInstance<StrategyRuleUnit> instance = ruleUnit.createInstance(data)) {
            instance.fire();
        }

        dispatchBuildDecisions(data.getBuildDecisions(), budget, workers, buildings, geysers);

        String strategy = data.getStrategyDecisions().stream().findFirst().orElse("MACRO");
        caseFile.put(QuarkMindCaseFile.STRATEGY, strategy);

        log.debugf("[DROOLS-STRATEGY] %s | posture=%s | timing=%b | armySize=%d | builds=%s | %s",
            strategy, posture, timing, armySize, data.getBuildDecisions(), budget);
        if (!Objects.equals(strategy, prevStrategy)) {
            prevStrategy = strategy;
            int frame = caseFile.get(QuarkMindCaseFile.GAME_FRAME, Long.class)
                    .map(Long::intValue).orElse(0);
            decisionEvents.fireAsync(new PluginDecisionEvent(
                    getId(), QuarkMindCapabilityTag.STRATEGY,
                    AttestationVerdict.SOUND, gameSession.id(), frame));
        }
    }

    private StrategyRuleUnit buildRuleUnit(List<Unit> workers, List<Unit> army,
                                           List<Building> buildings, List<Resource> geysers,
                                           String posture, boolean timing) {
        StrategyRuleUnit data = new StrategyRuleUnit();

        data.getPostureStore().add(posture);
        data.getTimingStore().add(timing);

        workers.stream().findFirst().ifPresent(data.getBuilders()::add);
        army.forEach(data.getArmy()::add);
        buildings.forEach(data.getBuildings()::add);

        firstFreeGeyser(buildings, geysers).ifPresent(data.getGeysers()::add);

        return data;
    }

    /**
     * Processes Drools build decisions in priority order, enforcing the budget.
     * Rules fire declaratively; budget + intent dispatch happen here in Java.
     * Handles: GATEWAY, CYBERNETICS_CORE, STALKER, ASSIMILATOR.
     */
    private void dispatchBuildDecisions(List<String> decisions, ResourceBudget budget,
                                        List<Unit> workers, List<Building> buildings,
                                        List<Resource> geysers) {
        for (String decision : decisions) {
            if (decision.equals("GATEWAY") && budget.spendMinerals(150)) {
                workers.stream().findFirst().ifPresent(p ->
                    intentQueue.add(new BuildIntent(p.tag(), BuildingType.GATEWAY, GATEWAY_POS)));

            } else if (decision.equals("CYBERNETICS_CORE") && budget.spendMinerals(150)) {
                workers.stream().findFirst().ifPresent(p ->
                    intentQueue.add(new BuildIntent(p.tag(), BuildingType.CYBERNETICS_CORE, CYBERNETICS_CORE_POS)));

            } else if (decision.equals("ASSIMILATOR")) {
                firstFreeGeyser(buildings, geysers).ifPresent(g -> {
                    if (budget.spendMinerals(75)) {
                        workers.stream().findFirst().ifPresent(p ->
                            intentQueue.add(new BuildIntent(p.tag(), BuildingType.ASSIMILATOR, g.position())));
                    }
                });

            } else if (decision.startsWith("STALKER:") && budget.spend(125, 50)) {
                String gatewayTag = decision.substring("STALKER:".length());
                intentQueue.add(new TrainIntent(gatewayTag, UnitType.STALKER));
            }
        }
    }

    static Optional<Resource> firstFreeGeyser(List<Building> buildings, List<Resource> geysers) {
        Set<Point2d> occupied = buildings.stream()
            .filter(b -> b.type() == BuildingType.ASSIMILATOR)
            .map(Building::position)
            .collect(Collectors.toSet());
        return geysers.stream()
            .filter(g -> !occupied.contains(g.position()))
            .findFirst();
    }
}

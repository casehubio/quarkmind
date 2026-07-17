package io.quarkmind.plugin;

import io.casehub.annotation.CaseType;
import io.casehub.api.context.CaseContext;
import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.Preferences;
import io.casehub.platform.api.preferences.SettingsScope;
import io.quarkmind.agent.GameSession;
import io.quarkmind.agent.PluginDecisionEvent;
import io.quarkmind.agent.QuarkMindCapabilityTag;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.ResourceBudget;
import io.quarkmind.agent.ScoutingIntelBroker;
import io.quarkmind.agent.plugin.ScoutingIntelConsumer;
import io.quarkmind.agent.plugin.ScoutingIntelPayload;
import io.quarkmind.agent.plugin.ScoutingIntelPreferences;
import io.quarkmind.agent.plugin.ScoutingIntelType;
import io.quarkmind.agent.plugin.StrategyTask;
import io.quarkmind.domain.Building;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.Resource;
import io.quarkmind.domain.Unit;
import io.quarkmind.domain.UnitType;
import io.quarkmind.plugin.drools.AdvisoryFact;
import io.quarkmind.plugin.drools.StrategyRuleUnit;
import io.quarkmind.plugin.summarisation.GameMoment;
import io.quarkmind.plugin.summarisation.GameMomentType;
import io.quarkmind.plugin.summarisation.GamePhase;
import io.quarkmind.plugin.summarisation.MomentBroker;
import io.quarkmind.plugin.summarisation.MomentConsumer;
import io.quarkmind.plugin.summarisation.SummarisationLifecycle;
import io.quarkmind.sc2.IntentQueue;
import io.quarkmind.sc2.intent.BuildIntent;
import io.quarkmind.sc2.intent.TrainIntent;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.drools.ruleunits.api.RuleUnit;
import org.drools.ruleunits.api.RuleUnitInstance;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Drools-backed {@link StrategyTask} — first real R&D integration.
 *
 * <p>Each tick, game state from the CaseContext is loaded into a {@link StrategyRuleUnit}
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
public class DroolsStrategyTask implements StrategyTask, ScoutingIntelConsumer, MomentConsumer {

    static final Point2d GATEWAY_POS          = new Point2d(17, 18);
    static final Point2d CYBERNETICS_CORE_POS = new Point2d(20, 18);

    /** Advisory staleness threshold — 400 frames ≈ 33 seconds at 12 frames/sec. */
    private static final long STALENESS_THRESHOLD = 400L;

    private static final Logger log = Logger.getLogger(DroolsStrategyTask.class);

    private final RuleUnit<StrategyRuleUnit> ruleUnit;
    private final IntentQueue intentQueue;
    private final ScoutingIntelBroker broker;

    
    @Inject Event<PluginDecisionEvent> decisionEvents;
    @Inject GameSession gameSession;
    @Inject PreferenceProvider preferenceProvider;
    @Inject Instance<MomentBroker> momentBroker;
    @Inject Instance<SummarisationLifecycle> summarisationLifecycle;

    private volatile String prevStrategy = null;

    // Safe default before @PostConstruct fires
    Set<ScoutingIntelType> subscribedTypes = Set.of();

    // Level 2/3 state
    private final List<GameMoment> pendingMoments = new ArrayList<>();
    private volatile GamePhase currentPhase = null;
    private volatile boolean summarisationInitialized = false;

    @PostConstruct
    void init() {
        refreshSubscriptions(preferenceProvider.resolve(SettingsScope.root()));
        // Don't initialize summarisation subscriptions here — causes circular dependency
        // with MomentBroker. Initialize lazily on first execute() call.
    }

    private void ensureSummarisationInitialized() {
        if (!summarisationInitialized) {
            synchronized (this) {
                if (!summarisationInitialized) {
                    // Subscribe to Level 2 moments — auto-discovered by MomentBroker's CDI bridge,
                    // but we need to capture them for execute() feeding.
                    // Use Instance<> to break circular dependency: MomentBroker injects consumers,
                    // consumers inject MomentBroker — lazy resolution via .get() defers until
                    // both beans are created. Called from execute(), not @PostConstruct.
                    momentBroker.get().momentBus().subscribe(eventFilter(), e -> {
                        synchronized (pendingMoments) {
                            pendingMoments.add(e.payload());
                        }
                    });

                    // Subscribe to Level 3 phases
                    summarisationLifecycle.get().phaseBus().subscribe(p -> true, e -> {
                        currentPhase = e.payload();
                    });

                    summarisationInitialized = true;
                }
            }
        }
    }

    @Override
    public void refreshSubscriptions(Preferences prefs) {
        subscribedTypes = Arrays.stream(new ScoutingIntelType[]{
                                        ScoutingIntelType.POSTURE,
                                        ScoutingIntelType.TIMING_ALERT,
                                        ScoutingIntelType.PATTERN_ASSESSMENT})
                                .filter(t -> prefs.getOrDefault(ScoutingIntelPreferences.consumerKey(getId(), t)).asBoolean())
                                .collect(Collectors.toUnmodifiableSet());}

    @Override
    public Set<ScoutingIntelType> subscribedIntelTypes() { return subscribedTypes; }

    @Override
    public Set<GameMomentType> subscribedMomentTypes() {
        return Set.of(
            GameMomentType.BATTLE_STARTED,
            GameMomentType.BATTLE_ENDED,
            GameMomentType.ECONOMIC_CRISIS,
            GameMomentType.NEXUS_UNDER_ATTACK);
    }

    @Inject
    public DroolsStrategyTask(RuleUnit<StrategyRuleUnit> ruleUnit, IntentQueue intentQueue,
                               ScoutingIntelBroker broker) {
        this.ruleUnit    = ruleUnit;
        this.intentQueue = intentQueue;
        this.broker      = broker;
    }

    /** Resets transition-detection state. Called from @QuarkusTest @BeforeEach to prevent leakage. */
    public void resetPrevStrategy() {
        prevStrategy = null;
        synchronized (pendingMoments) {
            pendingMoments.clear();
        }
        currentPhase = null;
    }

    @Override public String getId()   { return "strategy.drools"; }
    @Override public String getName() { return "Drools Strategy"; }

    // ── New engine API ───────────────────────────────────────────────────────

    @Override
    public Set<String> requires() {
        // ENEMY_ARMY_SIZE: ordering dependency — scouting always writes this (even as 0),
        // ensuring strategy runs after scouting in the CaseEngine re-evaluation loop (L5 invariant)
        return Set.of(QuarkMindCaseFile.READY, QuarkMindCaseFile.ENEMY_ARMY_SIZE);
    }

    @Override
    public Predicate<CaseContext> activateIf() {
        return ctx -> getId().equals(
                ctx.getString(QuarkMindCaseFile.STRATEGY_SELECTED_ID));
    }

    @Override
    @SuppressWarnings("unchecked")
    public void execute(final CaseContext ctx) {
        ensureSummarisationInitialized();

        int armySize = ctx.getOrDefault(QuarkMindCaseFile.ENEMY_ARMY_SIZE, 0);
        // Read posture and timing from broker (Stack 1) — context writes are observability only
        String posture = broker.current(ScoutingIntelType.POSTURE,
                ScoutingIntelPayload.PostureUpdate.class)
            .map(ScoutingIntelPayload.PostureUpdate::posture)
            .orElse("UNKNOWN");
        boolean timing = broker.current(ScoutingIntelType.TIMING_ALERT,
                ScoutingIntelPayload.TimingAlert.class)
            .map(ScoutingIntelPayload.TimingAlert::incoming)
            .orElse(false);
        List<Unit>     workers   = ctx.getList(QuarkMindCaseFile.WORKERS,      Unit.class);
        List<Unit>     army      = ctx.getList(QuarkMindCaseFile.ARMY,         Unit.class);
        List<Building> buildings = ctx.getList(QuarkMindCaseFile.MY_BUILDINGS, Building.class);
        List<Resource> geysers   = ctx.getList(QuarkMindCaseFile.GEYSERS,      Resource.class);
        ResourceBudget budget    = ctx.getOrDefault(QuarkMindCaseFile.RESOURCE_BUDGET, new ResourceBudget(0, 0));
        long currentFrame        = ctx.getAs(QuarkMindCaseFile.GAME_FRAME, Long.class);

        StrategyRuleUnit data = buildRuleUnit(workers, army, buildings, geysers, posture, timing, ctx, currentFrame);

        try (RuleUnitInstance<StrategyRuleUnit> instance = ruleUnit.createInstance(data)) {
            instance.fire();
        }

        dispatchBuildDecisions(data.getBuildDecisions(), budget, workers, buildings, geysers);

        String strategy = data.getStrategyDecisions().stream().findFirst().orElse("MACRO");
        ctx.set(QuarkMindCaseFile.STRATEGY, strategy);

        log.debugf("[DROOLS-STRATEGY] %s | posture=%s | timing=%b | armySize=%d | builds=%s | %s",
            strategy, posture, timing, armySize, data.getBuildDecisions(), budget);
        if (!Objects.equals(strategy, prevStrategy)) {
            prevStrategy = strategy;
            Long frame = ctx.getAs(QuarkMindCaseFile.GAME_FRAME, Long.class);
            decisionEvents.fireAsync(new PluginDecisionEvent(
                    getId(), QuarkMindCapabilityTag.STRATEGY,
                    AttestationVerdict.SOUND, gameSession.id(),
                    frame != null ? frame.intValue() : 0));
        }
    }

    @Override
    public Set<String> produces() { return Set.of(QuarkMindCaseFile.STRATEGY); }

    // ── Private helpers ──────────────────────────────────────────────────────

    private StrategyRuleUnit buildRuleUnit(List<Unit> workers, List<Unit> army,
                                           List<Building> buildings, List<Resource> geysers,
                                           String posture, boolean timing, CaseContext ctx, long currentFrame) {
        StrategyRuleUnit data = new StrategyRuleUnit();
        data.getPostureStore().add(posture);
        data.getTimingStore().add(timing);
        workers.stream().findFirst().ifPresent(data.getBuilders()::add);
        army.forEach(data.getArmy()::add);
        buildings.forEach(data.getBuildings()::add);
        firstFreeGeyser(buildings, geysers).ifPresent(data.getGeysers()::add);

        synchronized (pendingMoments) {
            pendingMoments.forEach(data.getMomentStore()::add);
            pendingMoments.clear();
        }

        if (currentPhase != null) {
            data.getPhaseStore().add(currentPhase);
        }

        broker.current(ScoutingIntelType.PATTERN_ASSESSMENT,
                       ScoutingIntelPayload.PatternAssessment.class)
              .map(ScoutingIntelPayload.PatternAssessment::assessments)
              .ifPresent(list -> list.forEach(data.getPatternStore()::add));

        feedAdvisoryFacts(ctx, currentFrame, data);

        return data;}

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
                intentQueue.add(new TrainIntent(decision.substring("STALKER:".length()), UnitType.STALKER));
            }
        }
    }

    /**
     * Reads advisory output from CaseContext and adds non-stale facts to the rule unit.
     *
     * <p>Checks all known advisory roles (crisis, strategic, economic) and adds facts
     * for any that are present and not stale (age < STALENESS_THRESHOLD).
     */
    private void feedAdvisoryFacts(CaseContext ctx, long currentFrame, StrategyRuleUnit data) {
        String[] roles = {"crisis", "strategic", "economic"};
        for (String role : roles) {
            String keyPrefix = "agent.advisory." + role + ".";
            String recommendation = ctx.getAs(keyPrefix + "recommendation", String.class);
            Long timestamp = ctx.getAs(keyPrefix + "timestamp", Long.class);
            String agentId = ctx.getAs(keyPrefix + "agent_id", String.class);
            String confidenceStr = ctx.getAs(keyPrefix + "confidence", String.class);

            if (recommendation != null && timestamp != null) {
                long age = currentFrame - timestamp;
                if (age < STALENESS_THRESHOLD) {
                    double confidence = parseConfidence(confidenceStr);
                    AdvisoryFact fact = new AdvisoryFact(role, recommendation, confidence, agentId, age);
                    data.getAdvisoryStore().add(fact);
                    log.debugf("[STRATEGY] Consuming %s advisory: %s (age %d frames, confidence %.2f)",
                        role, recommendation, age, confidence);
                } else {
                    log.debugf("[STRATEGY] Ignoring stale %s advisory (age %d frames)", role, age);
                }
            }
        }
    }

    /** Parses confidence string to double — handles null and malformed values. */
    private static double parseConfidence(String confidenceStr) {
        if (confidenceStr == null) return 0.0;
        try {
            return Double.parseDouble(confidenceStr);
        } catch (NumberFormatException e) {
            return 0.0;
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

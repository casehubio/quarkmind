package io.quarkmind.plugin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.annotation.CaseType;
import io.casehub.core.CaseFile;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.Preferences;
import io.casehub.platform.api.preferences.SettingsScope;
import io.casehub.qhorus.api.gateway.MessageObserver;
import io.casehub.qhorus.api.gateway.MessageReceivedEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.ScoutingIntelBroker;
import io.quarkmind.agent.plugin.ScoutingIntelConsumer;
import io.quarkmind.agent.plugin.ScoutingIntelPayload;
import io.quarkmind.agent.plugin.ScoutingIntelPreferences;
import io.quarkmind.agent.plugin.ScoutingIntelType;
import io.quarkmind.agent.plugin.TacticsTask;
import io.quarkmind.domain.*;
import io.quarkmind.plugin.drools.TacticsRuleUnit;
import io.quarkmind.plugin.tactics.FocusFireStrategy;
import io.quarkmind.plugin.tactics.GoapAction;
import io.quarkmind.plugin.tactics.GoapPlanner;
import io.quarkmind.plugin.tactics.KiteStrategy;
import io.quarkmind.plugin.tactics.WorldState;
import io.quarkmind.sc2.IntentQueue;
import io.quarkmind.sc2.TerrainProvider;
import io.quarkmind.sc2.intent.AttackIntent;
import io.quarkmind.sc2.intent.BlinkIntent;
import io.quarkmind.sc2.intent.MoveIntent;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.literal.NamedLiteral;
import org.drools.ruleunits.api.RuleUnit;
import org.drools.ruleunits.api.RuleUnitInstance;
import org.jboss.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.casehub.ledger.api.model.AttestationVerdict;
import io.quarkmind.agent.GameSession;
import io.quarkmind.agent.PluginDecisionEvent;
import io.quarkmind.sc2.GameStarted;
import io.quarkmind.agent.QuarkMindCapabilityTag;
import jakarta.enterprise.event.Event;
import java.util.*;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Drools-backed GOAP {@link TacticsTask} — third R&D integration.
 *
 * <p>Each tick:
 * <ol>
 *   <li>DEFEND: bypasses GOAP — emits {@link MoveIntent} to Nexus for all units.</li>
 *   <li>ATTACK: fires {@link TacticsRuleUnit} to classify units into groups (Phase 1)
 *       and emit applicable action names (Phase 2).</li>
 *   <li>Java A* ({@link GoapPlanner}) finds the cheapest action sequence per group.</li>
 *   <li>First action in each plan is dispatched as an Intent.</li>
 * </ol>
 *
 * <p>Replaces {@link BasicTacticsTask} as the active CDI bean.
 */
@ApplicationScoped
@CaseType("starcraft-game")
public class DroolsTacticsTask implements TacticsTask, ScoutingIntelConsumer, MessageObserver {

    static final Point2d MAP_CENTER   = new Point2d(64, 64);

    private static final Map<String, GoapAction> ACTION_TEMPLATES = Map.of(
        "RETREAT",        new GoapAction("RETREAT",
            Map.of("lowHealth", true),
            Map.of("unitSafe", true), 1),
        "ATTACK",         new GoapAction("ATTACK",
            Map.of("inRange", true, "enemyVisible", true, "onCooldown", false),
            Map.of("enemyEliminated", true), 2),
        "MOVE_TO_ENGAGE", new GoapAction("MOVE_TO_ENGAGE",
            Map.of("enemyVisible", true, "inRange", false),
            Map.of("inRange", true), 1),
        "KITE",           new GoapAction("KITE",
            Map.of("inRange", true, "onCooldown", true, "enemyVisible", true),
            Map.of("onCooldown", false), 1),
        "BLINK",          new GoapAction("BLINK",
            Map.of("shieldsLow", true, "blinkReady", true, "enemyVisible", true),
            Map.of("shieldsLow", false, "inRange", false, "blinkReady", false), 1)
    );

    private static final Logger log = Logger.getLogger(DroolsTacticsTask.class);

    private final RuleUnit<TacticsRuleUnit> ruleUnit;
    private final IntentQueue intentQueue;
    private final GoapPlanner planner = new GoapPlanner();

    @Inject Event<PluginDecisionEvent> decisionEvents;
    @Inject ObjectMapper objectMapper;
    @Inject PreferenceProvider preferenceProvider;
    @Inject GameSession gameSession;

    final AtomicReference<TacticsIntelCache> intelCache =
        new AtomicReference<>(TacticsIntelCache.empty());

    /** Test accessor — package-private; returns current cache via the CDI proxy. */
    TacticsIntelCache currentIntelCache() { return intelCache.get(); }

    /** Clears the intel cache when a new game starts — ensures no cross-game state bleed. */
    void onGameStarted(@Observes GameStarted event) { intelCache.set(TacticsIntelCache.empty()); }

    Set<ScoutingIntelType> subscribedTypes;
    private volatile String prevThreatState = null;

    @Inject
    @ConfigProperty(name = "quarkmind.tactics.kite.strategy", defaultValue = "direct")
    String kiteStrategyName;

    @Inject
    @ConfigProperty(name = "quarkmind.tactics.focus-fire.strategy", defaultValue = "lowest-hp")
    String focusFireStrategyName;

    @Inject Instance<KiteStrategy>      kiteStrategies;
    @Inject Instance<FocusFireStrategy> focusFireStrategies;
    @Inject TerrainProvider             terrainProvider;

    private KiteStrategy      kiteStrategy;
    private FocusFireStrategy focusFireStrategy;

    @PostConstruct
    void init() {
        kiteStrategy      = kiteStrategies.select(NamedLiteral.of(kiteStrategyName)).get();
        focusFireStrategy = focusFireStrategies.select(NamedLiteral.of(focusFireStrategyName)).get();
        initSubscriptions(preferenceProvider.resolve(SettingsScope.root()));
    }

    void initSubscriptions(Preferences prefs) {
        subscribedTypes = Arrays.stream(ScoutingIntelType.values())
            .filter(t -> prefs.getOrDefault(ScoutingIntelPreferences.consumerKey(getId(), t)).asBoolean())
            .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public Set<ScoutingIntelType> subscribedIntelTypes() { return subscribedTypes; }

    @Override
    public Set<String> channels() { return Set.of(ScoutingIntelBroker.CHANNEL_NAME); }

    @Override
    public void onMessage(MessageReceivedEvent event) {
        try {
            JsonNode node = objectMapper.readTree(event.content());
            JsonNode typeNode = node == null ? null : node.get("type");
            JsonNode dataNode = node == null ? null : node.get("data");
            if (typeNode == null || dataNode == null) {
                log.warnf("Malformed scouting intel message — missing 'type' or 'data': %s", event.content());
                return;
            }
            String type = typeNode.asText();
            ScoutingIntelPayload payload = switch (type) {
                case "ThreatPosition" ->
                    objectMapper.treeToValue(dataNode, ScoutingIntelPayload.ThreatPosition.class);
                case "PostureUpdate" ->
                    objectMapper.treeToValue(dataNode, ScoutingIntelPayload.PostureUpdate.class);
                case "TimingAlert" ->
                    objectMapper.treeToValue(dataNode, ScoutingIntelPayload.TimingAlert.class);
                case "ArmySize" ->
                    objectMapper.treeToValue(dataNode, ScoutingIntelPayload.ArmySize.class);
                case "BuildOrder" ->
                    objectMapper.treeToValue(dataNode, ScoutingIntelPayload.BuildOrder.class);
                default -> throw new IllegalArgumentException("Unknown ScoutingIntelType: " + type);
            };
            intelCache.updateAndGet(prev -> merge(prev, payload));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.warnf("Failed to deserialise scouting intel: %s", e.getMessage());
        }
    }

    static TacticsIntelCache merge(TacticsIntelCache prev, ScoutingIntelPayload payload) {
        return switch (payload) {
            case ScoutingIntelPayload.ThreatPosition p ->
                new TacticsIntelCache(p.position(), prev.posture(), prev.timingAlert());
            case ScoutingIntelPayload.PostureUpdate p ->
                new TacticsIntelCache(prev.threatPosition(), p.posture(), prev.timingAlert());
            case ScoutingIntelPayload.TimingAlert p ->
                new TacticsIntelCache(prev.threatPosition(), prev.posture(), p.incoming());
            // ArmySize and BuildOrder are not cached in TacticsIntelCache — tactics doesn't use them.
            // Future consumers (e.g. StrategyTask via #177) would extend this via their own caches.
            case ScoutingIntelPayload.ArmySize p -> prev;
            case ScoutingIntelPayload.BuildOrder p -> prev;
        };
    }

    @Inject
    public DroolsTacticsTask(RuleUnit<TacticsRuleUnit> ruleUnit, IntentQueue intentQueue) {
        this.ruleUnit    = ruleUnit;
        this.intentQueue = intentQueue;
    }

    @Override public String getId()   { return "tactics.drools-goap"; }
    @Override public String getName() { return "Drools GOAP Tactics"; }
    @Override public Set<String> entryCriteria() {
        return Set.of(QuarkMindCaseFile.READY, QuarkMindCaseFile.STRATEGY);
    }
    @Override public Set<String> producedKeys()  { return Set.of(); }

    /**
     * Overrides the {@code TaskDefinition} default, which unconditionally returns {@code true}
     * in the installed casehub-core snapshot — ignoring {@link #entryCriteria()}.
     * Override required until the foundation corrects the default.
     * Also gates on intel cache having a threat position (Layer 3 qhorus channel).
     */
    @Override
    public boolean canActivate(CaseFile caseFile) {
        return entryCriteria().stream().allMatch(caseFile::contains)
            && intelCache.get().threatPosition() != null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void execute(CaseFile caseFile) {
        String strategy    = caseFile.get(QuarkMindCaseFile.STRATEGY,      String.class).orElse("MACRO");
        List<Unit> army    = (List<Unit>)     caseFile.get(QuarkMindCaseFile.ARMY,         List.class).orElse(List.of());
        List<Unit> enemies = (List<Unit>)     caseFile.get(QuarkMindCaseFile.ENEMY_UNITS,  List.class).orElse(List.of());
        List<Building> bld = (List<Building>) caseFile.get(QuarkMindCaseFile.MY_BUILDINGS, List.class).orElse(List.of());
        // Reads from qhorus intel cache (Layer 3) — no CaseFile key coupling
        Point2d threat = intelCache.get().threatPosition();

        String threatState = enemies.isEmpty() ? "none" : "present";
        if (!Objects.equals(threatState, prevThreatState)) {
            prevThreatState = threatState;
            int frame = caseFile.get(QuarkMindCaseFile.GAME_FRAME, Long.class)
                    .map(Long::intValue).orElse(0);
            decisionEvents.fireAsync(new PluginDecisionEvent(
                    getId(), QuarkMindCapabilityTag.TACTICS,
                    AttestationVerdict.SOUND, gameSession.id(), frame));
        }

        if (army.isEmpty()) return;

        if ("DEFEND".equals(strategy)) {
            dispatchDefend(army, bld);
            return;
        }

        if (!"ATTACK".equals(strategy)) return;

        if (enemies.isEmpty()) return;

        // canActivate gates on intelCache.threatPosition() != null.
        // Defensive guard for test paths that bypass canActivate.
        if (threat == null) return;

        Set<String> inRangeSet    = computeInRangeTags(army, enemies);
        Set<String> onCooldownSet = computeOnCooldownTags(army);
        Set<String> blinkReadySet  = computeBlinkReadyTags(army);
        Set<String> shieldsLowSet  = computeShieldsLowTags(army);

        TacticsRuleUnit data = buildRuleUnit(army, enemies, inRangeSet, onCooldownSet, blinkReadySet, shieldsLowSet, strategy);
        try (RuleUnitInstance<TacticsRuleUnit> instance = ruleUnit.createInstance(data)) {
            instance.fire();
        }

        Map<String, GroupInfo> groups = parseGroups(data.getGroupDecisions());

        if (groups.isEmpty()) return;

        List<GoapAction> allActions = List.copyOf(ACTION_TEMPLATES.values());
        TerrainGrid terrain = terrainProvider.get();

        for (Map.Entry<String, GroupInfo> entry : groups.entrySet()) {
            String    groupId   = entry.getKey();
            GroupInfo groupInfo = entry.getValue();
            WorldState ws       = buildWorldState(groupId, !enemies.isEmpty());
            List<GoapAction> plan = planner.plan(ws, groupInfo.goalCondition(), allActions);
            if (!plan.isEmpty()) {
                dispatch(plan.get(0), groupInfo.unitTags(), army, enemies, threat, bld, terrain);
            }
            log.debugf("[DROOLS-GOAP] group=%s goal=%s plan=%s units=%d",
                groupId, groupInfo.goalCondition(),
                plan.stream().map(GoapAction::name).toList(),
                groupInfo.unitTags().size());
        }
    }

    static Set<String> computeInRangeTags(List<Unit> army, List<Unit> enemies) {
        Set<String> result = new HashSet<>();
        for (Unit unit : army) {
            for (Unit enemy : enemies) {
                if (distance(unit.position(), enemy.position()) <= SC2Data.attackRange(unit.type())) {
                    result.add(unit.tag());
                    break;
                }
            }
        }
        return result;
    }

    static double distance(Point2d a, Point2d b) {
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        return Math.sqrt(dx * dx + dy * dy);
    }

    static Set<String> computeOnCooldownTags(List<Unit> army) {
        return army.stream()
            .filter(u -> u.weaponCooldownTicks() > 0)
            .map(Unit::tag)
            .collect(Collectors.toSet());
    }

    static Set<String> computeBlinkReadyTags(List<Unit> army) {
        return army.stream()
            .filter(u -> u.type() == UnitType.STALKER && u.blinkCooldownTicks() == 0)
            .map(Unit::tag)
            .collect(Collectors.toSet());
    }

    static Set<String> computeShieldsLowTags(List<Unit> army) {
        return army.stream()
            .filter(u -> u.shields() < u.maxShields() * 0.25)
            .map(Unit::tag)
            .collect(Collectors.toSet());
    }

    private TacticsRuleUnit buildRuleUnit(List<Unit> army, List<Unit> enemies,
                                           Set<String> inRangeTags, Set<String> onCooldownTags,
                                           Set<String> blinkReadyTags, Set<String> shieldsLowTags,
                                           String strategy) {
        TacticsRuleUnit data = new TacticsRuleUnit();
        data.setStrategyGoal(strategy);
        army.forEach(data.getArmy()::add);
        enemies.forEach(data.getEnemies()::add);
        inRangeTags.forEach(data.getInRangeTags()::add);
        onCooldownTags.forEach(data.getOnCooldownTags()::add);
        blinkReadyTags.forEach(data.getBlinkReadyTags()::add);
        shieldsLowTags.forEach(data.getShieldsLowTags()::add);
        return data;
    }

    private Map<String, GroupInfo> parseGroups(List<String> groupDecisions) {
        Map<String, GroupInfo> groups = new LinkedHashMap<>();
        for (String decision : groupDecisions) {
            String[] parts = decision.split(":", 3);
            if (parts.length < 3) {
                log.warnf("[DROOLS-GOAP] Malformed group decision ignored: %s", decision);
                continue;
            }
            String groupId = parts[0];
            String goalKey = goalConditionKey(parts[1]);
            String unitTag = parts[2];
            groups.computeIfAbsent(groupId, k -> new GroupInfo(goalKey, new ArrayList<>()))
                  .unitTags().add(unitTag);
        }
        return groups;
    }

    private String goalConditionKey(String goalName) {
        return switch (goalName) {
            case "UNIT_SAFE"        -> "unitSafe";
            case "ENEMY_ELIMINATED" -> "enemyEliminated";
            case "KITING"           -> "enemyEliminated"; // plan: KITE → ATTACK
            case "BLINKING"         -> "enemyEliminated"; // plan: BLINK → MOVE_TO_ENGAGE → ATTACK
            default                 -> goalName.toLowerCase();
        };
    }

    private WorldState buildWorldState(String groupId, boolean enemyVisible) {
        return switch (groupId) {
            case "low-health"   -> new WorldState(Map.of(
                "lowHealth",       true,
                "enemyVisible",    enemyVisible,
                "inRange",         false,
                "unitSafe",        false,
                "enemyEliminated", false));
            case "in-range"     -> new WorldState(Map.of(
                "lowHealth",       false,
                "enemyVisible",    true,
                "inRange",         true,
                "onCooldown",      false,
                "unitSafe",        false,
                "enemyEliminated", false));
            case "out-of-range" -> new WorldState(Map.of(
                "lowHealth",       false,
                "enemyVisible",    true,
                "inRange",         false,
                "unitSafe",        false,
                "enemyEliminated", false));
            case "kiting" -> new WorldState(Map.of(
                "lowHealth",       false,
                "enemyVisible",    true,
                "inRange",         true,
                "onCooldown",      true,
                "unitSafe",        false,
                "enemyEliminated", false));
            case "blinking" -> new WorldState(Map.of(
                "shieldsLow",      true,
                "blinkReady",      true,
                "enemyVisible",    enemyVisible,
                "inRange",         true,
                "onCooldown",      false,
                "lowHealth",       false,
                "unitSafe",        false,
                "enemyEliminated", false));
            default             -> new WorldState(Map.of("enemyEliminated", false));
        };
    }

    private void dispatch(GoapAction action, List<String> unitTags,
                          List<Unit> army, List<Unit> enemies,
                          Point2d threat, List<Building> buildings,
                          TerrainGrid terrain) {
        switch (action.name()) {
            case "ATTACK" -> {
                List<Unit> attackers = army.stream()
                    .filter(u -> unitTags.contains(u.tag())).toList();
                Map<String, Point2d> targets = focusFireStrategy.assignTargets(attackers, enemies);
                unitTags.forEach(tag -> {
                    Point2d target = targets.getOrDefault(tag, threat);
                    intentQueue.add(new AttackIntent(tag, target));
                });
            }
            case "MOVE_TO_ENGAGE" -> {
                unitTags.forEach(tag -> intentQueue.add(new MoveIntent(tag, threat)));
            }
            case "RETREAT" -> {
                Point2d rally = buildings.stream()
                    .filter(b -> b.type() == BuildingType.NEXUS)
                    .findFirst()
                    .map(Building::position)
                    .orElse(MAP_CENTER);
                unitTags.forEach(tag -> intentQueue.add(new MoveIntent(tag, rally)));
            }
            case "KITE" -> {
                unitTags.forEach(tag ->
                    army.stream().filter(u -> u.tag().equals(tag)).findFirst()
                        .ifPresent(unit -> intentQueue.add(
                            new MoveIntent(tag, kiteStrategy.retreatTarget(unit, enemies, terrain)))));
            }
            case "BLINK" -> {
                unitTags.forEach(tag -> intentQueue.add(new BlinkIntent(tag)));
            }
        }
    }

    private void dispatchDefend(List<Unit> army, List<Building> buildings) {
        Point2d rally = buildings.stream()
            .filter(b -> b.type() == BuildingType.NEXUS)
            .findFirst()
            .map(Building::position)
            .orElse(MAP_CENTER);
        army.forEach(unit -> intentQueue.add(new MoveIntent(unit.tag(), rally)));
    }

    private record GroupInfo(String goalCondition, List<String> unitTags) {}
}

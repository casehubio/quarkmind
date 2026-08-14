package io.quarkmind.agent;

import io.quarkmind.agent.plugin.ScoutingIntelPayload.PatternAssessmentPayload;
import io.quarkmind.agent.plugin.ScoutingIntelType;
import io.quarkmind.domain.*;
import io.quarkmind.plugin.summarisation.TacticalPosture;
import io.quarkmind.plugin.summarisation.SummarisationLifecycle;
import io.quarkmind.sc2.GameStarted;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

@ApplicationScoped
public class MultiFactorDominanceAssessor implements DominanceAssessor {

    private static final Logger log = Logger.getLogger(MultiFactorDominanceAssessor.class);

    private final DominanceWeightStrategy strategy;
    private final double maxExpectedEconomyDelta;
    private final int maxExpectedArmyDelta;
    private final double maxExpectedTechDelta;
    private final int maxExpectedBaseDelta;
    private final int maxExpectedMapControlDelta;
    private final int minEnemyVisibility;
    private final ScoutingIntelBroker broker;

    static final double EXPANSION_CONTROL_RADIUS = 10.0;

    private final Instance<SummarisationLifecycle> lazyLifecycle;
    private volatile TacticalPosture cachedPhase;
    private volatile boolean subscribed = false;
    private DominanceWeights lastWeights;

    @Inject
    MultiFactorDominanceAssessor(
            @Any Instance<DominanceWeightStrategy> strategies,
            Instance<SummarisationLifecycle> summarisationLifecycle,
            ScoutingIntelBroker broker,
            MilestoneConfig config) {
        String selectedId = config.dominance().weightStrategy();
        this.strategy = strategies.stream()
            .filter(s -> s.id().equals(selectedId))
            .reduce((a, b) -> { throw new IllegalStateException(
                "Duplicate DominanceWeightStrategy id: " + selectedId); })
            .orElseThrow(() -> new IllegalStateException(
                "No DominanceWeightStrategy with id '" + selectedId + "'"));
        this.lazyLifecycle = summarisationLifecycle;
        this.broker = broker;
        this.maxExpectedEconomyDelta = config.dominance().maxExpectedEconomyDelta();
        this.maxExpectedArmyDelta = config.dominance().maxExpectedArmyDelta();
        this.maxExpectedTechDelta = config.dominance().maxExpectedTechDelta();
        this.maxExpectedBaseDelta = config.dominance().maxExpectedBaseDelta();
        this.maxExpectedMapControlDelta = config.dominance().maxExpectedMapControlDelta();
        this.minEnemyVisibility = config.dominance().minEnemyVisibility();
    }

    MultiFactorDominanceAssessor(
            DominanceWeightStrategy strategy,
            double maxExpectedEconomyDelta, int maxExpectedArmyDelta,
            double maxExpectedTechDelta, int maxExpectedBaseDelta,
            int maxExpectedMapControlDelta, int minEnemyVisibility,
            ScoutingIntelBroker broker) {
        this.strategy = strategy;
        this.lazyLifecycle = null;
        this.broker = broker;
        this.subscribed = true;
        this.maxExpectedEconomyDelta = maxExpectedEconomyDelta;
        this.maxExpectedArmyDelta = maxExpectedArmyDelta;
        this.maxExpectedTechDelta = maxExpectedTechDelta;
        this.maxExpectedBaseDelta = maxExpectedBaseDelta;
        this.maxExpectedMapControlDelta = maxExpectedMapControlDelta;
        this.minEnemyVisibility = minEnemyVisibility;
    }

    private void ensureSubscribed() {
        if (!subscribed) {
            synchronized (this) {
                if (!subscribed) {
                    lazyLifecycle.get().phaseBus().subscribe(
                        p -> true, e -> cachedPhase = e.payload());
                    subscribed = true;
                }
            }
        }
    }

    void onGameStarted(@Observes GameStarted event) {
        cachedPhase = null;
    }

    private static final DominanceScore NEUTRAL = new DominanceScore(0.0,
        Map.of("economy", 0.0, "army", 0.0, "tech", 0.0, "bases", 0.0, "mapControl", 0.0));

    @Override
    public DominanceScore assess(GameState state) {
        ensureSubscribed();
        int totalEnemyVisible = state.enemyUnits().size() + state.enemyBuildings().size();
        if (totalEnemyVisible < minEnemyVisibility) {
            return NEUTRAL;
        }

        double economy = economyFactor(state);
        double army = armyFactor(state);
        double tech = techFactor(state);
        double bases = basesFactor(state);
        double mapControl = mapControlFactor(state);

        TacticalPosture phase = cachedPhase;
        List<PatternAssessment> assessments = broker != null
            ? broker.current(ScoutingIntelType.PATTERN_ASSESSMENT,
                             PatternAssessmentPayload.class)
                .map(PatternAssessmentPayload::assessments)
                .orElse(List.of())
            : List.of();
        WeightContext ctx = new WeightContext(state.gameFrame(),
            phase != null ? phase.posture() : null,
            assessments);
        DominanceWeights weights = strategy.resolve(ctx);

        if (lastWeights != null && (
                Math.abs(weights.economy() - lastWeights.economy()) > 0.01
                || Math.abs(weights.army() - lastWeights.army()) > 0.01
                || Math.abs(weights.tech() - lastWeights.tech()) > 0.01
                || Math.abs(weights.bases() - lastWeights.bases()) > 0.01
                || Math.abs(weights.mapControl() - lastWeights.mapControl()) > 0.01)) {
            log.debugf("[DOMINANCE] Weights shifted: economy=%.2f army=%.2f tech=%.2f bases=%.2f mapControl=%.2f (strategy=%s frame=%d phase=%s)",
                weights.economy(), weights.army(), weights.tech(), weights.bases(), weights.mapControl(),
                strategy.id(), state.gameFrame(), ctx.currentPhase());
        }
        lastWeights = weights;

        double overall = clamp(economy * weights.economy() + army * weights.army()
            + tech * weights.tech() + bases * weights.bases()
            + mapControl * weights.mapControl());

        Map<String, Double> factors = new LinkedHashMap<>(5);
        factors.put("economy", economy);
        factors.put("army", army);
        factors.put("tech", tech);
        factors.put("bases", bases);
        factors.put("mapControl", mapControl);

        return new DominanceScore(overall, factors);
    }

    private double economyFactor(GameState state) {
        long ownWorkers = state.myUnits().stream()
            .filter(u -> SC2Data.isWorker(u.type())).count();
        long enemyWorkers = state.enemyUnits().stream()
            .filter(u -> SC2Data.isWorker(u.type())).count();
        if (enemyWorkers == 0) return 0.0;

        double rate = SC2Data.MINERAL_TIER_RATES_PER_TICK[0];
        double delta = (ownWorkers - enemyWorkers) * rate;
        return clamp(delta / maxExpectedEconomyDelta);
    }

    private double armyFactor(GameState state) {
        if (state.enemyUnits().isEmpty()) return 0.0;

        int ownValue = state.myUnits().stream()
            .filter(u -> !SC2Data.isWorker(u.type()))
            .mapToInt(u -> SC2Data.mineralCost(u.type()) + SC2Data.gasCost(u.type()))
            .sum();
        int enemyValue = state.enemyUnits().stream()
            .filter(u -> !SC2Data.isWorker(u.type()))
            .mapToInt(u -> SC2Data.mineralCost(u.type()) + SC2Data.gasCost(u.type()))
            .sum();
        return clamp((double) (ownValue - enemyValue) / maxExpectedArmyDelta);
    }

    private double techFactor(GameState state) {
        if (state.enemyBuildings().isEmpty()) return 0.0;

        double ownScore = techScore(state.myBuildings());
        double enemyScore = techScore(state.enemyBuildings());
        return clamp((ownScore - enemyScore) / maxExpectedTechDelta);
    }

    static double techScore(List<Building> buildings) {
        int maxTier = 0;
        int breadth = 0;
        for (Building b : buildings) {
            if (!b.isComplete()) continue;
            OptionalInt tier = SC2Data.techTier(b.type());
            if (tier.isPresent()) {
                maxTier = Math.max(maxTier, tier.getAsInt());
                breadth++;
            }
        }
        return maxTier + 0.1 * breadth;
    }

    private double basesFactor(GameState state) {
        if (state.enemyBuildings().isEmpty()) return 0.0;

        long ownBases = state.myBuildings().stream()
            .filter(b -> b.isComplete() && SC2Data.isBase(b.type())).count();
        long enemyBases = state.enemyBuildings().stream()
            .filter(b -> b.isComplete() && SC2Data.isBase(b.type())).count();
        return clamp((double) (ownBases - enemyBases) / maxExpectedBaseDelta);
    }

    private double mapControlFactor(GameState state) {
        if (state.enemyBuildings().isEmpty()) return 0.0;
        List<ExpansionLocation> expansions = state.mapInfo() != null
            ? state.mapInfo().expansions() : List.of();
        if (expansions.isEmpty()) return 0.0;

        long ownControlled = expansions.stream()
            .filter(exp -> controlledBy(state.myBuildings(), exp)).count();
        long enemyControlled = expansions.stream()
            .filter(exp -> controlledBy(state.enemyBuildings(), exp)).count();
        return clamp((double) (ownControlled - enemyControlled) / maxExpectedMapControlDelta);
    }

    private static boolean controlledBy(List<Building> buildings, ExpansionLocation exp) {
        return buildings.stream().anyMatch(b ->
            b.isComplete() && SC2Data.isBase(b.type())
            && b.position().distanceTo(exp.position()) <= EXPANSION_CONTROL_RADIUS);
    }

    private static double clamp(double value) {
        return Math.max(-1.0, Math.min(1.0, value));
    }
}

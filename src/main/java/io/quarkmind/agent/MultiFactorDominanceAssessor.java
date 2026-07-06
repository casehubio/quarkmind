package io.quarkmind.agent;

import io.quarkmind.domain.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

@ApplicationScoped
public class MultiFactorDominanceAssessor implements DominanceAssessor {

    private final double economyWeight;
    private final double armyWeight;
    private final double techWeight;
    private final double basesWeight;
    private final double maxExpectedEconomyDelta;
    private final int maxExpectedArmyDelta;
    private final double maxExpectedTechDelta;
    private final int maxExpectedBaseDelta;
    private final int minEnemyVisibility;

    @Inject
    MultiFactorDominanceAssessor(MilestoneConfig config) {
        this(config.dominance().economyWeight(),
             config.dominance().armyWeight(),
             config.dominance().techWeight(),
             config.dominance().basesWeight(),
             config.dominance().maxExpectedEconomyDelta(),
             config.dominance().maxExpectedArmyDelta(),
             config.dominance().maxExpectedTechDelta(),
             config.dominance().maxExpectedBaseDelta(),
             config.dominance().minEnemyVisibility());
    }

    MultiFactorDominanceAssessor(
            double economyWeight, double armyWeight, double techWeight, double basesWeight,
            double maxExpectedEconomyDelta, int maxExpectedArmyDelta,
            double maxExpectedTechDelta, int maxExpectedBaseDelta,
            int minEnemyVisibility) {
        this.economyWeight = economyWeight;
        this.armyWeight = armyWeight;
        this.techWeight = techWeight;
        this.basesWeight = basesWeight;
        this.maxExpectedEconomyDelta = maxExpectedEconomyDelta;
        this.maxExpectedArmyDelta = maxExpectedArmyDelta;
        this.maxExpectedTechDelta = maxExpectedTechDelta;
        this.maxExpectedBaseDelta = maxExpectedBaseDelta;
        this.minEnemyVisibility = minEnemyVisibility;
    }

    private static final DominanceScore NEUTRAL = new DominanceScore(0.0,
        Map.of("economy", 0.0, "army", 0.0, "tech", 0.0, "bases", 0.0));

    @Override
    public DominanceScore assess(GameState state) {
        int totalEnemyVisible = state.enemyUnits().size() + state.enemyBuildings().size();
        if (totalEnemyVisible < minEnemyVisibility) {
            return NEUTRAL;
        }

        double economy = economyFactor(state);
        double army = armyFactor(state);
        double tech = techFactor(state);
        double bases = basesFactor(state);

        double overall = clamp(economy * economyWeight + army * armyWeight
            + tech * techWeight + bases * basesWeight);

        Map<String, Double> factors = new LinkedHashMap<>(4);
        factors.put("economy", economy);
        factors.put("army", army);
        factors.put("tech", tech);
        factors.put("bases", bases);

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

    private static double clamp(double value) {
        return Math.max(-1.0, Math.min(1.0, value));
    }
}

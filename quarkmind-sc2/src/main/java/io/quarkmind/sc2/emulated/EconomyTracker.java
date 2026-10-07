package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.PlayerEconomyStats;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.UnitType;

class EconomyTracker {

    private int mineralsUsedArmy;
    private int vespeneUsedArmy;
    private int mineralsUsedEconomy;
    private int vespeneUsedEconomy;
    private int mineralsUsedTechnology;
    private int vespeneUsedTechnology;

    private double prevMinerals;
    private int prevVespene;
    private int mineralRate;
    private int vespeneRate;
    private int ticksSinceRateUpdate;

    private static final int RATE_INTERVAL_TICKS = 7;

    void recordTrainSpending(UnitType type, int mineralCost, int gasCost) {
        if (SC2Data.isWorker(type)) {
            mineralsUsedEconomy += mineralCost;
            vespeneUsedEconomy += gasCost;
        } else {
            mineralsUsedArmy += mineralCost;
            vespeneUsedArmy += gasCost;
        }
    }

    void recordBuildSpending(BuildingType type, int mineralCost) {
        if (isTechBuilding(type)) {
            mineralsUsedTechnology += mineralCost;
        } else {
            mineralsUsedEconomy += mineralCost;
        }
    }

    void tickUpdate(double minerals, int vespene,
                    int supply, int supplyUsed, int workerCount) {
        ticksSinceRateUpdate++;
        if (ticksSinceRateUpdate >= RATE_INTERVAL_TICKS) {
            double mineralDelta = minerals - prevMinerals;
            int vespeneDelta = vespene - prevVespene;
            mineralRate = (int) Math.max(0, mineralDelta);
            vespeneRate = Math.max(0, vespeneDelta);
            prevMinerals = minerals;
            prevVespene = vespene;
            ticksSinceRateUpdate = 0;
        }
    }

    PlayerEconomyStats currentStats(int minerals, int vespene,
                                     int supply, int supplyUsed,
                                     int workerCount) {
        return new PlayerEconomyStats(
            minerals, vespene,
            mineralRate, vespeneRate,
            supply, supplyUsed,
            workerCount,
            mineralsUsedArmy, mineralsUsedEconomy, mineralsUsedTechnology,
            vespeneUsedArmy, vespeneUsedEconomy, vespeneUsedTechnology);
    }

    void reset() {
        mineralsUsedArmy = 0;
        vespeneUsedArmy = 0;
        mineralsUsedEconomy = 0;
        vespeneUsedEconomy = 0;
        mineralsUsedTechnology = 0;
        vespeneUsedTechnology = 0;
        prevMinerals = 0;
        prevVespene = 0;
        mineralRate = 0;
        vespeneRate = 0;
        ticksSinceRateUpdate = 0;
    }

    private static boolean isTechBuilding(BuildingType type) {
        return switch (type) {
            case CYBERNETICS_CORE, TWILIGHT_COUNCIL, TEMPLAR_ARCHIVES,
                 DARK_SHRINE, FLEET_BEACON, FORGE,
                 ENGINEERING_BAY, ARMORY, GHOST_ACADEMY, FUSION_CORE,
                 EVOLUTION_CHAMBER, SPIRE, GREATER_SPIRE,
                 HYDRALISK_DEN, INFESTATION_PIT, ULTRALISK_CAVERN,
                 BANELING_NEST, LURKER_DEN -> true;
            default -> false;
        };
    }
}

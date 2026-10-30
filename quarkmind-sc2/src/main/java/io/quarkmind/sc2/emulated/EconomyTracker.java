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

    private int mineralRate;
    private int vespeneRate;
    private int ticksSinceRateUpdate;
    private double grossMinerals;
    private double grossVespene;
    private double prevGrossMinerals;
    private double prevGrossVespene;


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

    void recordResearchSpending(int mineralCost, int gasCost) {
        mineralsUsedTechnology += mineralCost;
        vespeneUsedTechnology += gasCost;
    }

    void recordMorphSpending(BuildingType type, int mineralCost, int gasCost) {
        if (isTechBuilding(type)) {
            mineralsUsedTechnology += mineralCost;
            vespeneUsedTechnology += gasCost;
        } else {
            mineralsUsedEconomy += mineralCost;
            vespeneUsedEconomy += gasCost;
        }
    }

    void recordMorphSpending(UnitType type, int mineralCost, int gasCost) {
        mineralsUsedArmy += mineralCost;
        vespeneUsedArmy += gasCost;
    }


    void recordIncome(double mineralIncome, double vespeneIncome) {
        grossMinerals += mineralIncome;
        grossVespene += vespeneIncome;
    }

    void tickUpdate(double minerals, int vespene,
                    int supply, int supplyUsed, int workerCount) {
        ticksSinceRateUpdate++;
        if (ticksSinceRateUpdate >= RATE_INTERVAL_TICKS) {
            mineralRate          = (int) Math.max(0, grossMinerals - prevGrossMinerals);
            vespeneRate          = (int) Math.max(0, grossVespene - prevGrossVespene);
            prevGrossMinerals    = grossMinerals;
            prevGrossVespene     = grossVespene;
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
        mineralsUsedArmy       = 0;
        vespeneUsedArmy        = 0;
        mineralsUsedEconomy    = 0;
        vespeneUsedEconomy     = 0;
        mineralsUsedTechnology = 0;
        vespeneUsedTechnology  = 0;
        grossMinerals          = 0;
        grossVespene           = 0;
        prevGrossMinerals      = 0;
        prevGrossVespene       = 0;
        mineralRate            = 0;
        vespeneRate            = 0;
        ticksSinceRateUpdate   = 0;
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

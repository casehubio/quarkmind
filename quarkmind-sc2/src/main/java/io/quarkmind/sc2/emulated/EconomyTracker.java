package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.PlayerEconomyStats;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.UnitType;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

class EconomyTracker {

    private int mineralsUsedArmy;
    private int vespeneUsedArmy;
    private int mineralsUsedEconomy;
    private int vespeneUsedEconomy;
    private int mineralsUsedTechnology;
    private int vespeneUsedTechnology;

    private int inProgressMineralArmy;
    private int inProgressVespeneArmy;
    private int inProgressMineralEconomy;
    private int inProgressVespeneEconomy;
    private int inProgressMineralTechnology;
    private int inProgressVespeneTechnology;

    private int reportedMineralsUsedArmy;
    private int reportedVespeneUsedArmy;
    private int reportedMineralsUsedEconomy;
    private int reportedVespeneUsedEconomy;
    private int reportedMineralsUsedTechnology;
    private int reportedVespeneUsedTechnology;
    private final Map<BuildingType, int[]> techBuildSpending       = new EnumMap<>(BuildingType.class);
    private final Map<BuildingType, int[]> techMorphSpending       = new EnumMap<>(BuildingType.class);
    private final Map<BuildingType, int[]> techDestroySubtractions = new EnumMap<>(BuildingType.class);
    private int researchMineralsTotal;
    private int researchVespeneTotal;


    private int mineralRate;
    private int vespeneRate;
    private int loopsSinceRateUpdate;
    private double grossMinerals;
    private double grossVespene;
    private double prevGrossMinerals;
    private double prevGrossVespene;
    private double emaMineralRate;
    private double emaVespeneRate;
    private boolean emaSeeded;

    static final double MINERAL_EMA_ALPHA = 0.25;
    static final double VESPENE_EMA_ALPHA = 0.35;

    private final int rateIntervalLoops;
    private static final double LOOPS_PER_MINUTE = 60.0 * SC2Data.GAME_LOOPS_PER_SECOND;

    EconomyTracker() {
        this(160);
    }

    EconomyTracker(int rateIntervalLoops) {
        this.rateIntervalLoops = rateIntervalLoops;
    }


    void recordTrainSpending(UnitType type, int mineralCost, int gasCost) {
        if (SC2Data.isWorker(type) || SC2Data.unitSupplyProvided(type) > 0) {
            mineralsUsedEconomy += mineralCost;
            vespeneUsedEconomy += gasCost;
        } else {
            mineralsUsedArmy += mineralCost;
            vespeneUsedArmy += gasCost;
        }
    }

    void recordBuildSpending(BuildingType type, int mineralCost) {
        recordBuildSpending(type, mineralCost, 0);
    }

    void recordBuildSpending(BuildingType type, int mineralCost, int gasCost) {
        if (isEconomyBuilding(type)) {
            mineralsUsedEconomy += mineralCost;
            vespeneUsedEconomy += gasCost;
        } else {
            mineralsUsedTechnology += mineralCost;
            vespeneUsedTechnology += gasCost;
            techBuildSpending.merge(type, new int[]{mineralCost, gasCost},
                                    (a, b) -> new int[]{a[0] + b[0], a[1] + b[1]});
        }
    }

    void recordResearchSpending(int mineralCost, int gasCost) {
        mineralsUsedTechnology += mineralCost;
        vespeneUsedTechnology += gasCost;
        researchMineralsTotal += mineralCost;
        researchVespeneTotal += gasCost;
    }

    void recordMorphSpending(BuildingType type, int mineralCost, int gasCost) {
        if (isEconomyBuilding(type)) {
            mineralsUsedEconomy += mineralCost;
            vespeneUsedEconomy += gasCost;
        } else {
            mineralsUsedTechnology += mineralCost;
            vespeneUsedTechnology += gasCost;
            techMorphSpending.merge(type, new int[]{mineralCost, gasCost},
                                    (a, b) -> new int[]{a[0] + b[0], a[1] + b[1]});
        }
    }

    void recordMorphSpending(UnitType type, int mineralCost, int gasCost) {
        mineralsUsedArmy += mineralCost;
        vespeneUsedArmy += gasCost;
    }

    void recordUnitDeath(UnitType type) {
        int mCost = SC2Data.mineralCost(type);
        int gCost = SC2Data.gasCost(type);
        UnitType source = SC2Data.morphSourceType(type);
        if (source != null) {
            mCost += SC2Data.mineralCost(source);
            gCost += SC2Data.gasCost(source);
        }
        if (SC2Data.isWorker(type) || SC2Data.unitSupplyProvided(type) > 0) {
            mineralsUsedEconomy = Math.max(0, mineralsUsedEconomy - mCost);
            vespeneUsedEconomy = Math.max(0, vespeneUsedEconomy - gCost);
        } else {
            mineralsUsedArmy = Math.max(0, mineralsUsedArmy - mCost);
            vespeneUsedArmy = Math.max(0, vespeneUsedArmy - gCost);
        }
    }

    void recordBuildingDestroyed(BuildingType type) {
        int mCost = SC2Data.mineralCost(type);
        int gCost = SC2Data.gasCost(type);
        if (isEconomyBuilding(type)) {
            mineralsUsedEconomy = Math.max(0, mineralsUsedEconomy - mCost);
            vespeneUsedEconomy  = Math.max(0, vespeneUsedEconomy - gCost);
        } else {
            mineralsUsedTechnology = Math.max(0, mineralsUsedTechnology - mCost);
            vespeneUsedTechnology  = Math.max(0, vespeneUsedTechnology - gCost);
            techDestroySubtractions.merge(type, new int[]{mCost, gCost},
                                          (a, b) -> new int[]{a[0] + b[0], a[1] + b[1]});
        }
    }

    void addInProgressTrainSpending(UnitType type, int mineralCost, int gasCost) {
        if (SC2Data.isWorker(type) || SC2Data.unitSupplyProvided(type) > 0) {
            addInProgressEconomy(mineralCost, gasCost);
        } else {
            addInProgressArmy(mineralCost, gasCost);
        }
    }

    void completeInProgressTrainSpending(UnitType type, int mineralCost, int gasCost) {
        if (SC2Data.isWorker(type) || SC2Data.unitSupplyProvided(type) > 0) {
            completeInProgressEconomy(mineralCost, gasCost);
        } else {
            completeInProgressArmy(mineralCost, gasCost);
        }
    }

    void addInProgressBuildSpending(BuildingType type, int mineralCost, int gasCost) {
        if (isEconomyBuilding(type)) {
            addInProgressEconomy(mineralCost, gasCost);
        } else {
            addInProgressTechnology(mineralCost, gasCost);
        }
    }

    void completeInProgressBuildSpending(BuildingType type, int mineralCost, int gasCost) {
        if (isEconomyBuilding(type)) {
            completeInProgressEconomy(mineralCost, gasCost);
        } else {
            completeInProgressTechnology(mineralCost, gasCost);
        }
    }

    void addInProgressArmy(int mineralCost, int gasCost) {
        inProgressMineralArmy += mineralCost;
        inProgressVespeneArmy += gasCost;
    }

    void completeInProgressArmy(int mineralCost, int gasCost) {
        inProgressMineralArmy = Math.max(0, inProgressMineralArmy - mineralCost);
        inProgressVespeneArmy = Math.max(0, inProgressVespeneArmy - gasCost);
    }

    void cancelInProgressArmy(int mineralCost, int gasCost) {
        inProgressMineralArmy = Math.max(0, inProgressMineralArmy - mineralCost);
        inProgressVespeneArmy = Math.max(0, inProgressVespeneArmy - gasCost);
    }

    void addInProgressEconomy(int mineralCost, int gasCost) {
        inProgressMineralEconomy += mineralCost;
        inProgressVespeneEconomy += gasCost;
    }

    void completeInProgressEconomy(int mineralCost, int gasCost) {
        inProgressMineralEconomy = Math.max(0, inProgressMineralEconomy - mineralCost);
        inProgressVespeneEconomy = Math.max(0, inProgressVespeneEconomy - gasCost);
    }

    void cancelInProgressEconomy(int mineralCost, int gasCost) {
        inProgressMineralEconomy = Math.max(0, inProgressMineralEconomy - mineralCost);
        inProgressVespeneEconomy = Math.max(0, inProgressVespeneEconomy - gasCost);
    }

    void addInProgressTechnology(int mineralCost, int gasCost) {
        inProgressMineralTechnology += mineralCost;
        inProgressVespeneTechnology += gasCost;
    }

    void completeInProgressTechnology(int mineralCost, int gasCost) {
        inProgressMineralTechnology = Math.max(0, inProgressMineralTechnology - mineralCost);
        inProgressVespeneTechnology = Math.max(0, inProgressVespeneTechnology - gasCost);
    }

    void cancelInProgressTechnology(int mineralCost, int gasCost) {
        inProgressMineralTechnology = Math.max(0, inProgressMineralTechnology - mineralCost);
        inProgressVespeneTechnology = Math.max(0, inProgressVespeneTechnology - gasCost);
    }

    void recordIncome(double mineralIncome, double vespeneIncome) {
        grossMinerals += mineralIncome;
        grossVespene += vespeneIncome;
    }

    private int mineralRateOverride = -1;
    private int vespeneRateOverride = -1;

    void overrideMineralRate(int ratePerMinute) {
        this.mineralRateOverride = ratePerMinute;
        if (ratePerMinute >= 0) {
            this.mineralRate = ratePerMinute;
        }
    }

    void overrideVespeneRate(int ratePerMinute) {
        this.vespeneRateOverride = ratePerMinute;
        if (ratePerMinute >= 0) {
            this.vespeneRate = ratePerMinute;
        }
    }

    private int[] spendingOverride;

    void overrideSpending(int mArmy, int mEcon, int mTech, int vArmy, int vEcon, int vTech) {
        this.spendingOverride = new int[]{mArmy, mEcon, mTech, vArmy, vEcon, vTech};
    }

    void clearSpendingOverride() {
        this.spendingOverride = null;
    }


    void tickUpdate(double minerals, int vespene,
                    int supply, double supplyUsed, int workerCount) {
        loopsSinceRateUpdate += SC2Data.LOOPS_PER_TICK;
        if (loopsSinceRateUpdate >= rateIntervalLoops) {
            double perLoopMineral = (grossMinerals - prevGrossMinerals) / loopsSinceRateUpdate;
            double perLoopVespene = (grossVespene - prevGrossVespene) / loopsSinceRateUpdate;
            double instantMinRate = perLoopMineral * LOOPS_PER_MINUTE;
            double instantGasRate = perLoopVespene * LOOPS_PER_MINUTE;
            if (!emaSeeded) {
                emaMineralRate = instantMinRate;
                emaVespeneRate = instantGasRate;
                emaSeeded = true;
            } else {
                emaMineralRate = MINERAL_EMA_ALPHA * instantMinRate + (1 - MINERAL_EMA_ALPHA) * emaMineralRate;
                emaVespeneRate = VESPENE_EMA_ALPHA * instantGasRate + (1 - VESPENE_EMA_ALPHA) * emaVespeneRate;
            }
            mineralRate    = mineralRateOverride >= 0
                             ? mineralRateOverride
                             : (int) Math.max(0, emaMineralRate);
            vespeneRate    = vespeneRateOverride >= 0
                             ? vespeneRateOverride
                             : (int) Math.max(0, emaVespeneRate);
            prevGrossMinerals    = grossMinerals;
            prevGrossVespene     = grossVespene;
            loopsSinceRateUpdate = 0;

            reportedMineralsUsedArmy       = mineralsUsedArmy;
            reportedVespeneUsedArmy        = vespeneUsedArmy;
            reportedMineralsUsedEconomy    = mineralsUsedEconomy;
            reportedVespeneUsedEconomy     = vespeneUsedEconomy;
            reportedMineralsUsedTechnology = mineralsUsedTechnology;
            reportedVespeneUsedTechnology  = vespeneUsedTechnology;
        }
    }

    PlayerEconomyStats currentStats(int minerals, int vespene,
                                     int supply, double supplyUsed,
                                     int workerCount) {
        int[] s = spendingOverride;
        return new PlayerEconomyStats(
            minerals, vespene,
            mineralRate, vespeneRate,
            supply, supplyUsed,
            workerCount,
            s != null ? s[0] : reportedMineralsUsedArmy,
            s != null ? s[1] : reportedMineralsUsedEconomy,
            s != null ? s[2] : reportedMineralsUsedTechnology,
            s != null ? s[3] : reportedVespeneUsedArmy,
            s != null ? s[4] : reportedVespeneUsedEconomy,
            s != null ? s[5] : reportedVespeneUsedTechnology);
    }

    void reset() {
        mineralsUsedArmy       = 0;
        vespeneUsedArmy        = 0;
        mineralsUsedEconomy    = 0;
        vespeneUsedEconomy     = 0;
        mineralsUsedTechnology = 0;
        vespeneUsedTechnology  = 0;
        inProgressMineralArmy       = 0;
        inProgressVespeneArmy       = 0;
        inProgressMineralEconomy    = 0;
        inProgressVespeneEconomy    = 0;
        inProgressMineralTechnology = 0;
        inProgressVespeneTechnology = 0;
        grossMinerals          = 0;
        grossVespene           = 0;
        prevGrossMinerals      = 0;
        prevGrossVespene       = 0;
        mineralRate            = 0;
        vespeneRate            = 0;
        emaMineralRate         = 0;
        emaVespeneRate         = 0;
        emaSeeded              = false;
        loopsSinceRateUpdate   = 0;
        reportedMineralsUsedArmy       = 0;
        reportedVespeneUsedArmy        = 0;
        reportedMineralsUsedEconomy    = 0;
        reportedVespeneUsedEconomy     = 0;
        reportedMineralsUsedTechnology = 0;
        reportedVespeneUsedTechnology  = 0;
        techBuildSpending.clear();
        techMorphSpending.clear();
        techDestroySubtractions.clear();
        researchMineralsTotal = 0;
        researchVespeneTotal  = 0;
    }


    record TechSpendingBreakdown(
            Map<BuildingType, int[]> buildSpending,
            Map<BuildingType, int[]> morphSpending,
            Map<BuildingType, int[]> destroySubtractions,
            int researchMinerals, int researchVespene) {
    }


    TechSpendingBreakdown techSpendingBreakdown() {
        return new TechSpendingBreakdown(
                Collections.unmodifiableMap(new EnumMap<>(techBuildSpending)),
                Collections.unmodifiableMap(new EnumMap<>(techMorphSpending)),
                Collections.unmodifiableMap(new EnumMap<>(techDestroySubtractions)),
                researchMineralsTotal, researchVespeneTotal);
    }

    static boolean isEconomyBuilding(BuildingType type) {
        return switch (type) {
            case NEXUS, COMMAND_CENTER, ORBITAL_COMMAND, PLANETARY_FORTRESS,
                 HATCHERY, LAIR, HIVE,
                 PYLON, SUPPLY_DEPOT,
                 ASSIMILATOR, ASSIMILATOR_RICH, REFINERY, EXTRACTOR -> true;
            default -> false;
        };
    }
}

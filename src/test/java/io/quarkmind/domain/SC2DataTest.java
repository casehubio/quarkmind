package io.quarkmind.domain;

import org.junit.jupiter.api.Test;

import static io.quarkmind.domain.UnitAttribute.ARMORED;
import static io.quarkmind.domain.UnitAttribute.BIOLOGICAL;
import static io.quarkmind.domain.UnitAttribute.LIGHT;
import static io.quarkmind.domain.UnitAttribute.MASSIVE;
import static io.quarkmind.domain.UnitAttribute.MECHANICAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SC2DataTest {

    @Test void zealotAttributes() {
        assertThat(SC2Data.unitAttributes(UnitType.ZEALOT)).containsExactlyInAnyOrder(LIGHT, BIOLOGICAL);
    }
    @Test void stalkerAttributes() {
        assertThat(SC2Data.unitAttributes(UnitType.STALKER)).containsExactlyInAnyOrder(ARMORED, MECHANICAL);
    }
    @Test void immortalAttributes() {
        assertThat(SC2Data.unitAttributes(UnitType.IMMORTAL)).containsExactlyInAnyOrder(ARMORED, MECHANICAL, MASSIVE);
    }
    @Test void marineAttributes() {
        assertThat(SC2Data.unitAttributes(UnitType.MARINE)).containsExactlyInAnyOrder(LIGHT, BIOLOGICAL);
    }
    @Test void roachAttributes() {
        assertThat(SC2Data.unitAttributes(UnitType.ROACH)).containsExactlyInAnyOrder(ARMORED, BIOLOGICAL);
    }
    @Test void hydraliskAttributes() {
        assertThat(SC2Data.unitAttributes(UnitType.HYDRALISK)).containsExactlyInAnyOrder(LIGHT, BIOLOGICAL);
    }
    @Test void probeAttributes() {
        assertThat(SC2Data.unitAttributes(UnitType.PROBE)).containsExactlyInAnyOrder(LIGHT, MECHANICAL);
    }

    @Test void immortalHasHardenedShield() {
        assertThat(SC2Data.hasHardenedShield(UnitType.IMMORTAL)).isTrue();
    }
    @Test void stalkerHasNoHardenedShield() {
        assertThat(SC2Data.hasHardenedShield(UnitType.STALKER)).isFalse();
    }
    @Test void zealotHasNoHardenedShield() {
        assertThat(SC2Data.hasHardenedShield(UnitType.ZEALOT)).isFalse();
    }

    @Test void stalkerArmour() { assertThat(SC2Data.armour(UnitType.STALKER)).isEqualTo(1); }
    @Test void zealotArmour()  { assertThat(SC2Data.armour(UnitType.ZEALOT)).isEqualTo(1); }
    @Test void immortalArmour(){ assertThat(SC2Data.armour(UnitType.IMMORTAL)).isEqualTo(1); }
    @Test void marauderArmour(){ assertThat(SC2Data.armour(UnitType.MARAUDER)).isEqualTo(1); }
    @Test void roachArmour()   { assertThat(SC2Data.armour(UnitType.ROACH)).isEqualTo(1); }
    @Test void marineArmour()  { assertThat(SC2Data.armour(UnitType.MARINE)).isEqualTo(0); }
    @Test void probeArmour()   { assertThat(SC2Data.armour(UnitType.PROBE)).isEqualTo(0); }
    @Test void hydraliskArmour() { assertThat(SC2Data.armour(UnitType.HYDRALISK)).isEqualTo(0); }

    @Test
    void scvArmour()             {assertThat(SC2Data.armour(UnitType.SCV)).isEqualTo(1);}

    @Test
    void queenArmour()           {assertThat(SC2Data.armour(UnitType.QUEEN)).isEqualTo(1);}


    @Test void stalkerBonusVsArmored()  { assertThat(SC2Data.bonusDamageVs(UnitType.STALKER,  ARMORED)).isEqualTo(4); }
    @Test void stalkerBonusVsLight()    { assertThat(SC2Data.bonusDamageVs(UnitType.STALKER,  LIGHT)).isEqualTo(0); }
    @Test void marauderBonusVsLight()   { assertThat(SC2Data.bonusDamageVs(UnitType.MARAUDER, LIGHT)).isEqualTo(0); }
    @Test void marauderBonusVsArmored() { assertThat(SC2Data.bonusDamageVs(UnitType.MARAUDER, ARMORED)).isEqualTo(10); }
    @Test void immortalBonusVsArmored() { assertThat(SC2Data.bonusDamageVs(UnitType.IMMORTAL, ARMORED)).isEqualTo(3); }
    @Test void probeBonusVsArmored()    { assertThat(SC2Data.bonusDamageVs(UnitType.PROBE,    ARMORED)).isEqualTo(0); }
    @Test void zealotBonusVsArmored()   { assertThat(SC2Data.bonusDamageVs(UnitType.ZEALOT,   ARMORED)).isEqualTo(0); }

    @Test void correctedHp_immortal()  { assertThat(SC2Data.maxHealth(UnitType.IMMORTAL)).isEqualTo(200); }
    @Test void correctedHp_marine()    { assertThat(SC2Data.maxHealth(UnitType.MARINE)).isEqualTo(45); }
    @Test void correctedHp_marauder()  { assertThat(SC2Data.maxHealth(UnitType.MARAUDER)).isEqualTo(125); }
    @Test void correctedHp_roach()     { assertThat(SC2Data.maxHealth(UnitType.ROACH)).isEqualTo(145); }
    @Test void correctedHp_hydralisk() { assertThat(SC2Data.maxHealth(UnitType.HYDRALISK)).isEqualTo(90); }

    @Test
    void damagePerAttackDefinedForProtossUnits() {
        assertThat(SC2Data.damagePerAttack(UnitType.PROBE)).isEqualTo(5);
        assertThat(SC2Data.damagePerAttack(UnitType.ZEALOT)).isEqualTo(8);
        assertThat(SC2Data.damagePerAttack(UnitType.STALKER)).isEqualTo(13);
        assertThat(SC2Data.damagePerAttack(UnitType.IMMORTAL)).isEqualTo(20);
    }

    @Test
    void damagePerAttackDefinedForTerranAndZergUnits() {
        assertThat(SC2Data.damagePerAttack(UnitType.MARINE)).isEqualTo(6);
        assertThat(SC2Data.damagePerAttack(UnitType.MARAUDER)).isEqualTo(10);
        assertThat(SC2Data.damagePerAttack(UnitType.ROACH)).isEqualTo(9);
        assertThat(SC2Data.damagePerAttack(UnitType.HYDRALISK)).isEqualTo(12);
    }

    @Test
    void attackCooldownInTicksDefinedForAllCombatUnits() {
        assertThat(SC2Data.attackCooldownInTicks(UnitType.MARINE)).isEqualTo(1);
        assertThat(SC2Data.attackCooldownInTicks(UnitType.HYDRALISK)).isEqualTo(1);
        assertThat(SC2Data.attackCooldownInTicks(UnitType.PROBE)).isEqualTo(2);
        assertThat(SC2Data.attackCooldownInTicks(UnitType.ZEALOT)).isEqualTo(2);
        assertThat(SC2Data.attackCooldownInTicks(UnitType.STALKER)).isEqualTo(1);
    }

    @Test
    void defaultCooldownAppliesForUnknownType() {
        assertThat(SC2Data.attackCooldownInTicks(UnitType.UNKNOWN)).isEqualTo(2);
    }

    @Test
    void defaultDamageAppliesForUnknownType() {
        assertThat(SC2Data.damagePerAttack(UnitType.UNKNOWN)).isEqualTo(5);
    }

    @Test
    void observerSupplyCostIsOne() {
        // Real SC2 value: Observer costs 1 supply. The default branch was returning 2.
        assertThat(SC2Data.supplyCost(UnitType.OBSERVER)).isEqualTo(1);
    }

    // --- mineralIncomePerTick ---

    private static final double TIER1 = SC2Data.MINERAL_TIER_RATES_PER_TICK[0];
    private static final double TIER2 = SC2Data.MINERAL_TIER_RATES_PER_TICK[1];
    private static final double TIER3 = SC2Data.MINERAL_TIER_RATES_PER_TICK[2];

    @Test
    void negativeProbeCountThrows() {
        assertThatThrownBy(() -> SC2Data.mineralIncomePerTick(-1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("-1");
    }

    @Test
    void zeroProbesYieldZeroIncome() {
        assertThat(SC2Data.mineralIncomePerTick(0)).isEqualTo(0.0);
    }

    @Test
    void singleProbeYieldsTier1Rate() {
        assertThat(SC2Data.mineralIncomePerTick(1)).isEqualTo(TIER1);
    }

    @Test
    void fullTier1At8Probes() {
        assertThat(SC2Data.mineralIncomePerTick(8)).isEqualTo(8 * TIER1);
    }

    @Test
    void tier2StartsAt9Probes() {
        assertThat(SC2Data.mineralIncomePerTick(9)).isEqualTo(8 * TIER1 + TIER2);
    }

    @Test
    void fullTier2At16Probes() {
        assertThat(SC2Data.mineralIncomePerTick(16)).isEqualTo(8 * TIER1 + 8 * TIER2);
    }

    @Test
    void tier3StartsAt17Probes() {
        assertThat(SC2Data.mineralIncomePerTick(17)).isEqualTo(8 * TIER1 + 8 * TIER2 + TIER3);
    }

    @Test
    void fullCapacityAt24Probes() {
        assertThat(SC2Data.mineralIncomePerTick(24)).isEqualTo(8 * TIER1 + 8 * TIER2 + 8 * TIER3);
    }

    @Test
    void capEnforcedAt25Probes() {
        assertThat(SC2Data.mineralIncomePerTick(25)).isEqualTo(SC2Data.mineralIncomePerTick(24));
    }

    @Test
    void capEnforcedAt100Probes() {
        assertThat(SC2Data.mineralIncomePerTick(100)).isEqualTo(SC2Data.mineralIncomePerTick(24));
    }

    @Test
    void trainedByMapsKnownUnits() {
        assertThat(SC2Data.trainedBy(UnitType.PROBE))    .isEqualTo(BuildingType.NEXUS);
        assertThat(SC2Data.trainedBy(UnitType.ZEALOT))   .isEqualTo(BuildingType.GATEWAY);
        assertThat(SC2Data.trainedBy(UnitType.STALKER))  .isEqualTo(BuildingType.GATEWAY);
        assertThat(SC2Data.trainedBy(UnitType.IMMORTAL)) .isEqualTo(BuildingType.ROBOTICS_FACILITY);
        assertThat(SC2Data.trainedBy(UnitType.OBSERVER)) .isEqualTo(BuildingType.ROBOTICS_FACILITY);
        assertThat(SC2Data.trainedBy(UnitType.MARINE))   .isEqualTo(BuildingType.BARRACKS);
        assertThat(SC2Data.trainedBy(UnitType.MARAUDER)) .isEqualTo(BuildingType.BARRACKS);
        assertThat(SC2Data.trainedBy(UnitType.ZERGLING)) .isEqualTo(BuildingType.HATCHERY);
        assertThat(SC2Data.trainedBy(UnitType.ROACH))    .isEqualTo(BuildingType.HATCHERY);
        assertThat(SC2Data.trainedBy(UnitType.HYDRALISK)).isEqualTo(BuildingType.HATCHERY);
        // Unmapped types return UNKNOWN, not a plausible sentinel
        assertThat(SC2Data.trainedBy(UnitType.UNKNOWN)).isEqualTo(BuildingType.UNKNOWN);
    }

    @Test
    void trainTimeInLoopsDefinedForProtossUnits() {
        // Empirically calibrated from 29 AI Arena replays (SC2TrainTimeCalibrationTest).
        // PROBE/ZEALOT/STALKER are replay-verified; IMMORTAL/OBSERVER are integer estimates.
        assertThat(SC2Data.trainTimeInLoops(UnitType.PROBE))    .isEqualTo(272);  // 499 obs
        assertThat(SC2Data.trainTimeInLoops(UnitType.ZEALOT))   .isEqualTo(618);  // 7 obs
        assertThat(SC2Data.trainTimeInLoops(UnitType.STALKER))  .isEqualTo(698);  // 2 obs
        assertThat(SC2Data.trainTimeInLoops(UnitType.IMMORTAL)) .isEqualTo(896);  // uncalibrated
        assertThat(SC2Data.trainTimeInLoops(UnitType.OBSERVER)) .isEqualTo(493);  // uncalibrated
        assertThat(SC2Data.trainTimeInLoops(UnitType.UNKNOWN)).isEqualTo(672);  // default: 30s × 22.4 = 672.0 (exact)
    }

    @Test
    void trainTimeInTicksDefinedForProtossUnits() {
        assertThat(SC2Data.trainTimeInTicks(UnitType.PROBE))    .isEqualTo(12);
        assertThat(SC2Data.trainTimeInTicks(UnitType.ZEALOT))   .isEqualTo(28);
        assertThat(SC2Data.trainTimeInTicks(UnitType.STALKER))  .isEqualTo(31);
        assertThat(SC2Data.trainTimeInTicks(UnitType.IMMORTAL)) .isEqualTo(40);
        assertThat(SC2Data.trainTimeInTicks(UnitType.OBSERVER)) .isEqualTo(22);
        assertThat(SC2Data.trainTimeInTicks(UnitType.UNKNOWN)).isEqualTo(30);   // 672 / 22 = 30 (integer division)
    }

    // --- trainCount ---

    @Test
    void trainCount_zergling_returnsTwo() {
        assertThat(SC2Data.trainCount(UnitType.ZERGLING)).isEqualTo(2);
    }

    @Test
    void trainCount_nonZergling_returnsOne() {
        assertThat(SC2Data.trainCount(UnitType.MARINE)).isEqualTo(1);
        assertThat(SC2Data.trainCount(UnitType.DRONE)).isEqualTo(1);
        assertThat(SC2Data.trainCount(UnitType.SCV)).isEqualTo(1);
        assertThat(SC2Data.trainCount(UnitType.PROBE)).isEqualTo(1);
    }

    // --- trainedBy additions ---

    @Test
    void trainedBy_queen_isHatchery() {
        assertThat(SC2Data.trainedBy(UnitType.QUEEN)).isEqualTo(BuildingType.HATCHERY);
    }

    @Test
    void trainedBy_mule_isOrbitalCommand() {
        assertThat(SC2Data.trainedBy(UnitType.MULE)).isEqualTo(BuildingType.ORBITAL_COMMAND);
    }

    @Test
    void trainedBy_scv_isCommandCenter() {
        assertThat(SC2Data.trainedBy(UnitType.SCV)).isEqualTo(BuildingType.COMMAND_CENTER);
    }

    // --- Terran unit stats ---

    @Test
    void scvMaxHealth() { assertThat(SC2Data.maxHealth(UnitType.SCV)).isEqualTo(45); }

    @Test
    void scvSupplyCost() { assertThat(SC2Data.supplyCost(UnitType.SCV)).isEqualTo(1); }

    @Test
    void scvMineralCost() { assertThat(SC2Data.mineralCost(UnitType.SCV)).isEqualTo(50); }

    @Test
    void marauderGasCost() { assertThat(SC2Data.gasCost(UnitType.MARAUDER)).isEqualTo(25); }

    @Test
    void marineMineralCost() { assertThat(SC2Data.mineralCost(UnitType.MARINE)).isEqualTo(50); }

    @Test
    void marauderMineralCost() { assertThat(SC2Data.mineralCost(UnitType.MARAUDER)).isEqualTo(100); }

    // --- Zerg unit stats ---

    @Test
    void droneMaxHealth() { assertThat(SC2Data.maxHealth(UnitType.DRONE)).isEqualTo(40); }

    @Test
    void zerglingMaxHealth() { assertThat(SC2Data.maxHealth(UnitType.ZERGLING)).isEqualTo(35); }

    @Test
    void overlordMaxHealth() { assertThat(SC2Data.maxHealth(UnitType.OVERLORD)).isEqualTo(200); }

    @Test
    void queenMaxHealth() { assertThat(SC2Data.maxHealth(UnitType.QUEEN)).isEqualTo(175); }

    @Test
    void droneSupplyCost() { assertThat(SC2Data.supplyCost(UnitType.DRONE)).isEqualTo(1); }

    @Test
    void overlordSupplyCost() { assertThat(SC2Data.supplyCost(UnitType.OVERLORD)).isEqualTo(0); }

    @Test
    void zerglingSupplyCost() { assertThat(SC2Data.supplyCost(UnitType.ZERGLING)).isEqualTo(1); }

    @Test
    void roachGasCost() { assertThat(SC2Data.gasCost(UnitType.ROACH)).isEqualTo(25); }

    @Test
    void hydraliskGasCost() { assertThat(SC2Data.gasCost(UnitType.HYDRALISK)).isEqualTo(50); }

    @Test
    void droneMineralCost() { assertThat(SC2Data.mineralCost(UnitType.DRONE)).isEqualTo(50); }

    @Test
    void zerglingMineralCost() { assertThat(SC2Data.mineralCost(UnitType.ZERGLING)).isEqualTo(25); }

    @Test
    void roachMineralCost() { assertThat(SC2Data.mineralCost(UnitType.ROACH)).isEqualTo(75); }

    @Test
    void hydraliskMineralCost() { assertThat(SC2Data.mineralCost(UnitType.HYDRALISK)).isEqualTo(100); }

    @Test
    void overlordMineralCost() { assertThat(SC2Data.mineralCost(UnitType.OVERLORD)).isEqualTo(100); }

    @Test
    void queenMineralCost() { assertThat(SC2Data.mineralCost(UnitType.QUEEN)).isEqualTo(150); }

    @Test
    void queenSupplyCost() { assertThat(SC2Data.supplyCost(UnitType.QUEEN)).isEqualTo(2); }

    // --- New SC2Data constants ---

    @Test
    void muleLifetimeLoopsIsPositive() {
        assertThat(SC2Data.MULE_LIFETIME_LOOPS).isEqualTo(1434);
    }

    @Test
    void muleIncomePerTickIsPositive() {
        assertThat(SC2Data.muleIncomePerTick()).isGreaterThan(0);
    }

    @Test
    void queenEnergyRegenPerLoopIsPositive() {
        assertThat(SC2Data.QUEEN_ENERGY_REGEN_PER_LOOP).isGreaterThan(0);
    }

    // --- techTier ---

    @Test
    void techTier_protossT1() {
        assertThat(SC2Data.techTier(BuildingType.GATEWAY)).hasValue(1);
    }

    @Test
    void techTier_protossT2() {
        assertThat(SC2Data.techTier(BuildingType.ROBOTICS_FACILITY)).hasValue(2);
        assertThat(SC2Data.techTier(BuildingType.STARGATE)).hasValue(2);
    }

    @Test
    void techTier_protossT3() {
        assertThat(SC2Data.techTier(BuildingType.TWILIGHT_COUNCIL)).hasValue(3);
        assertThat(SC2Data.techTier(BuildingType.TEMPLAR_ARCHIVES)).hasValue(3);
        assertThat(SC2Data.techTier(BuildingType.DARK_SHRINE)).hasValue(3);
    }

    @Test
    void techTier_protossT4() {
        assertThat(SC2Data.techTier(BuildingType.FLEET_BEACON)).hasValue(4);
        assertThat(SC2Data.techTier(BuildingType.ROBOTICS_BAY)).hasValue(4);
    }

    @Test
    void techTier_terranT1() {
        assertThat(SC2Data.techTier(BuildingType.BARRACKS)).hasValue(1);
    }

    @Test
    void techTier_terranT2() {
        assertThat(SC2Data.techTier(BuildingType.FACTORY)).hasValue(2);
        assertThat(SC2Data.techTier(BuildingType.STARPORT)).hasValue(2);
    }

    @Test
    void techTier_terranT3() {
        assertThat(SC2Data.techTier(BuildingType.GHOST_ACADEMY)).hasValue(3);
        assertThat(SC2Data.techTier(BuildingType.ARMORY)).hasValue(3);
    }

    @Test
    void techTier_terranT4() {
        assertThat(SC2Data.techTier(BuildingType.FUSION_CORE)).hasValue(4);
    }

    @Test
    void techTier_zergT1() {
        assertThat(SC2Data.techTier(BuildingType.SPAWNING_POOL)).hasValue(1);
    }

    @Test
    void techTier_zergT2() {
        assertThat(SC2Data.techTier(BuildingType.ROACH_WARREN)).hasValue(2);
        assertThat(SC2Data.techTier(BuildingType.HYDRALISK_DEN)).hasValue(2);
        assertThat(SC2Data.techTier(BuildingType.BANELING_NEST)).hasValue(2);
    }

    @Test
    void techTier_zergT3() {
        assertThat(SC2Data.techTier(BuildingType.INFESTATION_PIT)).hasValue(3);
        assertThat(SC2Data.techTier(BuildingType.LURKER_DEN)).hasValue(3);
    }

    @Test
    void techTier_zergT4() {
        assertThat(SC2Data.techTier(BuildingType.GREATER_SPIRE)).hasValue(4);
        assertThat(SC2Data.techTier(BuildingType.ULTRALISK_CAVERN)).hasValue(4);
    }

    @Test
    void techTier_nonTechBuildingsReturnEmpty() {
        assertThat(SC2Data.techTier(BuildingType.NEXUS)).isEmpty();
        assertThat(SC2Data.techTier(BuildingType.PYLON)).isEmpty();
        assertThat(SC2Data.techTier(BuildingType.ASSIMILATOR)).isEmpty();
        assertThat(SC2Data.techTier(BuildingType.PHOTON_CANNON)).isEmpty();
        assertThat(SC2Data.techTier(BuildingType.SUPPLY_DEPOT)).isEmpty();
        assertThat(SC2Data.techTier(BuildingType.HATCHERY)).isEmpty();
        assertThat(SC2Data.techTier(BuildingType.EXTRACTOR)).isEmpty();
        assertThat(SC2Data.techTier(BuildingType.UNKNOWN)).isEmpty();
    }

    // --- isWorker ---

    @Test
    void isWorker_workersReturnTrue() {
        assertThat(SC2Data.isWorker(UnitType.PROBE)).isTrue();
        assertThat(SC2Data.isWorker(UnitType.SCV)).isTrue();
        assertThat(SC2Data.isWorker(UnitType.DRONE)).isTrue();
    }

    @Test
    void isWorker_combatUnitsReturnFalse() {
        assertThat(SC2Data.isWorker(UnitType.ZEALOT)).isFalse();
        assertThat(SC2Data.isWorker(UnitType.MARINE)).isFalse();
        assertThat(SC2Data.isWorker(UnitType.ZERGLING)).isFalse();
    }

    // --- isBase ---

    @Test
    void isBase_baseBuildingsReturnTrue() {
        assertThat(SC2Data.isBase(BuildingType.NEXUS)).isTrue();
        assertThat(SC2Data.isBase(BuildingType.COMMAND_CENTER)).isTrue();
        assertThat(SC2Data.isBase(BuildingType.ORBITAL_COMMAND)).isTrue();
        assertThat(SC2Data.isBase(BuildingType.PLANETARY_FORTRESS)).isTrue();
        assertThat(SC2Data.isBase(BuildingType.HATCHERY)).isTrue();
        assertThat(SC2Data.isBase(BuildingType.LAIR)).isTrue();
        assertThat(SC2Data.isBase(BuildingType.HIVE)).isTrue();
    }

    @Test
    void isBase_nonBaseBuildingsReturnFalse() {
        assertThat(SC2Data.isBase(BuildingType.GATEWAY)).isFalse();
        assertThat(SC2Data.isBase(BuildingType.BARRACKS)).isFalse();
        assertThat(SC2Data.isBase(BuildingType.SPAWNING_POOL)).isFalse();
    }

    // --- UnitCosts exhaustive coverage (LotV final balance, Liquipedia-verified) ---
    // Sources: https://liquipedia.net/starcraft2/Units_(Protoss)
    //          https://liquipedia.net/starcraft2/Units_(Terran)
    //          https://liquipedia.net/starcraft2/Units_(Zerg)

    private static void assertCosts(UnitType type, int mineral, int gas, int supply) {
        assertThat(SC2Data.mineralCost(type)).as(type + " mineral").isEqualTo(mineral);
        assertThat(SC2Data.gasCost(type)).as(type + " gas").isEqualTo(gas);
        assertThat(SC2Data.supplyCost(type)).as(type + " supply").isEqualTo(supply);
    }

    @Test
    void allProtossUnitCosts() {
        assertCosts(UnitType.PROBE,              50,   0, 1);
        assertCosts(UnitType.ZEALOT,            100,   0, 2);
        assertCosts(UnitType.STALKER,           125,  50, 2);
        assertCosts(UnitType.IMMORTAL,          250, 100, 4);
        assertCosts(UnitType.COLOSSUS,          300, 200, 6);
        assertCosts(UnitType.CARRIER,           350, 250, 6);
        assertCosts(UnitType.DARK_TEMPLAR,      125, 125, 2);
        assertCosts(UnitType.HIGH_TEMPLAR,       50, 150, 2);
        assertCosts(UnitType.ARCHON,              0,   0, 0);
        assertCosts(UnitType.OBSERVER,           25,  75, 1);
        assertCosts(UnitType.VOID_RAY,          250, 150, 4);
        assertCosts(UnitType.ADEPT,             100,  25, 2);
        assertCosts(UnitType.DISRUPTOR,         150, 150, 3);
        assertCosts(UnitType.SENTRY,             50, 100, 2);
        assertCosts(UnitType.PHOENIX,           150, 100, 2);
        assertCosts(UnitType.ORACLE,            150, 150, 3);
        assertCosts(UnitType.TEMPEST,           250, 175, 4);
        assertCosts(UnitType.MOTHERSHIP,        400, 400, 8);
        assertCosts(UnitType.WARP_PRISM,        200,   0, 2);
        assertCosts(UnitType.WARP_PRISM_PHASING,200,   0, 2);
        assertCosts(UnitType.INTERCEPTOR,        15,   0, 0);
        assertCosts(UnitType.ADEPT_PHASE_SHIFT,   0,   0, 0);
    }

    @Test
    void allTerranUnitCosts() {
        assertCosts(UnitType.MARINE,             50,   0, 1);
        assertCosts(UnitType.MARAUDER,          100,  25, 2);
        assertCosts(UnitType.MEDIVAC,           100, 100, 2);
        assertCosts(UnitType.SIEGE_TANK,        150, 125, 3);
        assertCosts(UnitType.SIEGE_TANK_SIEGED, 150, 125, 3);
        assertCosts(UnitType.THOR,              300, 200, 6);
        assertCosts(UnitType.VIKING,            150,  75, 2);
        assertCosts(UnitType.GHOST,             150, 125, 2);
        assertCosts(UnitType.RAVEN,             100, 200, 2);
        assertCosts(UnitType.BANSHEE,           150, 100, 3);
        assertCosts(UnitType.BATTLECRUISER,     400, 300, 6);
        assertCosts(UnitType.CYCLONE,           150, 100, 3);
        assertCosts(UnitType.LIBERATOR,         150, 150, 3);
        assertCosts(UnitType.WIDOW_MINE,         75,  25, 2);
        assertCosts(UnitType.SCV,                50,   0, 1);
        assertCosts(UnitType.REAPER,             50,  50, 1);
        assertCosts(UnitType.HELLION,           100,   0, 2);
        assertCosts(UnitType.HELLBAT,           100,   0, 2);
        assertCosts(UnitType.MULE,                0,   0, 0);
        assertCosts(UnitType.VIKING_ASSAULT,    150,  75, 2);
        assertCosts(UnitType.LIBERATOR_AG,      150, 150, 3);
        assertCosts(UnitType.AUTO_TURRET,         0,   0, 0);
    }

    @Test
    void allZergUnitCosts() {
        assertCosts(UnitType.ZERGLING,           25,   0, 1);
        assertCosts(UnitType.ROACH,              75,  25, 2);
        assertCosts(UnitType.HYDRALISK,         100,  50, 2);
        assertCosts(UnitType.MUTALISK,          100, 100, 2);
        assertCosts(UnitType.ULTRALISK,         300, 200, 6);
        assertCosts(UnitType.BROOD_LORD,        150, 150, 2);
        assertCosts(UnitType.CORRUPTOR,         150, 100, 2);
        assertCosts(UnitType.INFESTOR,          100, 150, 2);
        assertCosts(UnitType.SWARM_HOST,        100,  75, 3);
        assertCosts(UnitType.VIPER,             100, 200, 3);
        assertCosts(UnitType.QUEEN,             150,   0, 2);
        assertCosts(UnitType.RAVAGER,            25,  75, 1);
        assertCosts(UnitType.LURKER,             50, 100, 1);
        assertCosts(UnitType.DRONE,              50,   0, 1);
        assertCosts(UnitType.OVERLORD,          100,   0, 0);
        assertCosts(UnitType.OVERSEER,           50,  50, 0);
        assertCosts(UnitType.BANELING,           25,  25, 0);
        assertCosts(UnitType.LOCUST,              0,   0, 0);
        assertCosts(UnitType.BROODLING,           0,   0, 0);
        assertCosts(UnitType.INFESTED_TERRAN,     0,   0, 0);
        assertCosts(UnitType.CHANGELING,          0,   0, 0);
        assertCosts(UnitType.EGG,                 0,   0, 0);
    }

    @Test
    void unknownSentinelCosts() {
        assertCosts(UnitType.UNKNOWN, 0, 0, 0);
    }

    @Test
    void unitCostsAccessorMatchesDelegates() {
        for (UnitType type : UnitType.values()) {
            UnitCosts costs = SC2Data.unitCosts(type);
            assertThat(costs.mineral()).as(type + " mineral via unitCosts").isEqualTo(SC2Data.mineralCost(type));
            assertThat(costs.gas()).as(type + " gas via unitCosts").isEqualTo(SC2Data.gasCost(type));
            assertThat(costs.supply()).as(type + " supply via unitCosts").isEqualTo(SC2Data.supplyCost(type));
        }
    }

    @Test
    void fleetScenarioCorrectValuation() {
        int value = 4 * (SC2Data.mineralCost(UnitType.CARRIER) + SC2Data.gasCost(UnitType.CARRIER))
                  + 3 * (SC2Data.mineralCost(UnitType.COLOSSUS) + SC2Data.gasCost(UnitType.COLOSSUS))
                  + 2 * (SC2Data.mineralCost(UnitType.VOID_RAY) + SC2Data.gasCost(UnitType.VOID_RAY));
        assertThat(value).isEqualTo(4700);
    }

    @Test
    void unitCombatStatsAccessor_stalker() {
        UnitCombatStats stats = SC2Data.unitCombatStats(UnitType.STALKER);
        assertThat(stats.damagePerAttack()).isEqualTo(13);
        assertThat(stats.attackCooldownInTicks()).isEqualTo(1);
        assertThat(stats.attackRange()).isEqualTo(5.0f);
        assertThat(stats.bonusDamageVs()).containsEntry(UnitAttribute.ARMORED, 4);
    }

    @Test
    void unitCombatStatsAccessor_zealot() {
        UnitCombatStats stats = SC2Data.unitCombatStats(UnitType.ZEALOT);
        assertThat(stats.damagePerAttack()).isEqualTo(8);
        assertThat(stats.attackCooldownInTicks()).isEqualTo(2);
        assertThat(stats.attackRange()).isEqualTo(0.5f);
        assertThat(stats.bonusDamageVs()).isEmpty();
    }

    @Test
    void unitDefensesAccessor_stalker() {
        UnitDefenses def = SC2Data.unitDefenses(UnitType.STALKER);
        assertThat(def.maxHealth()).isEqualTo(80);
        assertThat(def.maxShields()).isEqualTo(80);
        assertThat(def.armour()).isEqualTo(1);
    }

    @Test
    void unitDefensesAccessor_marine() {
        UnitDefenses def = SC2Data.unitDefenses(UnitType.MARINE);
        assertThat(def.maxHealth()).isEqualTo(45);
        assertThat(def.maxShields()).isEqualTo(0);
        assertThat(def.armour()).isEqualTo(0);
    }

    @Test
    void allUnitTypesHaveCombatStats() {
        for (UnitType type : UnitType.values()) {
            assertThat(SC2Data.unitCombatStats(type))
                    .as("Missing UnitCombatStats for " + type)
                    .isNotNull();
        }
    }

    @Test
    void allUnitTypesHaveDefenses() {
        for (UnitType type : UnitType.values()) {
            assertThat(SC2Data.unitDefenses(type))
                    .as("Missing UnitDefenses for " + type)
                    .isNotNull();
        }
    }

    @Test
    void allUnitTypesHaveAttributes() {
        for (UnitType type : UnitType.values()) {
            assertThat(SC2Data.unitAttributes(type))
                    .as("Missing unitAttributes for " + type)
                    .isNotNull();
        }
    }

    @Test
    void allUnitTypesHaveTrainTime() {
        for (UnitType type : UnitType.values()) {
            assertThat(SC2Data.trainTimeInLoops(type))
                    .as("Missing trainTimeInLoops for " + type)
                    .isGreaterThan(0);
        }
    }

    @Test
    void allUnitTypesHaveSightRange() {
        for (UnitType type : UnitType.values()) {
            assertThat(SC2Data.sightRange(type))
                    .as("Missing sightRange for " + type)
                    .isGreaterThan(0);
        }
    }
}

package io.quarkmind.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static io.quarkmind.domain.UnitAttribute.*;

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
    }

    @Test
    void trainTimeInTicksDefinedForProtossUnits() {
        assertThat(SC2Data.trainTimeInTicks(UnitType.PROBE))    .isEqualTo(12);
        assertThat(SC2Data.trainTimeInTicks(UnitType.ZEALOT))   .isEqualTo(28);
        assertThat(SC2Data.trainTimeInTicks(UnitType.STALKER))  .isEqualTo(31);
        assertThat(SC2Data.trainTimeInTicks(UnitType.IMMORTAL)) .isEqualTo(40);
        assertThat(SC2Data.trainTimeInTicks(UnitType.OBSERVER)) .isEqualTo(22);
    }
}

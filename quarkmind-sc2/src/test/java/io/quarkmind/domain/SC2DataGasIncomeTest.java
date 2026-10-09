package io.quarkmind.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class SC2DataGasIncomeTest {

    @Test
    void gasIncomePerTick_zeroWorkers_returnsZero() {
        assertThat(SC2Data.gasIncomePerTick(0)).isEqualTo(0.0);
    }

    @Test
    void gasIncomePerTick_oneWorker_returnsTier0Only() {
        double expected = SC2Data.GAS_TIER_RATES_PER_TICK[0];
        assertThat(SC2Data.gasIncomePerTick(1)).isCloseTo(expected, within(0.0001));
    }

    @Test
    void gasIncomePerTick_twoWorkers_returnsTier0PlusTier1() {
        double expected = SC2Data.GAS_TIER_RATES_PER_TICK[0]
                        + SC2Data.GAS_TIER_RATES_PER_TICK[1];
        assertThat(SC2Data.gasIncomePerTick(2)).isCloseTo(expected, within(0.0001));
    }

    @Test
    void gasIncomePerTick_threeWorkers_returnsAllTiers() {
        double expected = SC2Data.GAS_TIER_RATES_PER_TICK[0]
                        + SC2Data.GAS_TIER_RATES_PER_TICK[1]
                        + SC2Data.GAS_TIER_RATES_PER_TICK[2];
        assertThat(SC2Data.gasIncomePerTick(3)).isCloseTo(expected, within(0.0001));
    }

    @Test
    void gasIncomePerTick_beyondThreeWorkers_capsAtThreeTiers() {
        assertThat(SC2Data.gasIncomePerTick(5))
            .isCloseTo(SC2Data.gasIncomePerTick(3), within(0.0001));
    }

    @Test
    void gasIncomePerTick_negativeWorkers_throws() {
        assertThatThrownBy(() -> SC2Data.gasIncomePerTick(-1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isGasBuilding_allGasTypes_returnTrue() {
        assertThat(SC2Data.isGasBuilding(BuildingType.ASSIMILATOR)).isTrue();
        assertThat(SC2Data.isGasBuilding(BuildingType.ASSIMILATOR_RICH)).isTrue();
        assertThat(SC2Data.isGasBuilding(BuildingType.REFINERY)).isTrue();
        assertThat(SC2Data.isGasBuilding(BuildingType.EXTRACTOR)).isTrue();
    }

    @Test
    void isGasBuilding_nonGasTypes_returnFalse() {
        assertThat(SC2Data.isGasBuilding(BuildingType.NEXUS)).isFalse();
        assertThat(SC2Data.isGasBuilding(BuildingType.GATEWAY)).isFalse();
        assertThat(SC2Data.isGasBuilding(BuildingType.COMMAND_CENTER)).isFalse();
        assertThat(SC2Data.isGasBuilding(BuildingType.HATCHERY)).isFalse();
    }

    @Test
    void gasTierRates_hasThreeElements() {
        assertThat(SC2Data.GAS_TIER_RATES_PER_TICK).hasSize(3);
    }

    @Test
    void gasWorkersPerBuilding_isThree() {
        assertThat(SC2Data.GAS_WORKERS_PER_BUILDING).isEqualTo(3);
    }
}

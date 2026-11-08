package io.quarkmind.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SC2DataGasIncomeTest {

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
    void gasWorkersPerBuilding_isThree() {
        assertThat(SC2Data.GAS_WORKERS_PER_BUILDING).isEqualTo(3);
    }
}

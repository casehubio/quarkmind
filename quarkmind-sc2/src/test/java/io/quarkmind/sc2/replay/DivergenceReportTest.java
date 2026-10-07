package io.quarkmind.sc2.replay;

import io.quarkmind.domain.*;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DivergenceReportTest {

    @Test
    void tickSnapshotExposePerTypeMaps() {
        var unitsByType = Map.of(UnitType.MARINE, 5, UnitType.STALKER, 3);
        var emUnitsByType = Map.of(UnitType.MARINE, 4, UnitType.STALKER, 3);
        var bldgsByType = Map.of(BuildingType.BARRACKS, 2);
        var emBldgsByType = Map.of(BuildingType.BARRACKS, 2);
        var gtUpgrades = Set.of("Stimpack");
        var emUpgrades = Set.of("Stimpack");

        var snap = new DivergenceReport.TickSnapshot(
            10, 8, 7, 2, 2, 1000, 950, 200, 200,
            unitsByType, emUnitsByType,
            bldgsByType, emBldgsByType,
            gtUpgrades, emUpgrades,
            PlayerEconomyStats.EMPTY, PlayerEconomyStats.EMPTY);

        assertThat(snap.groundTruthUnitsByType()).isEqualTo(unitsByType);
        assertThat(snap.emulatedUnitsByType()).isEqualTo(emUnitsByType);
        assertThat(snap.groundTruthBuildingsByType()).containsEntry(BuildingType.BARRACKS, 2);
        assertThat(snap.groundTruthUpgrades()).isEqualTo(gtUpgrades);
        assertThat(snap.emulatedUpgrades()).isEqualTo(emUpgrades);
        assertThat(snap.groundTruthEconomy()).isEqualTo(PlayerEconomyStats.EMPTY);
        assertThat(snap.unitDelta()).isEqualTo(1);
    }
}

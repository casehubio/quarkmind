package io.quarkmind.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UpgradeTypeTest {

    @Test
    void allClassifierUpgradesCovered() {
        String[] classifierUpgrades = {
            "Stimpack", "ShieldWall", "PunisherGrenades", "BansheeCloak",
            "TerranVehicleWeaponsLevel1", "PersonalCloaking", "DrillClaws",
            "zerglingmovementspeed", "GlialReconstitution", "CentrificalHooks",
            "Burrow", "WarpGateResearch", "BlinkTech", "Charge",
            "AdeptPiercingAttack"
        };
        for (String name : classifierUpgrades) {
            assertThat(UpgradeType.fromPythonName(name))
                .as("UpgradeType for classifier Python name '%s'", name)
                .isNotNull();
        }
    }

    @Test
    void allGameplayUpgradesCovered() {
        assertThat(UpgradeType.values()).hasSize(82);
    }

    @Test
    void pythonNameRoundTrips() {
        for (UpgradeType ut : UpgradeType.values()) {
            assertThat(UpgradeType.fromPythonName(ut.pythonName()))
                .as("fromPythonName(%s) round-trip", ut.pythonName())
                .isEqualTo(ut);
        }
    }

    @Test
    void upgradeTimesArePositive() {
        for (UpgradeType ut : UpgradeType.values()) {
            assertThat(SC2Data.upgradeTimeInLoops(ut))
                .as("upgradeTimeInLoops(%s)", ut)
                .isGreaterThan(0);
        }
    }

    @Test
    void pythonNamesAreUnique() {
        var names = java.util.Arrays.stream(UpgradeType.values())
                .map(UpgradeType::pythonName)
                .toList();
        assertThat(names).doesNotHaveDuplicates();
    }
}

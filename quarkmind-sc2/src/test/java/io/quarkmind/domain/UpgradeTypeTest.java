package io.quarkmind.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UpgradeTypeTest {

    @Test
    void allClassifierUpgradesCovered() {
        String[] expected = {
            "Stimpack", "ShieldWall", "PunisherGrenades", "BansheeCloak",
            "TerranVehicleWeaponsLevel1", "PersonalCloaking", "DrillClaws",
            "zerglingmovementspeed", "GlialReconstitution", "CentrificalHooks",
            "Burrow", "WarpGateResearch", "BlinkTech", "Charge",
            "AdeptPiercingAttack"
        };
        assertThat(UpgradeType.values()).hasSize(expected.length);
        for (String name : expected) {
            assertThat(UpgradeType.fromPythonName(name))
                .as("UpgradeType for Python name '%s'", name)
                .isNotNull();
        }
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
}

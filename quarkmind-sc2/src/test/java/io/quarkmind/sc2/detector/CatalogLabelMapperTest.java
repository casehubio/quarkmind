package io.quarkmind.sc2.detector;

import io.quarkmind.domain.Race;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogLabelMapperTest {

    @Test
    void rushIsValidForAllRaces() {
        assertThat(CatalogLabelMapper.isValidLabel("RUSH", Race.TERRAN)).isTrue();
        assertThat(CatalogLabelMapper.isValidLabel("RUSH", Race.PROTOSS)).isTrue();
        assertThat(CatalogLabelMapper.isValidLabel("RUSH", Race.ZERG)).isTrue();
    }

    @Test
    void cannonRushIsOnlyValidForProtoss() {
        assertThat(CatalogLabelMapper.isValidLabel("CANNON_RUSH", Race.PROTOSS)).isTrue();
        assertThat(CatalogLabelMapper.isValidLabel("CANNON_RUSH", Race.TERRAN)).isFalse();
        assertThat(CatalogLabelMapper.isValidLabel("CANNON_RUSH", Race.ZERG)).isFalse();
    }

    @Test
    void unknownLabelIsInvalid() {
        assertThat(CatalogLabelMapper.isValidLabel("UNKNOWN", Race.TERRAN)).isFalse();
        assertThat(CatalogLabelMapper.isValidLabel("TERRAN_MARINE_RUSH", Race.TERRAN)).isFalse();
    }

    @Test
    void validLabelsForTerranMatchup() {
        var labels = CatalogLabelMapper.validLabelsForMatchup(Race.TERRAN);
        assertThat(labels).contains("RUSH", "MACRO_ECONOMY", "BIO_TIMING",
                "BANSHEE_HARASS", "AIR_SUPERIORITY", "MECH_PUSH", "TECH_RUSH");
        assertThat(labels).doesNotContain("CANNON_RUSH", "DT_RUSH", "ROACH_RUSH");
    }

    @Test
    void validLabelsForZergMatchup() {
        var labels = CatalogLabelMapper.validLabelsForMatchup(Race.ZERG);
        assertThat(labels).contains("RUSH", "ROACH_RUSH", "MACRO_ECONOMY",
                "MUTA_HARASS", "HYDRA_PUSH", "LING_BANE");
        assertThat(labels).doesNotContain("CANNON_RUSH", "BIO_TIMING");
    }

    @Test
    void validLabelsForProtossMatchup() {
        var labels = CatalogLabelMapper.validLabelsForMatchup(Race.PROTOSS);
        assertThat(labels).contains("RUSH", "PROXY", "CANNON_RUSH", "MACRO_ECONOMY",
                "AIR_SUPERIORITY", "DT_RUSH", "COLOSSUS_PUSH", "BLINK_STALKER");
        assertThat(labels).doesNotContain("BIO_TIMING", "ROACH_RUSH");
    }
}

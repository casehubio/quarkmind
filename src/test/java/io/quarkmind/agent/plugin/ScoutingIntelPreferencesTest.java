package io.quarkmind.agent.plugin;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ScoutingIntelPreferencesTest {

    @Test
    void consumerKey_tactics_threatPosition_hasExpectedQualifiedName() {
        var key = ScoutingIntelPreferences.consumerKey("tactics.drools-goap", ScoutingIntelType.THREAT_POSITION);
        assertThat(key.qualifiedName())
            .isEqualTo("scouting.intel.consumer.tactics.drools-goap.threat-position");
    }

    @Test
    void consumerKey_default_threatPosition_isTrue() {
        var key = ScoutingIntelPreferences.consumerKey("any-plugin", ScoutingIntelType.THREAT_POSITION);
        assertThat(key.defaultValue().asBoolean()).isTrue();
    }

    @Test
    void consumerKey_default_armySize_isFalse() {
        var key = ScoutingIntelPreferences.consumerKey("any-plugin", ScoutingIntelType.ARMY_SIZE);
        assertThat(key.defaultValue().asBoolean()).isFalse();
    }

    @Test
    void toKebab_allTypes_roundTripsCorrectly() {
        assertThat(ScoutingIntelPreferences.toKebab(ScoutingIntelType.THREAT_POSITION)).isEqualTo("threat-position");
        assertThat(ScoutingIntelPreferences.toKebab(ScoutingIntelType.TIMING_ALERT)).isEqualTo("timing-alert");
        assertThat(ScoutingIntelPreferences.toKebab(ScoutingIntelType.BUILD_ORDER)).isEqualTo("build-order");
        assertThat(ScoutingIntelPreferences.toKebab(ScoutingIntelType.ARMY_SIZE)).isEqualTo("army-size");
        assertThat(ScoutingIntelPreferences.toKebab(ScoutingIntelType.POSTURE)).isEqualTo("posture");
    }

    @Test
    void threatPositionMinDistanceKey_defaultIsZero() {
        assertThat(ScoutingIntelPreferences.THREAT_POSITION_MIN_DISTANCE.defaultValue().asDouble())
            .isEqualTo(0.0);
    }

    @Test
    void armySizeMinDeltaKey_defaultIsOne() {
        assertThat(ScoutingIntelPreferences.ARMY_SIZE_MIN_DELTA.defaultValue().asInt()).isEqualTo(1);
    }
}

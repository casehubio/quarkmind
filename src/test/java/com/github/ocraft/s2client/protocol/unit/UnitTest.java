package com.github.ocraft.s2client.protocol.unit;

/*-
 * Adapted from ocraft/ocraft-s2client (MIT License).
 * Removed fulfillsEqualsContract() and isDiffableVia*() — require de.danielbechler.diff
 * and nl.jqno.equalsverifier, which are not on the quarkmind classpath.
 * Core Unit.from() correctness tests preserved.
 */

import SC2APIProtocol.Raw;
import com.github.ocraft.s2client.protocol.data.Units;
import org.junit.jupiter.api.Test;

import static com.github.ocraft.s2client.protocol.Constants.nothing;
import static com.github.ocraft.s2client.protocol.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class UnitTest {

    @Test
    void throwsExceptionWhenSc2ApiUnitIsNull() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> Unit.from(nothing()))
                .withMessage("sc2api unit is required");
    }

    @Test
    void convertsAllFieldsFromSc2ApiUnit() {
        Unit unit = Unit.from(sc2ApiUnit());
        assertThat(unit.getDisplayType()).as("unit: display type").isEqualTo(DisplayType.VISIBLE);
        assertThat(unit.getAlliance()).as("unit: alliance").isEqualTo(Alliance.SELF);
        assertThat(unit.getTag()).as("unit: tag").isEqualTo(Tag.from(UNIT_TAG));
        assertThat(unit.getType()).as("unit: type").isEqualTo(Units.PROTOSS_NEXUS);
        assertThat(unit.getOwner()).as("unit: owner").isEqualTo(PLAYER_ID);
        assertThat(unit.getPosition()).as("unit: position").isNotNull();
        assertThat(unit.getBuildProgress()).as("unit: build progress").isEqualTo(UNIT_BUILD_PROGRESS);
        assertThat(unit.getHealth()).as("unit: health").hasValue(UNIT_HEALTH);
        assertThat(unit.getHealthMax()).as("unit: health max").hasValue(UNIT_HEALTH_MAX);
        assertThat(unit.getShield()).as("unit: shield").hasValue(UNIT_SHIELD);
        assertThat(unit.getShieldMax()).as("unit: shield max").hasValue(UNIT_SHIELD_MAX);
    }

    @Test
    void hasTagFromProto() {
        Raw.Unit rawUnit = sc2ApiUnit();
        Unit unit = Unit.from(rawUnit);
        assertThat(unit.getTag().getValue()).isEqualTo(UNIT_TAG);
    }

    @Test
    void hasAllianceFromProto() {
        Unit unit = Unit.from(sc2ApiUnit());
        assertThat(unit.getAlliance()).isEqualTo(Alliance.SELF);
    }
}

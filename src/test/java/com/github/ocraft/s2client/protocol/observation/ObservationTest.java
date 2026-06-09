package com.github.ocraft.s2client.protocol.observation;

/*-
 * Adapted from ocraft/ocraft-s2client (MIT License).
 * Removed fulfillsEqualsContract() — requires nl.jqno.equalsverifier not on quarkmind classpath.
 */

import SC2APIProtocol.Sc2Api;
import org.junit.jupiter.api.Test;

import static com.github.ocraft.s2client.protocol.Constants.nothing;
import static com.github.ocraft.s2client.protocol.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class ObservationTest {

    @Test
    void throwsExceptionWhenSc2ApiObservationIsNull() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> Observation.from(nothing()))
                .withMessage("sc2api observation is required");
    }

    @Test
    void convertsAllFieldsFromSc2ApiObservation() {
        Observation observation = Observation.from(sc2ApiObservation());
        assertThat(observation.getGameLoop()).as("observation: game loop").isEqualTo(GAME_LOOP);
        assertThat(observation.getPlayerCommon()).as("observation: player common").isNotNull();
        assertThat(observation.getAlerts()).as("observation: alerts").containsOnlyElementsOf(ALERTS);
        assertThat(observation.getAvailableAbilities()).as("observation: available abilities").isNotEmpty();
        assertThat(observation.getScore()).as("observation: score").isNotNull();
        assertThat(observation.getRaw()).as("observation: raw").isNotEmpty();
        assertThat(observation.getFeatureLayer()).as("observation: feature layer").isNotEmpty();
        assertThat(observation.getRender()).as("observation: render").isNotEmpty();
        assertThat(observation.getUi()).as("observation: ui").isNotEmpty();
    }

    @Test
    void throwsExceptionWhenGameLoopIsNotProvided() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> Observation.from(without(
                        () -> sc2ApiObservation().toBuilder(),
                        Sc2Api.Observation.Builder::clearGameLoop).build()))
                .withMessage("game loop is required");
    }

    @Test
    void throwsExceptionWhenPlayerCommonIsNotProvided() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> Observation.from(without(
                        () -> sc2ApiObservation().toBuilder(),
                        Sc2Api.Observation.Builder::clearPlayerCommon).build()))
                .withMessage("player common is required");
    }

    @Test
    void hasEmptySetOfAlertsWhenNotProvided() {
        assertThat(Observation.from(
                without(() -> sc2ApiObservation().toBuilder(), Sc2Api.Observation.Builder::clearAlerts).build()
        ).getAlerts()).as("observation: empty alert set").isEmpty();
    }

    @Test
    void hasEmptySetOfAvailableAbilityWhenNotProvided() {
        assertThat(Observation.from(
                without(() -> sc2ApiObservation().toBuilder(), Sc2Api.Observation.Builder::clearAbilities).build()
        ).getAvailableAbilities()).as("observation: empty available ability set").isEmpty();
    }

    @Test
    void throwsExceptionWhenOneOfInterfacesNotProvided() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> Observation.from(without(
                        () -> sc2ApiObservation().toBuilder(),
                        Sc2Api.Observation.Builder::clearRawData,
                        Sc2Api.Observation.Builder::clearFeatureLayerData,
                        Sc2Api.Observation.Builder::clearRenderData).build()))
                .withMessage("one of interfaces is required");
    }
}

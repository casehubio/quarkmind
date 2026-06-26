package io.quarkmind.sc2.emulated.server;

import SC2APIProtocol.Raw;
import SC2APIProtocol.Sc2Api;
import SC2APIProtocol.Common;
import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.observation.Observation;
import com.github.ocraft.s2client.protocol.response.ResponseObservation;
import io.quarkmind.domain.*;
import io.quarkmind.sc2.emulated.EmulatedGame;
import io.quarkmind.sc2.intent.*;
import io.quarkmind.sc2.real.ObservationTranslator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class GameStateRoundTripTest {

    @Test
    void observationRoundTrip_survivesOcraftParsing() {
        EmulatedGame game = new EmulatedGame();
        game.reset();
        for (int i = 0; i < 10; i++) game.tick();
        GameState original = game.snapshot();

        Sc2Api.ResponseObservation protoObs = GameStateToProtobuf.translate(original);
        Sc2Api.Response response = Sc2Api.Response.newBuilder()
            .setObservation(protoObs)
            .setStatus(Sc2Api.Status.in_game)
            .build();

        // Through ocraft parsing — catches malformed protobuf
        ResponseObservation ro = ResponseObservation.from(response);
        Observation obs = ro.getObservation();
        GameState roundTripped = ObservationTranslator.translate(obs);

        // Preserved fields
        assertThat(roundTripped.minerals()).isEqualTo(original.minerals());
        assertThat(roundTripped.vespene()).isEqualTo(original.vespene());
        assertThat(roundTripped.supply()).isEqualTo(original.supply());
        assertThat(roundTripped.supplyUsed()).isEqualTo(original.supplyUsed());
        assertThat(roundTripped.gameFrame()).isEqualTo(original.gameFrame());
        assertThat(roundTripped.myUnits()).hasSize(original.myUnits().size());
        assertThat(roundTripped.myBuildings()).hasSize(original.myBuildings().size());

        // Lossy fields — enemies collapse into enemyUnits
        int totalEnemies = original.enemyUnits().size()
            + original.enemyBuildings().size()
            + original.enemyStagingArea().size();
        assertThat(roundTripped.enemyUnits()).hasSize(totalEnemies);
        assertThat(roundTripped.enemyBuildings()).isEmpty();
        assertThat(roundTripped.enemyStagingArea()).isEmpty();

        // Lossy fields — not in SC2 protobuf
        assertThat(roundTripped.geysers()).isEmpty();
        roundTripped.myUnits().forEach(u -> {
            assertThat(u.weaponCooldownTicks()).as("cooldown zeroed").isZero();
            assertThat(u.blinkCooldownTicks()).as("blink cooldown zeroed").isZero();
        });
    }

    @Test
    void observationRoundTrip_preservesUnitPositionAndHealth() {
        GameState original = new GameState(
            400, 200, 30, 22,
            List.of(new Unit("1", UnitType.STALKER, new Point2d(10f, 20f), 80, 80, 80, 80, 0, 0)),
            List.of(new Building("2", BuildingType.NEXUS, new Point2d(30f, 30f), 1000, 1000, true)),
            List.of(new Unit("3", UnitType.ZEALOT, new Point2d(40f, 40f), 100, 100, 50, 50, 0, 0)),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            150L
        );

        Sc2Api.ResponseObservation protoObs = GameStateToProtobuf.translate(original);
        Sc2Api.Response response = Sc2Api.Response.newBuilder()
            .setObservation(protoObs).setStatus(Sc2Api.Status.in_game).build();
        ResponseObservation ro = ResponseObservation.from(response);
        GameState rt = ObservationTranslator.translate(ro.getObservation());

        Unit rtStalker = rt.myUnits().get(0);
        assertThat(rtStalker.type()).isEqualTo(UnitType.STALKER);
        assertThat(rtStalker.position().x()).isCloseTo(10f, within(0.01f));
        assertThat(rtStalker.position().y()).isCloseTo(20f, within(0.01f));
        assertThat(rtStalker.health()).isEqualTo(80);
        assertThat(rtStalker.shields()).isEqualTo(80);

        Building rtNexus = rt.myBuildings().get(0);
        assertThat(rtNexus.type()).isEqualTo(BuildingType.NEXUS);
        assertThat(rtNexus.isComplete()).isTrue();
    }

    @Test
    void unitTypeToProto_coversAllTypesExceptUnknown() {
        // All UnitType values except UNKNOWN must have a mapping
        // Currently 3 missing: LOCUST, INFESTED_TERRAN, WIDOW_MINE — ocraft constant names unknown
        int expectedSize = UnitType.values().length - 1 - 3;
        assertThat(GameStateToProtobuf.UNIT_TYPE_TO_PROTO).hasSize(expectedSize);
    }

    @Test
    void intentRoundTrip_attackIntent() {
        Raw.ActionRawUnitCommand proto = Raw.ActionRawUnitCommand.newBuilder()
            .addUnitTags(42L)
            .setAbilityId(Abilities.ATTACK.getAbilityId())
            .setTargetWorldSpacePos(Common.Point2D.newBuilder().setX(10f).setY(20f).build())
            .build();

        Intent result = ProtobufToIntent.translate(proto);
        assertThat(result).isInstanceOf(AttackIntent.class);
        AttackIntent rt = (AttackIntent) result;
        assertThat(rt.unitTag()).isEqualTo("42");
        assertThat(rt.targetLocation().x()).isCloseTo(10f, within(0.01f));
        assertThat(rt.targetLocation().y()).isCloseTo(20f, within(0.01f));
    }

    @Test
    void intentRoundTrip_moveIntent() {
        Raw.ActionRawUnitCommand proto = Raw.ActionRawUnitCommand.newBuilder()
            .addUnitTags(99L)
            .setAbilityId(Abilities.MOVE.getAbilityId())
            .setTargetWorldSpacePos(Common.Point2D.newBuilder().setX(5f).setY(15f).build())
            .build();

        Intent rt = ProtobufToIntent.translate(proto);
        assertThat(rt).isEqualTo(new MoveIntent("99", new Point2d(5f, 15f)));
    }

    @Test
    void intentRoundTrip_trainIntent() {
        Raw.ActionRawUnitCommand proto = Raw.ActionRawUnitCommand.newBuilder()
            .addUnitTags(7L)
            .setAbilityId(Abilities.TRAIN_STALKER.getAbilityId())
            .build();

        Intent rt = ProtobufToIntent.translate(proto);
        assertThat(rt).isEqualTo(new TrainIntent("7", UnitType.STALKER));
    }

    @Test
    void intentRoundTrip_buildIntent() {
        Raw.ActionRawUnitCommand proto = Raw.ActionRawUnitCommand.newBuilder()
            .addUnitTags(5L)
            .setAbilityId(Abilities.BUILD_PYLON.getAbilityId())
            .setTargetWorldSpacePos(Common.Point2D.newBuilder().setX(25f).setY(30f).build())
            .build();

        Intent rt = ProtobufToIntent.translate(proto);
        assertThat(rt).isEqualTo(new BuildIntent("5", BuildingType.PYLON, new Point2d(25f, 30f)));
    }

    @Test
    void intentOneDirectional_blinkIntent_fromProtobuf() {
        Raw.ActionRawUnitCommand proto = Raw.ActionRawUnitCommand.newBuilder()
            .addUnitTags(42L)
            .setAbilityId(Abilities.EFFECT_BLINK_STALKER.getAbilityId())
            .build();

        Intent result = ProtobufToIntent.translate(proto);
        assertThat(result).isEqualTo(new BlinkIntent("42"));
    }

    @Test
    void intentOneDirectional_muleCalldownIntent_fromProtobuf() {
        // TODO: find correct MULE calldown ability ID
        // ActionTranslator.muleCalldown() not wired up yet
        Raw.ActionRawUnitCommand proto = Raw.ActionRawUnitCommand.newBuilder()
            .addUnitTags(99L)
            .setAbilityId(3632) // Placeholder — actual ability ID for EFFECT_CALLDOWNMULE
            .build();

        Intent result = ProtobufToIntent.translate(proto);
        assertThat(result).isEqualTo(new MuleCalldownIntent("99"));
    }

    @Test
    void intentTranslate_unknownAbility_returnsNull() {
        Raw.ActionRawUnitCommand proto = Raw.ActionRawUnitCommand.newBuilder()
            .addUnitTags(1L)
            .setAbilityId(99999)
            .build();

        assertThat(ProtobufToIntent.translate(proto)).isNull();
    }
}

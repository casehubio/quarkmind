package io.quarkmind.sc2.playbook;

import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.emulated.EmulatedGame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SC2DeliveryHandlerTest {

    private EmulatedGame game;
    private SC2DeliveryHandler handler;

    @BeforeEach
    void setUp() {
        game = new EmulatedGame();
        game.reset();
        handler = new SC2DeliveryHandler(game);
    }

    @Test
    void trainAction_producesTrainIntent() {
        var data = Map.<String, Object>of("action", "train", "train", "PROBE");
        var outcome = handler.execute("train-0", data, null);
        assertThat(outcome.success()).isTrue();
        assertThat(outcome.result()).containsEntry("unit", "PROBE");

        int trainTicks = SC2Data.trainTimeInTicks(UnitType.PROBE) + 1;
        for (int i = 0; i < trainTicks; i++) game.tick();

        long probes = game.snapshot().myUnits().stream()
            .filter(u -> u.type() == UnitType.PROBE).count();
        assertThat(probes).isGreaterThan(12);
    }

    @Test
    void buildAction_producesStepOutcome() {
        for (int i = 0; i < 30; i++) game.tick();
        var data = Map.<String, Object>of("action", "build", "build", "PYLON");
        var outcome = handler.execute("build-0", data, null);
        assertThat(outcome.success()).isTrue();
        assertThat(outcome.result()).containsEntry("building", "PYLON");
    }

    @Test
    void assertAction_passesWhenConditionMet() {
        var expect = Map.<String, Object>of("units",
            Map.of("PROBE", Map.of("min", 12)));
        var data = Map.<String, Object>of("action", "assert", "expect", expect);
        var outcome = handler.execute("assert-0", data, null);
        assertThat(outcome.success()).isTrue();
    }

    @Test
    void assertAction_throwsWhenConditionFails() {
        var expect = Map.<String, Object>of("units",
            Map.of("PROBE", Map.of("min", 100)));
        var data = Map.<String, Object>of("action", "assert", "expect", expect);
        assertThatThrownBy(() -> handler.execute("assert-0", data, null))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("expected >=100");
    }

    @Test
    void unknownAction_fails() {
        var data = Map.<String, Object>of("action", "dance");
        var outcome = handler.execute("dance-0", data, null);
        assertThat(outcome.success()).isFalse();
    }

    @Test
    void abilityAction_chronoBoost_succeeds() {
        game.setPlayerRaceModel(io.quarkmind.sc2.emulated.RaceModelFactory.forRace(io.quarkmind.domain.Race.PROTOSS));
        game.reset();
        handler = new SC2DeliveryHandler(game);
        var outcome = handler.execute("chrono-1",
                                      Map.of("action", "ability", "ability", "CHRONO_BOOST", "target", "NEXUS"), null);
        assertThat(outcome.success()).isTrue();
    }

    @Test
    void abilityAction_chronoBoost_insufficientEnergy_fails() {
        game.setPlayerRaceModel(io.quarkmind.sc2.emulated.RaceModelFactory.forRace(io.quarkmind.domain.Race.PROTOSS));
        game.reset();
        handler = new SC2DeliveryHandler(game);
        handler.execute("chrono-1",
                        Map.of("action", "ability", "ability", "CHRONO_BOOST", "target", "NEXUS"), null);
        var outcome = handler.execute("chrono-2",
                                      Map.of("action", "ability", "ability", "CHRONO_BOOST", "target", "NEXUS"), null);
        assertThat(outcome.success()).isFalse();
    }

    @Test
    void abilityAction_unknownAbility_fails() {
        game.setPlayerRaceModel(io.quarkmind.sc2.emulated.RaceModelFactory.forRace(io.quarkmind.domain.Race.PROTOSS));
        game.reset();
        handler = new SC2DeliveryHandler(game);
        var outcome = handler.execute("unknown-1",
                                      Map.of("action", "ability", "ability", "UNKNOWN", "target", "NEXUS"), null);
        assertThat(outcome.success()).isFalse();
    }

}

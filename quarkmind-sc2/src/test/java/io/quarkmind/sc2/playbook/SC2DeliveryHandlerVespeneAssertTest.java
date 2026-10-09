package io.quarkmind.sc2.playbook;

import io.quarkmind.sc2.emulated.EmulatedGame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SC2DeliveryHandlerVespeneAssertTest {

    private EmulatedGame game;
    private SC2DeliveryHandler handler;

    @BeforeEach
    void setUp() {
        game = new EmulatedGame();
        game.reset();
        handler = new SC2DeliveryHandler(game);
    }

    @Test
    void assertVespene_minBound_passes() {
        game.setVespeneForHarness(100);
        var data = Map.<String, Object>of(
            "action", "assert",
            "expect", Map.<String, Object>of(
                "vespene", Map.of("min", 50)
            )
        );
        var result = handler.execute("check-gas", data, null);
        assertThat(result.success()).isTrue();
    }

    @Test
    void assertVespene_minBound_fails() {
        game.setVespeneForHarness(10);
        var data = Map.<String, Object>of(
            "action", "assert",
            "expect", Map.<String, Object>of(
                "vespene", Map.of("min", 50)
            )
        );
        assertThatThrownBy(() -> handler.execute("check-gas", data, null))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("vespene expected >=50");
    }

    @Test
    void assertVespene_maxBound_passes() {
        game.setVespeneForHarness(30);
        var data = Map.<String, Object>of(
            "action", "assert",
            "expect", Map.<String, Object>of(
                "vespene", Map.of("max", 100)
            )
        );
        var result = handler.execute("check-gas", data, null);
        assertThat(result.success()).isTrue();
    }

    @Test
    void assertVespene_maxBound_fails() {
        game.setVespeneForHarness(200);
        var data = Map.<String, Object>of(
            "action", "assert",
            "expect", Map.<String, Object>of(
                "vespene", Map.of("max", 100)
            )
        );
        assertThatThrownBy(() -> handler.execute("check-gas", data, null))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("vespene expected <=100");
    }
}

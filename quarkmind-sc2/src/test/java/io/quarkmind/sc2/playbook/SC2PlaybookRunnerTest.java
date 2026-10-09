package io.quarkmind.sc2.playbook;

import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.emulated.EmulatedGame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SC2PlaybookRunnerTest {

    private EmulatedGame game;
    private SC2PlaybookRunner runner;

    @BeforeEach
    void setUp() {
        game = new EmulatedGame();
        var handler = new SC2DeliveryHandler(game);
        runner = new SC2PlaybookRunner(handler, game);
    }

    @Test
    void executePlaybook_trainsProbeAndAsserts() {
        runner.execute("playbooks/test-train-probes.yaml");
        long probes = game.snapshot().myUnits().stream()
            .filter(u -> u.type() == UnitType.PROBE).count();
        assertThat(probes).isGreaterThanOrEqualTo(13);
    }

    @Test
    void parseTimeTicks_minutes() {
        int ticks = SC2PlaybookRunner.parseTimeTicks("3m");
        assertThat(ticks).isGreaterThan(0);
    }

    @Test
    void parseTimeTicks_seconds() {
        int ticks = SC2PlaybookRunner.parseTimeTicks("30s");
        assertThat(ticks).isGreaterThan(0);
        assertThat(ticks).isLessThan(SC2PlaybookRunner.parseTimeTicks("1m"));
    }
}

package io.quarkmind.sc2.playbook;

import io.quarkmind.sc2.emulated.EmulatedGame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SC2PlaybookCalibrationTest {

    private SC2PlaybookRunner runner;

    @BeforeEach
    void setUp() {
        var game = new EmulatedGame();
        var handler = new SC2DeliveryHandler(game);
        runner = new SC2PlaybookRunner(handler, game);
    }

    @Test
    void economyOnlyProtoss() {
        runner.execute("playbooks/economy-only-protoss.yaml");
    }

    @Test
    void economyOnlyTerran() {
        runner.execute("playbooks/economy-only-terran.yaml");
    }

    @Test
    void economyOnlyZerg() {
        runner.execute("playbooks/economy-only-zerg.yaml");
    }
}

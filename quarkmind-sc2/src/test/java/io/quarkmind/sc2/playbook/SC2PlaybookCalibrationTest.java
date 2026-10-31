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

    @Test
    void chronoBoostEconomy() {
        runner.execute("playbooks/chrono-boost-economy.yaml");
    }

    @Test
    void muleIncome() {
        runner.execute("playbooks/mule-income.yaml");
    }

    @Test
    void injectLarva() {
        runner.execute("playbooks/inject-larva.yaml");
    }

    @Test
    void gasSaturation() {
        runner.execute("playbooks/gas-saturation.yaml");
    }

    @Test
    void upgradeResearch() {
        runner.execute("playbooks/upgrade-research.yaml");
    }

    @Test
    void twoBaseProduction() {
        runner.execute("playbooks/two-base-production.yaml");
    }


}

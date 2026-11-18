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
    void muleIsolation() {
        var game    = new EmulatedGame();
        var handler = new SC2DeliveryHandler(game);
        var r       = new SC2PlaybookRunner(handler, game);
        r.execute("playbooks/cal-mule-isolation.yaml");
        var    snapshot    = game.snapshot();
        int    sc2Baseline = 3565;
        double delta       = Math.abs(snapshot.minerals() - sc2Baseline) / (double) sc2Baseline;
        System.out.printf("[MULE-ISOLATION] minerals=%d, SC2 baseline=%d, delta=%.1f%%%n",
                          snapshot.minerals(), sc2Baseline, delta * 100.0);
        org.assertj.core.api.Assertions.assertThat(delta)
                                       .as("MULE isolation delta vs SC2 baseline")
                                       .isLessThanOrEqualTo(0.02);
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
    void deferredResearchCompletesWithoutLoop() {
        runner.execute("playbooks/test-deferred-research.yaml");
    }

    @Test
    void expansionWithContinuousTraining() {
        runner.execute("playbooks/test-expansion-reservation.yaml");
    }

    @Test
    void expansionWithoutChronoAndGateway() {
        runner.execute("playbooks/test-expansion-no-chrono.yaml");
    }


    @Test
    void twoBaseProduction() {
        runner.execute("playbooks/two-base-production.yaml");
    }


    @Test
    void mineralBaseline8w() {
        runner.execute("playbooks/cal-mineral-baseline-8w.yaml");
    }
}

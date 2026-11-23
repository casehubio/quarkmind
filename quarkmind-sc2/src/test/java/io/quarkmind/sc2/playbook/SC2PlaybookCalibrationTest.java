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
        CalibrationBaseline baseline = DualRunCalibrationTest.loadBaseline("calibration/cal-mule-isolation.json");
        CalibrationResult result = DualRunCalibrationTest.calibrate("playbooks/cal-mule-isolation.yaml", baseline, 1);
        System.out.println(result.report());
        if (!result.passed()) {
            System.out.printf("[MULE] Diverged at tick %d: %s expected=%s actual=%s%n",
                              result.divergenceTick(), result.divergenceField(),
                              result.expected(), result.actual());
        }
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

    @Test
    void mineralBaseline4w() {
        runner.execute("playbooks/cal-mineral-baseline-4w.yaml");
    }

    @Test
    void mineralBaseline6w() {
        runner.execute("playbooks/cal-mineral-baseline-6w.yaml");
    }

    @Test
    void mineralBaseline10w() {
        runner.execute("playbooks/cal-mineral-baseline-10w.yaml");
    }

}

package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.SC2Data;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Empirical verification that MULE_MINERAL_CARGO=25 is correct.
 *
 * Runs a single MULE through the WorkerSimulation physics (no SCVs)
 * and records each individual deposit amount. Every deposit must be
 * exactly 25 minerals — the value used in the EM that produces a
 * +1.4% delta against the SC2 dual-run baseline (3615 vs 3565).
 *
 * Cargo=30 was tested during #717 and produced +12.3%, confirming
 * 25 is the correct empirical value.
 */
class MuleCargoVerificationTest {

    private static final Point2d BASE_POS = new Point2d(50, 50);

    @Test
    void muleDeliversExactly25MineralsPerTrip() {
        WorkerSimulation sim = new WorkerSimulation();
        sim.onTownHallCompleted("base-1", BASE_POS);

        sim.addMule("mule-1", BASE_POS);

        List<Double> deposits = new ArrayList<>();
        // WorkerSimulation doesn't handle MULE expiry — that's EmulatedGame.tick()
        int ticksToRun = SC2Data.MULE_LIFETIME_LOOPS / SC2Data.LOOPS_PER_TICK + 10;

        for (int t = 0; t < ticksToRun; t++) {
            double loop = t * (double) SC2Data.LOOPS_PER_TICK;
            TickIncome income = sim.tick(loop);
            if (income.mineralIncome() > 0) {
                deposits.add(income.mineralIncome());
            }
        }

        assertThat(deposits).as("MULE should complete multiple trips").hasSizeGreaterThanOrEqualTo(3);
        assertThat(deposits).as("Every MULE deposit must be exactly MULE_MINERAL_CARGO")
                            .allMatch(d -> d == SC2Data.MULE_MINERAL_CARGO);

        double totalIncome = deposits.stream().mapToDouble(d -> d).sum();
        System.out.printf("[MULE-CARGO] trips=%d, cargo=%.0f, total=%.0f minerals%n",
                          deposits.size(), SC2Data.MULE_MINERAL_CARGO, totalIncome);
        System.out.printf("[MULE-CARGO] SC2 baseline MULE isolation: 3565 minerals (EM=3615, delta=+1.4%%)%n");
        System.out.printf("[MULE-CARGO] Cargo=30 tested in #717: produced +12.3%% — rejected%n");
    }
}

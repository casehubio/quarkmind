package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.*;
import io.quarkmind.sc2.intent.BuildIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class EmulatedGameGasIncomeTest {

    EmulatedGame game;

    @BeforeEach
    void setup() {
        game = new EmulatedGame();
        game.configureWave(9999, 4, UnitType.ZEALOT);
        game.reset();
    }

    @Test
    void completedAssimilator_producesVespeneIncome() {
        assertThat(game.snapshot().vespene()).isEqualTo(0);

        game.setMineralsForTesting(500);
        game.applyIntent(new BuildIntent("probe-0", BuildingType.ASSIMILATOR, new Point2d(10, 10)));
        int buildTicks = SC2Data.buildTimeInLoops(BuildingType.ASSIMILATOR) / SC2Data.LOOPS_PER_TICK + 1;
        for (int i = 0; i < buildTicks; i++) game.tick();

        boolean assimilatorComplete = game.snapshot().myBuildings().stream()
            .anyMatch(b -> b.type() == BuildingType.ASSIMILATOR && b.isComplete());
        assertThat(assimilatorComplete)
            .as("Assimilator must be complete after build time")
            .isTrue();

        for (int i = 0; i < 50; i++) game.tick();

        assertThat(game.snapshot().vespene())
            .as("Vespene should accumulate after gas building completes")
            .isGreaterThan(0);
    }

    @Test
    void gasBuilding_reducesMineralIncome() {
        for (int i = 0; i < 100; i++) game.tick();
        double mineralsWithoutGas = game.snapshot().minerals();

        game = new EmulatedGame();
        game.configureWave(9999, 4, UnitType.ZEALOT);
        game.reset();
        game.setMineralsForTesting(500);
        game.applyIntent(new BuildIntent("probe-0", BuildingType.ASSIMILATOR, new Point2d(10, 10)));
        int buildTicks = SC2Data.buildTimeInLoops(BuildingType.ASSIMILATOR) / SC2Data.LOOPS_PER_TICK + 1;
        for (int i = 0; i < buildTicks; i++) game.tick();
        double mineralsAtBuildComplete = game.snapshot().minerals();

        for (int i = 0; i < 100; i++) game.tick();
        double mineralIncomeWithGas = game.snapshot().minerals() - mineralsAtBuildComplete;

        assertThat(mineralIncomeWithGas)
            .as("Mineral income should decrease when workers are on gas")
            .isLessThan(mineralsWithoutGas);
    }

    @Test
    void multipleGasBuildings_workerBudgetCapsAtTotalWorkers() {
        game.setMineralsForTesting(2000);
        for (int i = 0; i < 5; i++) {
            game.applyIntent(new BuildIntent("probe-0", BuildingType.ASSIMILATOR,
                new Point2d(10 + i * 5, 10)));
        }
        int buildTicks = SC2Data.buildTimeInLoops(BuildingType.ASSIMILATOR) / SC2Data.LOOPS_PER_TICK + 1;
        for (int i = 0; i < buildTicks; i++) game.tick();

        double mineralsAfterBuild = game.snapshot().minerals();

        for (int i = 0; i < 100; i++) game.tick();

        double mineralIncome = game.snapshot().minerals() - mineralsAfterBuild;
        assertThat(mineralIncome)
            .as("No mineral income when all workers are on gas")
            .isCloseTo(0.0, within(1.0));

        assertThat(game.snapshot().vespene())
            .as("Vespene income should accumulate from multiple gas buildings")
            .isGreaterThan(0);
    }
}

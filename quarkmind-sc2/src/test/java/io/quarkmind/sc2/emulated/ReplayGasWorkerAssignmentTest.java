package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ReplayGasWorkerAssignmentTest {

    EmulatedGame game;

    @BeforeEach
    void setup() {
        game = new EmulatedGame();
        game.configureWave(9999, 4, UnitType.ZEALOT);
        game.reset();
    }

    @Test
    void injectReplayBuilding_completeGas_assignsWorkers_producesVespene() {
        Building assimilator = new Building("gas-1", BuildingType.ASSIMILATOR,
            new Point2d(10, 10),
            SC2Data.maxBuildingHealth(BuildingType.ASSIMILATOR),
            SC2Data.maxBuildingHealth(BuildingType.ASSIMILATOR),
            true);

        game.injectReplayBuilding(assimilator);

        for (int i = 0; i < 50; i++) game.tick();

        assertThat(game.snapshot().vespene())
            .as("Injected complete gas building must produce vespene income")
            .isGreaterThan(0);
    }

    @Test
    void syncReplayBuilding_transitionsToComplete_assignsWorkers_producesVespene() {
        Building incomplete = new Building("gas-2", BuildingType.ASSIMILATOR,
            new Point2d(10, 10),
            SC2Data.maxBuildingHealth(BuildingType.ASSIMILATOR),
            SC2Data.maxBuildingHealth(BuildingType.ASSIMILATOR),
            false);
        game.injectReplayBuilding(incomplete);

        for (int i = 0; i < 10; i++) game.tick();
        assertThat(game.snapshot().vespene())
            .as("Incomplete gas building should not produce income")
            .isEqualTo(0);

        Building complete = new Building("gas-2", BuildingType.ASSIMILATOR,
            new Point2d(10, 10),
            SC2Data.maxBuildingHealth(BuildingType.ASSIMILATOR),
            SC2Data.maxBuildingHealth(BuildingType.ASSIMILATOR),
            true);
        game.syncReplayBuilding(complete);

        for (int i = 0; i < 50; i++) game.tick();

        assertThat(game.snapshot().vespene())
            .as("Synced-to-complete gas building must produce vespene income")
            .isGreaterThan(0);
    }

    @Test
    void injectReplayBuilding_incompleteGas_noVespene() {
        Building incomplete = new Building("gas-3", BuildingType.ASSIMILATOR,
            new Point2d(10, 10),
            SC2Data.maxBuildingHealth(BuildingType.ASSIMILATOR),
            SC2Data.maxBuildingHealth(BuildingType.ASSIMILATOR),
            false);
        game.injectReplayBuilding(incomplete);

        for (int i = 0; i < 50; i++) game.tick();

        assertThat(game.snapshot().vespene())
            .as("Incomplete gas building must not produce vespene")
            .isEqualTo(0);
    }
}

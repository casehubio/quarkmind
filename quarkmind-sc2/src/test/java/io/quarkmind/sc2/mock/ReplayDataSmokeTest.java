package io.quarkmind.sc2.mock;

import io.quarkmind.domain.GameState;
import io.quarkmind.domain.UnitType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ReplayDataSmokeTest {

    private static final Path REPLAY = Path.of("replays/aiarena_protoss/Nothing_4720936.SC2Replay");
    private ReplaySimulatedGame game;

    @BeforeEach
    void setUp() {
        game = new ReplaySimulatedGame(REPLAY, 1);
    }

    @Test
    void enemyUnitsAppearWithinFirst100Ticks() {
        for (int i = 0; i < 100; i++) game.tick();
        GameState state = game.snapshot();
        assertThat(state.enemyUnits())
                .as("enemy units should be visible within 100 ticks")
                .isNotEmpty();
    }

    @Test
    void multipleEnemyUnitTypesVisibleBy200Ticks() {
        for (int i = 0; i < 200; i++) game.tick();
        GameState state = game.snapshot();
        Set<UnitType> types = state.enemyUnits().stream()
                .map(u -> u.type())
                .collect(Collectors.toSet());
        assertThat(types)
                .as("multiple enemy unit types should be visible — not just one kind")
                .hasSizeGreaterThan(1);
    }

    @Test
    void economyStatsNonZeroAfter5Ticks() {
        for (int i = 0; i < 5; i++) game.tick();
        GameState state = game.snapshot();
        assertThat(state.minerals())
                .as("minerals should be non-zero after 5 ticks")
                .isGreaterThan(0);
    }

    @Test
    void friendlyUnitsAndBuildingsExistAtStart() {
        game.tick();
        GameState state = game.snapshot();
        assertThat(state.myUnits())
                .as("friendly units should exist at game start")
                .isNotEmpty();
        assertThat(state.myBuildings())
                .as("friendly buildings should exist at game start")
                .isNotEmpty();
    }

    @Test
    void enemyBuildingsVisibleBy300Ticks() {
        for (int i = 0; i < 300; i++) game.tick();
        GameState state = game.snapshot();
        assertThat(state.enemyBuildings())
                .as("enemy buildings should be visible by tick 300")
                .isNotEmpty();
    }
}

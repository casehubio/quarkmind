package io.quarkmind.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class TimelineObservationTest {

    @Test
    void fromGameState_extractsCorrectValues() {
        var gs = gameState(350, 44, 16, 5, 15120);

        var obs = TimelineObservation.from(gs);

        assertEquals(15120.0 / SC2Data.GAME_LOOPS_PER_SECOND / 60.0, obs.minute(), 0.001);
        assertEquals(16, obs.ourWorkers());
        assertEquals(350, obs.ourMinerals());
        assertEquals(44 - 16, obs.ourArmySupply());
    }

    @Test
    void fromGameState_zeroWorkers() {
        var gs = gameState(50, 0, 0, 0, 0);
        var obs = TimelineObservation.from(gs);

        assertEquals(0, obs.ourWorkers());
        assertEquals(50, obs.ourMinerals());
        assertEquals(0, obs.ourArmySupply());
    }

    @Test
    void fromGameState_noArmy_onlyWorkers() {
        var gs = gameState(200, 12, 12, 0, 672);
        var obs = TimelineObservation.from(gs);

        assertEquals(12, obs.ourWorkers());
        assertEquals(200, obs.ourMinerals());
        assertEquals(0, obs.ourArmySupply());
    }

    @Test
    void fromGameState_highArmySupply() {
        var gs = gameState(100, 100, 20, 10, 5000);
        var obs = TimelineObservation.from(gs);

        assertEquals(20, obs.ourWorkers());
        assertEquals(100, obs.ourMinerals());
        assertEquals(80, obs.ourArmySupply());
    }

    private static GameState gameState(int minerals, int supplyUsed,
                                       int workerCount, int armyUnitCount, long gameFrame) {
        List<Unit> units = new ArrayList<>();
        IntStream.range(0, workerCount)
                .mapToObj(i -> new Unit("w-" + i, UnitType.PROBE, new Point2d(0, 0), 45, 45, 20, 20, 0, 0))
                .forEach(units::add);
        IntStream.range(0, armyUnitCount)
                .mapToObj(i -> new Unit("a-" + i, UnitType.STALKER, new Point2d(0, 0), 160, 160, 80, 80, 0, 0))
                .forEach(units::add);
        return new GameState(
                minerals, 0, 200, supplyUsed,
                units, List.of(),
                List.of(), List.of(), List.of(),
                List.of(), List.of(),
                gameFrame, null,
                PlayerEconomyStats.EMPTY, PlayerEconomyStats.EMPTY,
                Set.of(), Set.of()
        );
    }
}

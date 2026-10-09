package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.Race;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.intent.TrainIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZergParallelTrainingTest {

    private EmulatedGame game;

    @BeforeEach
    void setUp() {
        game = new EmulatedGame();
        game.setPlayerRaceModel(RaceModelFactory.forRace(Race.ZERG));
        game.reset();
    }

    @Test
    void zerg_canTrainMultipleDronesSimultaneously() {
        for (int i = 0; i < 20; i++) {game.tick();}

        game.applyIntent(new TrainIntent("hatchery-0", UnitType.DRONE));
        game.applyIntent(new TrainIntent("hatchery-0", UnitType.DRONE));

        long eggs = game.snapshot().myUnits().stream()
                        .filter(u -> u.type() == UnitType.EGG).count();
        assertEquals(2, eggs, "2 Larva should immediately become 2 Eggs (parallel training)");
    }

    @Test
    void zerg_parallelDronesCompleteAtSameTime() {
        for (int i = 0; i < 20; i++) {game.tick();}

        long dronesBefore = game.snapshot().myUnits().stream()
                                .filter(u -> u.type() == UnitType.DRONE).count();

        game.applyIntent(new TrainIntent("hatchery-0", UnitType.DRONE));
        game.applyIntent(new TrainIntent("hatchery-0", UnitType.DRONE));

        int trainTicks = SC2Data.trainTimeInLoops(UnitType.DRONE) / SC2Data.LOOPS_PER_TICK + 2;
        for (int i = 0; i < trainTicks; i++) {game.tick();}

        long dronesAfter = game.snapshot().myUnits().stream()
                               .filter(u -> u.type() == UnitType.DRONE).count();
        assertTrue(dronesAfter >= dronesBefore + 2,
                   "Both parallel Drones should complete — got " + dronesAfter + " (was " + dronesBefore + ")");
    }

    @Test
    void protoss_stillQueuesOneAtATime() {
        var protossGame = new EmulatedGame();
        protossGame.setPlayerRaceModel(RaceModelFactory.forRace(Race.PROTOSS));
        protossGame.reset();

        protossGame.applyIntent(new TrainIntent("nexus-0", UnitType.PROBE));
        protossGame.applyIntent(new TrainIntent("nexus-0", UnitType.PROBE));

        int halfTrain = SC2Data.trainTimeInLoops(UnitType.PROBE) / SC2Data.LOOPS_PER_TICK / 2;
        for (int i = 0; i < halfTrain; i++) protossGame.tick();

        long probes = protossGame.snapshot().myUnits().stream()
            .filter(u -> u.type() == UnitType.PROBE).count();
        assertEquals(12, probes, "Protoss should NOT have parallel training — still queued");
    }
}

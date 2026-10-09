package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.*;
import io.quarkmind.sc2.intent.AbilityIntent;
import io.quarkmind.sc2.intent.TrainIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmulatedGameChronoTest {

    private EmulatedGame game;

    @BeforeEach
    void setUp() {
        game = new EmulatedGame();
        game.setPlayerRaceModel(RaceModelFactory.forRace(Race.PROTOSS));
        game.reset();
    }

    @Test
    void chronoBoost_thenTrain_completesInHalfTime() {
        game.applyIntent(new AbilityIntent("nexus-0", "CHRONO_BOOST", "nexus-0"));
        game.applyIntent(new TrainIntent("nexus-0", UnitType.PROBE));

        int normalTicks = SC2Data.trainTimeInLoops(UnitType.PROBE) / SC2Data.LOOPS_PER_TICK;
        int halfTicks = normalTicks / 2;

        long probesBefore = game.snapshot().myUnits().stream()
            .filter(u -> u.type() == UnitType.PROBE).count();
        for (int i = 0; i < halfTicks + 2; i++) game.tick();
        long probesAfter = game.snapshot().myUnits().stream()
            .filter(u -> u.type() == UnitType.PROBE).count();

        assertTrue(probesAfter > probesBefore,
            "Chrono-boosted Probe should complete in ~" + halfTicks + " ticks, not " + normalTicks);
    }

    @Test
    void midTrainingChrono_halvesRemainingTime() {
        game.applyIntent(new TrainIntent("nexus-0", UnitType.PROBE));
        int normalTicks = SC2Data.trainTimeInLoops(UnitType.PROBE) / SC2Data.LOOPS_PER_TICK;
        for (int i = 0; i < 3; i++) game.tick();

        game.applyIntent(new AbilityIntent("nexus-0", "CHRONO_BOOST", "nexus-0"));

        long probesBefore = game.snapshot().myUnits().stream()
            .filter(u -> u.type() == UnitType.PROBE).count();
        int remainingHalved = (normalTicks - 3) / 2;
        for (int i = 0; i < remainingHalved + 2; i++) game.tick();
        long probesAfter = game.snapshot().myUnits().stream()
            .filter(u -> u.type() == UnitType.PROBE).count();

        assertTrue(probesAfter > probesBefore,
            "Mid-training Chrono should halve remaining time");
    }

    @Test
    void noChrono_normalTrainingTime() {
        game.applyIntent(new TrainIntent("nexus-0", UnitType.PROBE));
        int normalTicks = SC2Data.trainTimeInLoops(UnitType.PROBE) / SC2Data.LOOPS_PER_TICK;

        long probesBefore = game.snapshot().myUnits().stream()
            .filter(u -> u.type() == UnitType.PROBE).count();
        for (int i = 0; i < normalTicks / 2; i++) game.tick();
        long probesMid = game.snapshot().myUnits().stream()
            .filter(u -> u.type() == UnitType.PROBE).count();

        assertEquals(probesBefore, probesMid,
            "Without Chrono, Probe should NOT complete in half time");
    }
}

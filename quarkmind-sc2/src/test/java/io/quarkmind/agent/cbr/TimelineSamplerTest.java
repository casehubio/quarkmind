package io.quarkmind.agent.cbr;

import io.quarkmind.domain.*;
import io.quarkmind.sc2.GameStarted;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class TimelineSamplerTest {

    private TimelineSampler sampler;

    @BeforeEach
    void setUp() {
        sampler = new TimelineSampler();
    }

    @Test
    void samplesAtCorrectInterval() {
        sampler.tick(gameStateAtFrame(0));
        assertEquals(1, sampler.getTimeline().size());

        sampler.tick(gameStateAtFrame(671));
        assertEquals(1, sampler.getTimeline().size());

        sampler.tick(gameStateAtFrame(672));
        assertEquals(2, sampler.getTimeline().size());
    }

    @Test
    void firstTickAlwaysSamples() {
        sampler.tick(gameStateAtFrame(100));
        assertEquals(1, sampler.getTimeline().size());
    }

    @Test
    void getTimelineReturnsImmutableCopy() {
        sampler.tick(gameStateAtFrame(0));
        List<TimelineObservation> timeline = sampler.getTimeline();
        assertThrows(UnsupportedOperationException.class, timeline::clear);
    }

    @Test
    void clearsOnGameStarted() {
        sampler.tick(gameStateAtFrame(0));
        sampler.tick(gameStateAtFrame(672));
        assertEquals(2, sampler.getTimeline().size());

        sampler.onGameStarted(new GameStarted("Zerg", "Computer", "Medium", "ai-1"));
        assertEquals(0, sampler.getTimeline().size());

        sampler.tick(gameStateAtFrame(1000));
        assertEquals(1, sampler.getTimeline().size());
    }

    @Test
    void multipleWindowsAccumulate() {
        for (int i = 0; i < 5; i++) {
            sampler.tick(gameStateAtFrame(i * 672L));
        }
        assertEquals(5, sampler.getTimeline().size());
    }

    @Test
    void sampledValuesMatchGameState() {
        sampler.tick(gameStateAtFrame(672, 300, 40, 16));
        var obs = sampler.getTimeline().get(0);
        assertEquals(672.0 / SC2Data.GAME_LOOPS_PER_SECOND / 60.0, obs.minute(), 0.001);
        assertEquals(16, obs.ourWorkers());
        assertEquals(300, obs.ourMinerals());
        assertEquals(40 - 16, obs.ourArmySupply());
    }

    private static GameState gameStateAtFrame(long frame) {
        return gameStateAtFrame(frame, 200, 20, 12);
    }

    private static GameState gameStateAtFrame(long frame, int minerals, int supplyUsed, int workerCount) {
        List<Unit> units = new ArrayList<>();
        IntStream.range(0, workerCount)
                .mapToObj(i -> new Unit("w-" + i, UnitType.PROBE, new Point2d(0, 0), 45, 45, 20, 20, 0, 0))
                .forEach(units::add);
        return new GameState(
                minerals, 0, 200, supplyUsed,
                units, List.of(),
                List.of(), List.of(), List.of(),
                List.of(), List.of(),
                frame, null,
                PlayerEconomyStats.EMPTY, PlayerEconomyStats.EMPTY,
                Set.of(), Set.of()
        );
    }
}

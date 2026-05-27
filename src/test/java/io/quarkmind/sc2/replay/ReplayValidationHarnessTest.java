package io.quarkmind.sc2.replay;

import io.quarkmind.domain.*;
import io.quarkmind.sc2.intent.TimedIntent;
import io.quarkmind.sc2.mock.ReplaySimulatedGame;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReplayValidationHarnessTest {

    @Test
    void countProbesPerBase_zeroCompleteNexuses_returnsEmptyArray() {
        Building incompleteNexus = new Building("nexus-0", BuildingType.NEXUS,
            new Point2d(8, 8), 750, 1500, false);

        GameState state = gameState(
            List.of(probe("p-0", 9, 9), probe("p-1", 10, 9)),
            List.of(incompleteNexus));

        assertThat(ReplayValidationHarness.countProbesPerBase(state)).isEmpty();
    }

    @Test
    void countProbesPerBase_singleNexus_allProbesAssigned() {
        Building nexus = new Building("nexus-0", BuildingType.NEXUS,
            new Point2d(8, 8), 1500, 1500, true);

        GameState state = gameState(
            List.of(probe("p-0", 9, 9), probe("p-1", 10, 9), probe("p-2", 7, 8)),
            List.of(nexus));

        assertThat(ReplayValidationHarness.countProbesPerBase(state)).containsExactly(3);
    }

    @Test
    void countProbesPerBase_twoNexuses_probesAssignedByProximity() {
        Building nexus0 = new Building("nexus-0", BuildingType.NEXUS,
            new Point2d(8, 8), 1500, 1500, true);
        Building nexus1 = new Building("nexus-1", BuildingType.NEXUS,
            new Point2d(30, 30), 1500, 1500, true);

        GameState state = gameState(
            List.of(probe("p-0", 9, 9), probe("p-1", 10, 8), probe("p-2", 29, 30)),
            List.of(nexus0, nexus1));

        assertThat(ReplayValidationHarness.countProbesPerBase(state)).containsExactly(2, 1);
    }

    @Test
    void countProbesPerBase_nonProbeUnitsIgnored() {
        Building nexus = new Building("nexus-0", BuildingType.NEXUS,
            new Point2d(8, 8), 1500, 1500, true);
        Unit zealot = new Unit("z-0", UnitType.ZEALOT, new Point2d(10, 10),
            100, 100, 50, 50, 0, 0);

        GameState state = gameState(
            List.of(probe("p-0", 9, 9), zealot),
            List.of(nexus));

        assertThat(ReplayValidationHarness.countProbesPerBase(state)).containsExactly(1);
    }

    @Test
    void generalFormAcceptsSimulatedGameArgument() {
        Path replayPath = Path.of("replays/aiarena_protoss/Nothing_4720936.SC2Replay");
        var game    = new ReplaySimulatedGame(replayPath, 1);
        var intents = ReplayCommandExtractor.extract(replayPath, 1).intents();
        DivergenceReport report = ReplayValidationHarness.run(game, intents, 183);
        assertThat(report).isNotNull();
        assertThat(report.ticks()).hasSize(183);
    }

    private static Unit probe(String tag, float x, float y) {
        return new Unit(tag, UnitType.PROBE, new Point2d(x, y),
            SC2Data.maxHealth(UnitType.PROBE), SC2Data.maxHealth(UnitType.PROBE),
            SC2Data.maxShields(UnitType.PROBE), SC2Data.maxShields(UnitType.PROBE), 0, 0);
    }

    private static GameState gameState(List<Unit> units, List<Building> buildings) {
        return new GameState(0, 0, 0, 0, units, buildings,
            List.of(), List.of(), List.of(), List.of(), List.of(), 0);
    }
}

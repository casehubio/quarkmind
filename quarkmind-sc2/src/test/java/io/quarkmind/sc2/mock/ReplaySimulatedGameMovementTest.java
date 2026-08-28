package io.quarkmind.sc2.mock;

import io.quarkmind.sc2.replay.ReplayCommandExtractor;
import io.quarkmind.sc2.replay.UnitOrder;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests movement order loading and seek functionality for ReplaySimulatedGame.
 * Movement orders are now extracted via ReplayCommandExtractor (not GameEventStream.parse()).
 */
class ReplaySimulatedGameMovementTest {

    private static final Path REPLAY = Path.of("replays/aiarena_protoss/Nothing_4720936.SC2Replay");

    @Test
    void loadOrdersEnablesMovementTracking() {
        ReplaySimulatedGame game = new ReplaySimulatedGame(REPLAY, 1);
        List<UnitOrder> orders = ReplayCommandExtractor.extract(REPLAY, 1).movementOrders();
        assertThat(orders).isNotEmpty();
        game.loadOrders(orders);
        // No assertion beyond "doesn't throw" — loadOrders sets up the tracker
    }

    @Test
    void totalLoopsMatchesReplayLength() {
        ReplaySimulatedGame game = new ReplaySimulatedGame(REPLAY, 1);
        // Nothing_4720936 is 8m21s ≈ 501s × 22.4 loops/sec ≈ 11223 loops
        assertThat(game.totalLoops()).isGreaterThan(10000L);
    }

    @Test
    void seekToAdvancesToTargetLoop() {
        ReplaySimulatedGame game = new ReplaySimulatedGame(REPLAY, 1);
        long targetLoop = 2200L; // 100 ticks in
        game.seekTo(targetLoop);
        assertThat(game.currentLoop()).isGreaterThanOrEqualTo(targetLoop);
    }

    @Test
    void allUnitPositionsWithinMapBoundsAfter200Ticks() {
        ReplaySimulatedGame game = new ReplaySimulatedGame(REPLAY, 1);
        List<UnitOrder> orders = ReplayCommandExtractor.extract(REPLAY, 1).movementOrders();
        game.loadOrders(orders);
        for (int i = 0; i < 200; i++) game.tick();
        game.snapshot().myUnits().forEach(u -> {
            assertThat(u.position().x()).as("x in bounds for %s", u.tag()).isBetween(0f, 256f);
            assertThat(u.position().y()).as("y in bounds for %s", u.tag()).isBetween(0f, 256f);
        });
    }

    @Test
    void probesMoveTowardMineralPatchesAfterMiningOrders() {
        ReplaySimulatedGame game   = new ReplaySimulatedGame(REPLAY, 1);
        List<UnitOrder>     orders = ReplayCommandExtractor.extract(REPLAY, 1).movementOrders();
        game.loadOrders(orders);

        // Advance 200 ticks (~100 seconds at Faster speed)
        for (int i = 0; i < 200; i++) {game.tick();}

        var state = game.snapshot();
        var probes = state.myUnits().stream()
                          .filter(u -> u.type() == io.quarkmind.domain.UnitType.PROBE)
                          .toList();
        var nexus = state.myBuildings().stream()
                         .filter(b -> b.type() == io.quarkmind.domain.BuildingType.NEXUS)
                         .toList();
        var minerals = state.mineralPatches();

        assertThat(probes).as("probes exist").isNotEmpty();
        assertThat(nexus).as("nexus exists").isNotEmpty();

        // Count probes within 15 tiles of any Nexus or mineral patch
        long nearBase = probes.stream().filter(p -> {
            float px = p.position().x(), py = p.position().y();
            boolean nearNexus = nexus.stream().anyMatch(n ->
                                                                Math.abs(px - n.position().x()) < 15 && Math.abs(py - n.position().y()) < 15);
            boolean nearMineral = minerals.stream().anyMatch(m ->
                                                                     Math.abs(px - m.position().x()) < 8 && Math.abs(py - m.position().y()) < 8);
            return nearNexus || nearMineral;
        }).count();

        // At least 50% of probes should be near a base or mineral (mining)
        assertThat(nearBase)
                .as("probes near base/minerals: %d/%d", nearBase, probes.size())
                .isGreaterThanOrEqualTo((long) (probes.size() * 0.5));
    }
}


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
}

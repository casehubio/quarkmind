package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResourceObstacleTest {

    @Test
    void geyserResources_registeredAsObstacles() {
        EmulatedGame game = new EmulatedGame();
        TerrainGrid grid = game.getTerrainGrid();

        game.setGeyserResources(List.of(
            new Resource("geyser-1", new Point2d(20, 10), 2250, false),
            new Resource("geyser-2", new Point2d(25, 10), 2250, false)
        ));

        // Geysers are 3x3 footprint — half-extents 1.5
        // At (20,10): blocks cells (int)(20-1.5)=18 to (int)(20+1.5)=21
        assertFalse(grid.isWalkable(20, 10), "Geyser center should be blocked");
        assertFalse(grid.isWalkable(18, 10), "Geyser edge should be blocked");
        assertTrue(grid.isWalkable(17, 10), "Cell outside geyser should be walkable");

        assertFalse(grid.isWalkable(25, 10), "Second geyser center should be blocked");
    }

    @Test
    void armyUnit_routesAroundGeyser() {
        EmulatedGame game = new EmulatedGame();
        TerrainGrid grid = game.getTerrainGrid();
        game.setMovementStrategy(new PathfindingMovement(grid));

        game.setGeyserResources(List.of(
            new Resource("geyser-1", new Point2d(10, 8), 2250, false)
        ));

        String tag = game.spawnFriendlyForTesting(UnitType.STALKER, new Point2d(5, 8));
        game.friendlyPhysics.unitTargets.put(tag, new Point2d(15, 8));

        for (int i = 0; i < 30; i++) {
            game.tick();
        }

        Unit unit = game.snapshot().myUnits().stream()
            .filter(u -> u.tag().equals(tag))
            .findFirst().orElseThrow();
        assertTrue(unit.position().x() > 12,
            "Unit should have navigated past the geyser, but is at x=" + unit.position().x());
    }

    @Test
    void mineralPatches_alreadyRegisteredAsObstacles() {
        // Mineral patches are registered by WorkerSimulation when town halls are created.
        // This test verifies the shared TerrainGrid has mineral obstacles after reset.
        EmulatedGame game = new EmulatedGame();
        game.reset();
        TerrainGrid grid = game.getTerrainGrid();

        // After reset with the default Protoss race model, mineral patches are registered
        // around the town hall. Check that at least some cells near the mineral line are blocked.
        GameState state = game.snapshot();
        if (!state.mineralPatches().isEmpty()) {
            Resource firstPatch = state.mineralPatches().get(0);
            int px = (int) firstPatch.position().x();
            int py = (int) firstPatch.position().y();
            assertFalse(grid.isWalkable(px, py),
                "Mineral patch cell should be blocked at (" + px + "," + py + ")");
        }
    }
}

package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BuildingObstacleTest {

    @Test
    void debugCreateBuilding_marksRectangularObstacle() {
        EmulatedGame game = new EmulatedGame();
        TerrainGrid grid = game.getTerrainGrid();

        // Gateway is 3x3 — half-extents 1.5 x 1.5
        // Place at (10, 10) — cells (9,9) through (11,11) should be blocked
        game.debugCreateBuilding(BuildingType.GATEWAY, new Point2d(10, 10));

        // 3x3 at (10,10): half-extents 1.5 → minX=(int)(8.5)=8, maxX=(int)(11.5)=11
        assertFalse(grid.isWalkable(10, 10), "Center cell should be blocked");
        assertFalse(grid.isWalkable(8, 8), "Min corner should be blocked");
        assertFalse(grid.isWalkable(11, 11), "Max corner should be blocked");
        assertTrue(grid.isWalkable(7, 10), "Cell outside footprint should be walkable");
        assertTrue(grid.isWalkable(12, 10), "Cell outside footprint should be walkable");
    }

    @Test
    void buildingDied_clearsObstacle() {
        EmulatedGame game = new EmulatedGame();
        TerrainGrid grid = game.getTerrainGrid();

        game.debugCreateBuilding(BuildingType.GATEWAY, new Point2d(10, 10));
        assertFalse(grid.isWalkable(10, 10), "Should be blocked after placement");

        String tag = game.snapshot().myBuildings().stream()
            .filter(b -> b.type() == BuildingType.GATEWAY)
            .findFirst().orElseThrow().tag();
        game.removeDestroyedBuilding(tag, BuildingType.GATEWAY);
        assertTrue(grid.isWalkable(10, 10), "Should be walkable after destruction");
    }

    @Test
    void reset_clearsAllBuildingObstacles() {
        EmulatedGame game = new EmulatedGame();
        TerrainGrid grid = game.getTerrainGrid();

        game.debugCreateBuilding(BuildingType.GATEWAY, new Point2d(10, 10));
        game.debugCreateBuilding(BuildingType.PYLON, new Point2d(15, 10));
        assertFalse(grid.isWalkable(10, 10));
        assertFalse(grid.isWalkable(15, 10));

        game.reset();
        assertTrue(grid.isWalkable(10, 10), "Should be walkable after reset");
        assertTrue(grid.isWalkable(15, 10), "Should be walkable after reset");
    }

    @Test
    void overlappingBuildings_referenceCountedCorrectly() {
        EmulatedGame game = new EmulatedGame();
        TerrainGrid grid = game.getTerrainGrid();

        // Two 2x2 Pylons at (10, 10) and (11, 10) — overlap at cell (10, 10) and (11, 10)
        game.debugCreateBuilding(BuildingType.PYLON, new Point2d(10, 10));
        game.debugCreateBuilding(BuildingType.PYLON, new Point2d(11, 10));

        assertFalse(grid.isWalkable(10, 10));
        assertFalse(grid.isWalkable(11, 10));

        // Remove first pylon — shared cells should still be blocked
        String tag1 = game.snapshot().myBuildings().stream()
            .filter(b -> b.type() == BuildingType.PYLON)
            .findFirst().orElseThrow().tag();
        game.removeDestroyedBuilding(tag1, BuildingType.PYLON);

        // Cell (10,10) may still be blocked by the second pylon if footprints overlap
        // Cell unique to first pylon should be clear
        assertTrue(grid.isWalkable(9, 9), "Cell only covered by first pylon should be clear");
    }

    @Test
    void enemyBuilding_marksObstacle() {
        EmulatedGame game = new EmulatedGame();
        TerrainGrid grid = game.getTerrainGrid();

        game.spawnEnemyBuildingForTesting(BuildingType.BARRACKS, new Point2d(10, 10));
        assertFalse(grid.isWalkable(10, 10), "Enemy building center should be blocked");
    }

    @Test
    void largeBuilding_blocksCorrectCells() {
        EmulatedGame game = new EmulatedGame();
        TerrainGrid grid = game.getTerrainGrid();

        // Nexus is 5x5 — half-extents 2.5 x 2.5
        game.debugCreateBuilding(BuildingType.NEXUS, new Point2d(10, 10));

        // 5x5 at (10,10): half-extents 2.5 → minX=(int)(7.5)=7, maxX=(int)(12.5)=12
        for (int x = 7; x <= 12; x++) {
            for (int y = 7; y <= 12; y++) {
                assertFalse(grid.isWalkable(x, y),
                    "Cell (%d,%d) inside 5x5 footprint should be blocked".formatted(x, y));
            }
        }
        assertTrue(grid.isWalkable(6, 10), "Cell outside footprint should be walkable");
        assertTrue(grid.isWalkable(13, 10), "Cell outside footprint should be walkable");
    }
}

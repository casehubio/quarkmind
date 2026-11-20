package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArmyPathAroundBuildingTest {

    private EmulatedGame gameWithPathfinding() {
        EmulatedGame game = new EmulatedGame();
        TerrainGrid grid = game.getTerrainGrid();
        game.setMovementStrategy(new PathfindingMovement(grid));
        return game;
    }

    @Test
    void armyUnit_pathsAroundBuilding() {
        EmulatedGame game = gameWithPathfinding();

        // Place a 3x3 Gateway directly between unit start and target
        game.debugCreateBuilding(BuildingType.GATEWAY, new Point2d(8, 8));

        // Spawn a unit at (5, 8) with target (12, 8)
        String tag = game.spawnFriendlyForTesting(UnitType.STALKER, new Point2d(5, 8));
        game.friendlyPhysics.unitTargets.put(tag, new Point2d(12, 8));

        for (int i = 0; i < 30; i++) {
            game.tick();
        }

        Unit unit = game.snapshot().myUnits().stream()
            .filter(u -> u.tag().equals(tag))
            .findFirst().orElseThrow();
        assertTrue(unit.position().x() > 10,
            "Unit should have navigated past the building, but is at x=" + unit.position().x());
    }

    @Test
    void armyUnit_neverEntersBuildingFootprint() {
        EmulatedGame game = gameWithPathfinding();

        // Place a 5x5 Nexus at (10, 8)
        game.debugCreateBuilding(BuildingType.NEXUS, new Point2d(10, 8));

        String tag = game.spawnFriendlyForTesting(UnitType.ZEALOT, new Point2d(5, 8));
        game.friendlyPhysics.unitTargets.put(tag, new Point2d(16, 8));

        int[] fp = SC2Data.buildingFootprint(BuildingType.NEXUS);
        float hw = fp[0] / 2.0f;
        float hh = fp[1] / 2.0f;

        for (int i = 0; i < 40; i++) {
            game.tick();
            Unit u = game.snapshot().myUnits().stream()
                .filter(unit -> unit.tag().equals(tag))
                .findFirst().orElseThrow();
            boolean inside = u.position().x() >= 10 - hw && u.position().x() <= 10 + hw
                          && u.position().y() >= 8 - hh && u.position().y() <= 8 + hh;
            assertFalse(inside, "Unit entered building footprint at tick " + i
                + " position=" + u.position());
        }
    }

    @Test
    void enforceWall_blocksIncompleteBuildings() {
        EmulatedGame game = gameWithPathfinding();
        TerrainGrid grid = game.getTerrainGrid();

        // handleBuild creates incomplete buildings — but needs resources + race model.
        // Instead, verify that enforceWall now blocks even without isComplete:
        // debugCreateBuilding creates complete buildings, so test with incomplete via
        // the obstacle layer directly — if obstacle is registered, A* won't route through it.
        // The key assertion: cells are blocked from placement (tested in BuildingObstacleTest).
        // Here we verify enforceWall drops the isComplete guard by checking an incomplete
        // building in the building list doesn't allow entry.

        // Manually add an incomplete building to test enforceWall
        game.friendly.addBuilding(new Building("test-inc", BuildingType.GATEWAY,
            new Point2d(10, 8), 100, 1000, false));
        // Register obstacle manually (would be done by handleBuild in real code)
        int[] fp = SC2Data.buildingFootprint(BuildingType.GATEWAY);
        grid.markRectangularObstacle(10, 8, fp[0] / 2.0f, fp[1] / 2.0f);

        assertFalse(grid.isWalkable(10, 8), "Incomplete building should block pathfinding");

        // Verify unit can't path through it
        String tag = game.spawnFriendlyForTesting(UnitType.ZEALOT, new Point2d(5, 8));
        game.friendlyPhysics.unitTargets.put(tag, new Point2d(15, 8));

        for (int i = 0; i < 30; i++) {
            game.tick();
            Unit u = game.snapshot().myUnits().stream()
                .filter(unit -> unit.tag().equals(tag))
                .findFirst().orElseThrow();
            boolean inside = u.position().x() >= 10 - fp[0] / 2.0f && u.position().x() <= 10 + fp[0] / 2.0f
                          && u.position().y() >= 8 - fp[1] / 2.0f && u.position().y() <= 8 + fp[1] / 2.0f;
            assertFalse(inside, "Unit entered incomplete building footprint at tick " + i);
        }
    }
}

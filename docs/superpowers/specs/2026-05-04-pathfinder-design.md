# Pathfinder Enhancement — Design Spec
_2026-05-04_

## Overview

Three independent improvements to the existing pathfinding infrastructure, implemented in order c → b → a:

- **C — Real SC2 terrain extraction**: wire the existing `TerrainGrid.fromPathingGrid()` into the real SC2 engine so terrain is available to agent plugins during live games
- **B — Terrain-aware movement costs**: weight A* edge costs by tile type (RAMP = 1.5×) so units prefer flat ground when both routes exist
- **A — Path smoothing**: post-process A* waypoints with greedy string-pulling to eliminate unnecessary intermediate points on open ground

The core infrastructure (`AStarPathfinder`, `PathfindingMovement`, `TerrainGrid`, `TerrainProvider`) already exists and is tested. All three changes are small, targeted, and independently testable without a running SC2 instance.

---

## C: Real SC2 Terrain Extraction

### SC2BotAgent → CDI bean

`SC2BotAgent` is currently constructed manually in `RealSC2Engine.connect()`. Convert it to `@ApplicationScoped @IfBuildProfile("sc2")` so it can inject `TerrainProvider` and `IntentQueue` directly, removing the constructor parameter chain.

`RealSC2Engine` injects `SC2BotAgent` and passes it to `S2Coordinator.createParticipant()`. ocraft calls the callback methods on the injected instance (through the Quarkus proxy). `@ApplicationScoped` is always active regardless of which thread calls the callbacks — no context-not-active risk.

### Terrain extraction in `onGameStart()`

```java
@Override
public void onGameStart() {
    observation().getGameInfo().getStartRaw().ifPresent(raw -> {
        var grid = raw.getPathingGrid();
        TerrainGrid terrain = TerrainGrid.fromPathingGrid(
            grid.getData().toByteArray(),
            grid.getSize().getX(),
            grid.getSize().getY());
        terrainProvider.setTerrain(terrain);
        log.infof("[SC2] Terrain extracted — %dx%d pathing grid",
            grid.getSize().getX(), grid.getSize().getY());
    });
}
```

Terrain is extracted once at game start — pathing data is static for the lifetime of a game (it comes from `RequestGameInfo`, not per-frame observations). Publishing via `TerrainProvider` makes it available to all CDI-injected plugins (tactics, scouting) with no further wiring.

`MovementStrategy` / `PathfindingMovement` are **not** wired into `RealSC2Engine` — the real SC2 engine does not simulate movement. Terrain flows to agent decision-making (reachability checks, retreat target selection), not to a movement loop.

### Testing

`SC2BotAgentTerrainTest` (new, plain JUnit): construct a synthetic 4×4 pathing grid bitmap with one wall bit, call extraction logic directly, assert `TerrainGrid` reflects correct walkability. No ocraft mock needed — `TerrainGrid.fromPathingGrid()` takes raw `byte[]`.

---

## B: Terrain-Aware Movement Costs

### `TerrainGrid.movementCost(int x, int y)`

New method in `domain/TerrainGrid`:

```java
public double movementCost(int x, int y) {
    return heightAt(x, y) == Height.RAMP ? 1.5 : 1.0;
}
```

RAMP tiles cost 1.5× to traverse. LOW and HIGH tiles cost 1.0×. WALL tiles are never expanded by A* (already filtered by `isWalkable()`).

### `AStarPathfinder` — weighted edge cost

In `findPath()`, multiply the edge cost by the destination tile's movement cost:

```java
double cost = (d[0] != 0 && d[1] != 0) ? DIAG : 1.0;
cost *= grid.movementCost(nx, ny);
open.add(new Node(nx, ny, cur.g() + cost, cur));
```

Units naturally prefer LOW ground when alternative flat routes exist. They still traverse ramps when the ramp is the only option (as in the emulated map's single chokepoint at x=11–13).

### Testing

Add to `AStarPathfinderTest`:
- Given two routes of equal tile length — one flat, one through a ramp — assert the flat route is chosen
- Given a map where the ramp is the only option, assert a path is still found through it

Add `TerrainGridTest` (new or extend existing): `movementCost()` returns 1.5 for RAMP, 1.0 for LOW and HIGH.

---

## A: Path Smoothing (String-Pulling)

### Algorithm

After A* returns a waypoint list, a greedy post-processing pass removes unnecessary intermediate points. Starting from waypoint 0, find the furthest later waypoint reachable in a straight line without crossing a WALL tile (Bresenham line check). Skip everything in between. Repeat from the kept waypoint until the end of the path.

```
A* raw:    1→2→3→4→5→6→7
LOS(1,5):  clear
LOS(1,6):  wall crosses
Keep 5.  Continue from 5:
LOS(5,7):  clear
Smoothed:  1→5→7
```

### `AStarPathfinder.smoothPath()`

New static method in `domain/AStarPathfinder` (no engine deps):

```java
public static List<Point2d> smoothPath(List<Point2d> path, TerrainGrid grid)
```

Bresenham LOS check: step along the integer tiles between two world-coordinate waypoints; if any tile is a WALL, line-of-sight is blocked. Returns the original path unchanged if it has ≤ 2 points.

### `PathfindingMovement` — call smoothPath after findPath

In `advance()`, after computing the A* path, smooth it before caching:

```java
List<Point2d> path = pathfinder.findPath(grid, current, target);
path = AStarPathfinder.smoothPath(path, grid);
waypoints.put(unitTag, new ArrayDeque<>(path));
```

`DirectMovement` is unaffected — no waypoints.

### Testing

Add to `AStarPathfinderTest`:
- Straight-line path on open ground → `smoothPath()` returns only start and end
- Path bending around a wall corner → waypoints past the corner are preserved (LOS blocked)
- Zigzag path on fully open ground → all intermediate points removed

---

## Child Issues

| Issue | Title | Depends on |
|-------|-------|-----------|
| Terrain extraction | `SC2BotAgent` → CDI bean + terrain extraction in `onGameStart()` | — |
| Weighted costs | Terrain-aware A* edge costs (`movementCost`) | — |
| Path smoothing | `smoothPath` static method + wire into `PathfindingMovement` | — |

All three are independent. Natural implementation order: terrain extraction → weighted costs → path smoothing.

---

## Files Changed

| File | Change |
|------|--------|
| `sc2/real/SC2BotAgent.java` | `@ApplicationScoped @IfBuildProfile("sc2")`; inject `TerrainProvider`; extract terrain in `onGameStart()` |
| `sc2/real/RealSC2Engine.java` | Inject `SC2BotAgent`; remove manual construction |
| `domain/TerrainGrid.java` | Add `movementCost(int x, int y)` |
| `domain/AStarPathfinder.java` | Weighted edge cost; add static `smoothPath()` |
| `sc2/emulated/PathfindingMovement.java` | Call `smoothPath()` after `findPath()` |
| `test/.../SC2BotAgentTerrainTest.java` | New — synthetic bitmap extraction |
| `test/.../AStarPathfinderTest.java` | Extended — weighted cost + smoothing tests |
| `test/.../TerrainGridTest.java` | New or extended — `movementCost()` |

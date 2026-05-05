# Handover — 2026-05-05

**Head commit:** `20e74ab` — blog entry "both sides of the board"

## What Changed This Session

**Two epics landed:**

**Enemy Active AI (#116, closed)** — `EmulatedGame` refactored to symmetric `PlayerState × 2`. Both players drain through `applyIntent(Intent, PlayerState)`. `EnemyBehavior implements PlayerBehavior` drives enemy production, attack, and retreat via the same `TrainIntent`/`AttackIntent`/`MoveIntent` types the friendly AI uses. `TechTree` (full three-race prerequisite graph) gates production. `EnemyStrategyLibrary` holds 9 named strategies + `ReactiveStrategy` (counter-picks every 50 frames). `SC2BotAgent` converted to `@ApplicationScoped @IfBuildProfile("sc2")` CDI bean.

**Pathfinder enhancements (#120, closed)** — RAMP tiles now cost 1.5× in A*. `AStarPathfinder.smoothPath()` applies greedy string-pulling using sub-tile LOS sampling (0.4-unit steps, below movement speed 0.5). Key bug: Bresenham tile-centre LOS passes but movement at 0.5 tiles/tick lands in wall tiles the Bresenham line misses — caused infinite path-invalidation loops. Fixed by sub-tile sampling. `SC2BotAgent.onGameStart()` extracts pathing grid from `StartRaw.getPathingGrid()` and publishes via `TerrainProvider`.

**624 tests, 0 failures.**

## Immediate Next Step

No open active issues. Good candidates for next work:
- **Pathfinder brainstorm still pending** — Task #1 in task list is marked in_progress but we fully completed it (spec + plan at `docs/superpowers/plans/2026-05-04-pathfinder.md`). Clean up task list.
- **Protoss sprites plans** still untracked at `docs/superpowers/plans/2026-04-23-e18a/b-*.md`
- The natural next epic: real enemy AI behaviour in the visualizer — does the enemy actually attack convincingly? Run `mvn quarkus:dev -Dquarkus.profile=emulated` and observe.

## Key Technical Notes

- **`emulatedMap()` gap is x=11–13, y=18 (RAMP)** — `enemyRespectsWallWithPathfinding` test requires `game.setTerrainGrid(terrain)` in addition to `game.setMovementStrategy(new PathfindingMovement(terrain))` or `enforceWall()` is a no-op.
- **`EnemyBehavior` constructor is 3-arg**: `(EnemyStrategy, PlayerState, TechTree)` — use permissive TechTree (`canTrain → true`) in unit tests that don't exercise tech gating.
- **`setEnemyStrategy()` shim**: wraps strategy in `EnemyBehavior` with permissive TechTree — keeps existing tests working but skips tech tree checks.
- **ocraft `ImageData.getData()` returns `byte[]` directly** — no `.toByteArray()` needed.

## Open Issues

| # | What | Status |
|---|------|--------|
| #74 | Unit genericisation as YAML | Parked |
| #13 | Live SC2 smoke test | Blocked on SC2 |
| #14 | GraalVM native image | Blocked on #13 |

## References

| Context | Where |
|---------|-------|
| Blog entry (this session) | `docs/_posts/2026-05-05-mdp01-both-sides-of-the-board.md` |
| Enemy AI spec | `docs/superpowers/specs/2026-04-30-enemy-ai-design.md` |
| Pathfinder spec | `docs/superpowers/specs/2026-05-04-pathfinder-design.md` |
| Pathfinder plan | `docs/superpowers/plans/2026-05-04-pathfinder.md` |
| Prior handover | `git show HEAD~1:HANDOFF.md` |

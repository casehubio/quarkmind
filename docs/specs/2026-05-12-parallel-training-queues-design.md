# Parallel Training Queues — Design Spec
**Issue:** #128 (epic #127 — Phase 5 completion)
**Date:** 2026-05-12

## Problem

`EmulatedGame.handleTrain()` has two correctness gaps that will cause Phase 6 replay
validation to diverge from real SC2 data:

1. **Supply reserved at completion, not queue time.** A TODO comment acknowledges
   this. Two TrainIntents issued in the same tick can both pass the supply check,
   but only one of them should succeed — the second should be blocked because the
   first already consumed the supply.

2. **No per-building queue.** All training completions go into a flat
   `pendingCompletions` list on `PlayerState`. Multiple TrainIntents for the same
   building are processed independently with no ordering, and there is no limit on
   how many units a single building can "train" simultaneously.

3. **Enemy uses fake building tags.** `EnemyBehavior.tickProduction()` generates
   `"enemy-nexus-N"` as the building tag on each TrainIntent, ignoring the real
   building that was seeded into `enemy.buildings` on reset. This means building
   validation cannot be enforced for the enemy, and any future per-building logic
   applies only to the friendly player.

## Scope

Closes all three gaps. Specifically:

- Per-building training queue (one slot active, up to 4 waiting = 5 total, matching SC2)
- Supply reserved at queue time (before the completion fires)
- Full building validation: tag must resolve to a complete building in `state.buildings`
- Enemy uses real building tags looked up from `enemy.buildings`
- `TrainIntent.unitTag` renamed to `buildingTag` (the field is a building tag, not a unit tag)
- `SC2Data.trainedBy(UnitType)` added to the domain layer

## Data Model

### `PlayerState` additions

```java
// Per-building training queue: unit types waiting to start (not counting current)
// Max depth: 4 waiting + 1 active = 5 total (SC2 queue limit)
final Map<String, Deque<UnitType>> buildingQueues       = new HashMap<>();

// When the currently-training unit finishes per building tag. Absent = idle.
final Map<String, Long>            buildingTrainingUntil = new HashMap<>();
```

Both maps cleared in `clear()`.

### `SC2Data` addition (domain layer)

```java
public static BuildingType trainedBy(UnitType type) { ... }
```

Maps each `UnitType` to the `BuildingType` that produces it (Gateway→Zealot/Stalker,
Nexus→Probe, Robotics→Immortal/Observer, Barracks→Marine/Marauder,
Hatchery→Drone/Zergling/Roach/Hydralisk, etc.).

## Algorithm

### `handleTrain(TrainIntent t, PlayerState state)`

```
1. Resolve building: find b in state.buildings where b.tag() == t.buildingTag() && b.isComplete()
   → if absent: log and return (building not ready)

2. Check resources: minerals >= mCost, vespene >= gCost, supplyUsed + sCost <= supply
   → if insufficient: log and return

3. Check queue depth:
   isBusy = buildingTrainingUntil.containsKey(tag)
   total  = (isBusy ? 1 : 0) + buildingQueues.getOrDefault(tag, empty).size()
   → if total >= 5: log and return (queue full)

4. Reserve supply immediately: state.supplyUsed += sCost
   Deduct minerals and gas.

5. If idle (!isBusy): call startTraining(tag, unitType, state)
   If busy:           buildingQueues.computeIfAbsent(tag, ...).add(unitType)
```

### `startTraining(String buildingTag, UnitType type, PlayerState state)`

Extracted helper:

```
completesAt = gameFrame + SC2Data.trainTimeInTicks(type)
buildingTrainingUntil.put(buildingTag, completesAt)
pendingCompletions.add(PendingCompletion(completesAt, () -> {
    buildingTrainingUntil.remove(buildingTag)
    spawn unit at (9, 9) with full HP/shields
    if enemy: notifyUnitTrained()
}))
```

### Queue drain in `tick()`

After `friendly.fireCompletions(gameFrame)` and `enemy.fireCompletions(gameFrame)`,
call `drainBuildingQueues(friendly)` and `drainBuildingQueues(enemy)`.

```
drainBuildingQueues(PlayerState state):
  for each tag in new ArrayList<>(state.buildingQueues.keySet()):
    if buildingTrainingUntil.containsKey(tag): skip (still busy)
    queue = buildingQueues.get(tag)
    if queue is null or empty: buildingQueues.remove(tag); continue
    next = queue.poll()
    startTraining(tag, next, state)
```

The drain runs after completions have fired — no concurrent-modification risk.

## Enemy Changes

### `EnemyBehavior.tickProduction()`

Replace fake tag generation:

```java
// Before
String buildingTag = "enemy-nexus-" + nextTag++;
queue.add(new TrainIntent(buildingTag, target));

// After
BuildingType needed = SC2Data.trainedBy(target);
Optional<Building> trainer = enemy.buildings.stream()
    .filter(b -> b.isComplete() && b.type() == needed)
    .findFirst();
if (trainer.isEmpty()) return;
queue.add(new TrainIntent(trainer.get().tag(), target));
```

The `"enemy-main"` building seeded in `reset()` handles basic units (Zealot/Marine/Zergling
from main structure equivalent). Prerequisite tech buildings built via `BuildIntent` are
tagged by `handleBuild` (e.g., `"bldg-201"`) and stored in `enemy.buildings` — the
stream lookup finds them by type.

`trainingPending` flag stays — enemy trains one unit at a time (conservative, correct
for Phase 5).

## Rename

`TrainIntent.unitTag` → `buildingTag` via `ide_refactor_rename`. Call sites using the
constructor (positional args) compile unchanged. Accessor call sites (`t.unitTag()`) are
updated by the refactor across `EmulatedGame`, `EnemyBehavior`, plugin classes, and tests.

## Tests

All new tests are plain JUnit (no `@QuarkusTest`).

| Test | What it verifies |
|------|-----------------|
| `parallelTrainingTwoGateways` | Two Gateways each issue a TrainIntent; both units appear at the correct tick |
| `supplyReservedAtQueueTime` | Queue a Stalker to a busy Gateway; `supplyUsed` increments immediately, not on completion |
| `queueFullRejected` | Fill a building to 5 total; 6th TrainIntent rejected, supply unchanged |
| `queueDrainsSequentially` | Queue 2 units to one Gateway; second starts immediately when first finishes |
| `buildingValidationRejectsUnknownTag` | TrainIntent with unknown building tag is silently dropped |
| `enemyUsesBuildingFromEnemyBuildings` | Enemy TrainIntent resolves `"enemy-main"` tag, not a fake tag |

Existing `trainIntentDeductsMinerals`, `trainedUnitAppearsAfterBuildTime`,
`trainBlockedIfInsufficientMinerals` pass unchanged — they use `"nexus-0"` which is a
valid complete building already seeded in `reset()`.

## Files Touched

| File | Change |
|------|--------|
| `domain/SC2Data.java` | Add `trainedBy(UnitType)` |
| `sc2/intent/TrainIntent.java` | Rename `unitTag → buildingTag` (via IntelliJ) |
| `sc2/emulated/PlayerState.java` | Add `buildingQueues`, `buildingTrainingUntil`, update `clear()` |
| `sc2/emulated/EmulatedGame.java` | Rewrite `handleTrain()`, add `startTraining()`, add `drainBuildingQueues()`, update `tick()` |
| `sc2/emulated/EnemyBehavior.java` | Replace fake tag generation with real building lookup |
| `sc2/emulated/EmulatedGameTest.java` | 6 new tests; existing pass unchanged |
| `docs/roadmap-sc2-engine.md` | Remove "Parallel training queues" from Phase 5 "Not yet implemented" |

Plugin files (`BasicStrategyTask`, `DroolsStrategyTask`, `BasicEconomicsTask`,
`EconomicsDecisionService`) updated automatically by IntelliJ refactor if they access
`t.unitTag()` — constructor call sites need no change.

## Invariants

- Supply is always reserved before a `PendingCompletion` is created.
- A building tag appears in `buildingTrainingUntil` if and only if there is a live
  `PendingCompletion` for it.
- `buildingQueues` entries for a tag are non-empty only while the building is busy;
  they are removed when the queue drains to empty.
- `fireCompletions` always completes before `drainBuildingQueues` runs in the same tick.

# Design: Building-Cost Mineral Timing (#146)

**Date:** 2026-05-21
**Issue:** [#146](https://github.com/mdproctor/quarkmind/issues/146)
**Branch:** `issue-146-building-cost-mineral-timing`

## Problem

`ReplayValidationHarness` injects buildings into `EmulatedGame` from replay tracker events without deducting their mineral costs. EmulatedGame therefore accumulates ~850 more minerals than ground truth (GT) over a 3-minute game. When a train command arrives at a moment when the real player was briefly mineral-constrained, EmulatedGame executes it one tick earlier — causing `firstUnitDivergenceTick = 86` and `maxUnitDelta = 2`.

The saturation model (#141) already reduced the residual mineral delta from ~11,564 to ~850. The remaining delta is building-cost timing only.

## Root Cause

`injectReplayBuilding(Building)` adds the building to `EmulatedGame.friendly.buildings` with no cost deduction:

```java
public void injectReplayBuilding(Building building) {
    friendly.buildings.add(building);
}
```

In the real game, minerals are deducted when construction is ordered — which is the same loop the tracker event fires. The harness should mirror this.

## Design

### Approach

Harness owns the routing decision between free injection (initial buildings) and cost-deducting injection (buildings ordered during the game). Two injection paths are conceptually distinct; separate methods make intent explicit and allow independent testing.

### Initial Building Detection

The starting Nexus is free in SC2 — it is given to the player at game start, not purchased. Deducting its 400-mineral cost would immediately push EmulatedGame negative. Before the main loop, capture the set of GT building tags present in the initial snapshot:

```java
Set<String> initialBuildingTags = replayGame.snapshot().myBuildings().stream()
    .map(Building::tag)
    .collect(Collectors.toSet());
```

Any building whose tag appears in this set is a starting building — use free injection. All others use cost-deducting injection.

### `EmulatedGame` — new method

```java
public void injectReplayBuildingWithCost(Building building) {
    friendly.minerals = Math.max(0, friendly.minerals - SC2Data.mineralCost(building.buildingType()));
    friendly.buildings.add(building);
}
```

`Math.max(0, ...)` is a defensive guard. In a correct model it should never fire — the real player could not have ordered a building they could not afford. If it fires, it indicates a model gap worth investigating, not a crash condition.

### `ReplayValidationHarness` — routing in `syncBuildings`

`syncBuildings` gains an `initialBuildingTags` parameter. When a new building tag is seen:

- Tag is in `initialBuildingTags` → `emulated.injectReplayBuilding(building)` (free)
- Tag is not in `initialBuildingTags` → `emulated.injectReplayBuildingWithCost(building)` (deduct cost)

### Vespene costs

No Protoss, Terran, or Zerg buildings have vespene costs — only upgrades do. No vespene deduction is needed.

## Test Strategy

### Unit tests

- `EmulatedGameTest`: test `injectReplayBuildingWithCost` deducts the correct mineral cost; test the `Math.max(0, ...)` floor when minerals are insufficient; verify the building is still added in both cases.

### Integration / validation

Run `ReplayValidationHarness` after the fix and record actual values. Then tighten `ReplayValidationTest` assertions to document what the corrected model achieves:

- `firstUnitDivergenceTick` — expected `-1` (no divergence); assert accordingly
- `maxUnitDelta` — expected `0`; assert accordingly
- `maxMineralDelta` — record actual bound after the run; tighten from `≤ 1100`

The test assertions are the specification — they document the model's actual accuracy, not aspirational bounds.

## Out of Scope

- Extra "nexus-0" in EmulatedGame during harness runs (EmulatedGame starts with a Nexus; harness also injects the GT Nexus — building delta is 1 throughout). Not blocking; tracked as a separate gap if needed.
- Multi-base mining (#143) — `mineralIncomePerTick` TODO already documents this.

# Design: Building-Cost Mineral Timing (#146)

**Date:** 2026-05-21
**Issue:** [#146](https://github.com/casehubio/quarkmind/issues/146)
**Branch:** `issue-146-building-cost-mineral-timing`

## Problem

`ReplayValidationHarness` injects buildings into `EmulatedGame` from replay tracker events without deducting their mineral costs. EmulatedGame therefore accumulates more minerals than ground truth (GT) over time. When a train command arrives at a moment when the real player was briefly mineral-constrained, EmulatedGame executes it one tick earlier — causing `firstUnitDivergenceTick = 86` and `maxUnitDelta = 2`.

The saturation model (#141) already reduced the residual mineral delta from ~11,564 to ~850 at 3 minutes. The original hypothesis for #146 was that the remaining delta was building-cost timing only.

## Root Cause

`injectReplayBuilding(Building)` adds the building to `EmulatedGame.friendly.buildings` with no cost deduction:

```java
public void injectReplayBuilding(Building building) {
    friendly.buildings.add(building);
}
```

In the real game, minerals are deducted when construction is ordered.

## Design

### Approach

Harness owns the routing decision between free injection (initial buildings) and cost-deducting injection (buildings ordered during the game). Two injection paths are conceptually distinct; separate methods make intent explicit and allow independent testing.

### Initial Building Detection

The starting Nexus is free in SC2 — it is gifted to the player at game start, not purchased. Before the main loop, capture the set of GT building tags present in the initial snapshot:

```java
Set<String> initialBuildingTags = replayGame.snapshot().myBuildings().stream()
    .map(Building::tag)
    .collect(Collectors.toSet());
```

Any building whose tag appears in this set is a starting building — use free injection. All others use cost-deducting injection.

### `EmulatedGame` — new method

```java
public void injectReplayBuildingWithCost(Building building) {
    friendly.minerals -= SC2Data.mineralCost(building.type());
    friendly.buildings.add(building);
}
```

**No floor at 0.** Minerals may go negative when EM's model-approximated balance is below the real player's balance at injection time (a model artifact from income approximation timing). The debt is repaid through mining income over the next few ticks — this correctly blocks training during the player's mineral-constrained period without clamping EM at 0, which would instead mask the debt and cause EM to recover too quickly. The `handleTrain` guard (`(int) state.minerals < mCost`) correctly rejects training while balance is negative.

### `ReplayValidationHarness` — intended routing in `syncBuildings`

```java
if (initialBuildingTags.contains(gtBuilding.tag())) {
    emulated.injectReplayBuilding(gtBuilding);      // free
} else {
    emulated.injectReplayBuildingWithCost(gtBuilding); // deduct cost
}
```

### Vespene costs

No Protoss, Terran, or Zerg buildings have vespene costs — only upgrades do. No vespene deduction is needed.

## Plan vs. Delivered

### What was delivered

`injectReplayBuildingWithCost` was implemented and tested on `EmulatedGame` as designed. The harness routing was **not** wired in.

### Why the harness routing was not wired in

Investigation during implementation revealed that deducting building costs in the harness makes unit-count divergence significantly worse (firstUnitDivergenceTick moved from 86 to 49, maxUnitDelta grew from 2 to 48–78 depending on floor strategy).

**Root cause of the failure:** EM's continuous mineral model does not align with the real player's balance at building-injection time. GT mineral readings come from PlayerStats events sampled infrequently — the reading at the tick a building appears does not reflect the player's real-time balance when they ordered it. Deducting the building cost against EM's approximated balance creates a mineral debt that blocks trains EM should not miss, reversing the direction of the divergence.

**What the investigation found about the tick-86 divergence:** The unit that diverges at tick 86 completes in EM at tick 86 and in the real game at tick 87. This is a completion-time formula issue in `startTraining` — `completesAt` rounds to 86 while SC2 completes the unit one tick later. This is independent of mineral balance; deducting building costs does not fix it.

**Two independent divergence causes (filed as follow-on work):**
1. Sub-tick timing formula in `startTraining` — causes the tick-86 discrepancy. See #146.
2. No vespene income in EM — causes growing delta when gas units (Stalker, Immortal) are trained. See #148.

## Test Strategy

### Unit tests

- `EmulatedGameTest`: test `injectReplayBuildingWithCost` deducts the correct mineral cost; test the negative-balance (debt) case; verify the building is still added; verify free `injectReplayBuilding` is unchanged.

### Integration / validation

`ReplayValidationTest` assertions are unchanged — the existing bounds remain correct with the harness unmodified:

- `firstUnitDivergenceTick >= 80` (actual 86 — timing formula gap)
- `maxUnitDelta <= 2` (timing formula + gas units)
- `maxMineralDelta <= 1100` (saturation model bound)

The test docstring was updated to correctly document the two independent divergence causes.

## Out of Scope

- Sub-tick timing formula refinement (why completesAt is 1 too low for some loop offsets) — primary fix needed for firstUnitDivergenceTick = -1.
- Vespene income tracking (EmulatedGame has no gas model) — filed as #148.
- Multi-base mining (#143) — `mineralIncomePerTick` TODO already documents this.

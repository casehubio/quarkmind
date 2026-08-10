# Design: EmulatedGame fixes — #144, #147, #148

**Branch:** `issue-148-emulated-fixes`  
**Issues:** #144 (SC2DataTest default branch), #147 (switch exhaustiveness + queued-unit comment), #148 (vespene sync)  
**Date:** 2026-05-22

---

## #144 — SC2DataTest: pin trainTimeInLoops default branch

`SC2Data.trainTimeInLoops` has a `default -> 672` arm (30s × 22.4 loops, exact integer) covering all unmapped unit types. Neither test method covers this branch, so a future change to the default value would be silent.

**Fix:** Add one assertion to each existing test method.

In `trainTimeInLoopsDefinedForProtossUnits`:
```java
assertThat(SC2Data.trainTimeInLoops(UnitType.UNKNOWN)).isEqualTo(672);
```

In `trainTimeInTicksDefinedForProtossUnits`:
```java
assertThat(SC2Data.trainTimeInTicks(UnitType.UNKNOWN)).isEqualTo(30); // 672 / 22 = 30.54 → 30
```

`UnitType.UNKNOWN` is the canonical unmapped sentinel. No new test methods needed.

---

## #147 — Switch exhaustiveness + queued-unit comment

### Part 1: Convert applyIntent switch statements to switch expressions

**Problem.** Java 21 switch *statements* with pattern matching are not exhaustiveness-checked at compile time — a new `Intent` subtype compiles cleanly and throws `MatchException` at runtime. Switch *expressions* are compile-time exhaustive.

Two methods in `EmulatedGame` use switch statements over the sealed `Intent` interface:
- `applyIntent(TimedIntent ti)` (line 225)
- `applyIntent(Intent intent, PlayerState state)` (line 235)

**Fix.** Convert both to switch expressions using the `Runnable` dispatch pattern. Each arm captures the void call in a lambda; the expression is exhaustiveness-checked; `action.run()` executes it. No behavioral change.

`applyIntent(TimedIntent ti)`:
```java
public void applyIntent(TimedIntent ti) {
    Runnable action = switch (ti.intent()) {
        case TrainIntent  t -> () -> handleTrain(t, friendly, ti.loop());
        case MoveIntent   m -> () -> setTarget(m.unitTag(), m.targetLocation(), false, friendly);
        case AttackIntent a -> () -> setTarget(a.unitTag(), a.targetLocation(), true,  friendly);
        case BuildIntent  b -> () -> handleBuild(b, friendly);
        case BlinkIntent  b -> () -> executeBlink(b.unitTag(), friendly);
    };
    action.run();
}
```

`applyIntent(Intent intent, PlayerState state)`:
```java
void applyIntent(Intent intent, PlayerState state) {
    Runnable action = switch (intent) {
        case MoveIntent   m -> () -> setTarget(m.unitTag(), m.targetLocation(), false, state);
        case AttackIntent a -> () -> setTarget(a.unitTag(), a.targetLocation(), true,  state);
        case TrainIntent  t -> () -> handleTrain(t, state);
        case BuildIntent  b -> () -> handleBuild(b, state);
        case BlinkIntent  b -> () -> executeBlink(b.unitTag(), state);
    };
    action.run();
}
```

No new tests needed — existing `EmulatedGameTest` coverage exercises all arms. The refactor is a compile-time safety improvement, not a behavioral change.

### Part 2: Document queued-unit timing imprecision

`drainBuildingQueues` calls `startTraining(buildingTag, next, state, 0L)`. The `0L` loses the original command's sub-tick loop offset, so queued units can complete ±1 tick vs replay. The existing comment documents the mechanism but not the accuracy impact.

**Fix.** Sharpen the comment:
```java
// Before:
startTraining(buildingTag, next, state, 0L); // queued units have no loop context — 0L uses integer-tick precision

// After:
startTraining(buildingTag, next, state, 0L); // queued units carry no original loop — sub-tick offset is lost; unit can complete ±1 tick vs replay
```

---

## #148 — Vespene income sync in ReplayValidationHarness

### Root cause

`EmulatedGame` initialises with `friendly.vespene = SC2Data.INITIAL_VESPENE` (0) and earns no gas during `tick()`. `handleTrain` checks `state.vespene < gCost` and silently rejects Stalker (50 gas) and Immortal (100 gas) train commands. Each rejection creates a unit-count delta that accumulates over the game. The `ReplayValidationTest` documents this as the sole remaining cause of divergence at 3 minutes.

### Fix

**`EmulatedGame` — new method:**

```java
/**
 * Sets the friendly vespene pool from replay ground truth.
 * Mirrors the vespene the real player had available at this tick,
 * so gas-unit TrainIntents are not rejected by the resource check.
 * Public: called from ReplayValidationHarness in a different package.
 */
public void setVespeneForHarness(int vespene) {
    friendly.vespene = vespene;
}
```

Place beside `setSupplyCapForHarness` (line 730).

**`ReplayValidationHarness` — sync from `gtBefore` (pre-tick):**

```java
GameState gtBefore = replayGame.snapshot();
emulated.setMiningProbes(countProbes(gtBefore));
emulated.setVespeneForHarness(gtBefore.vespene());  // add this line
```

Pre-tick is correct: `gtBefore.vespene()` is the vespene the real player had *before* issuing train commands this tick. The subsequent TrainIntent application deducts gas costs in EmulatedGame, mirroring the real expenditure without double-counting.

Post-tick sync would set vespene to the post-training GT value, then deduct again when applying TrainIntents — double-counting gas costs within the same tick.

**`ReplayValidationTest` — tighten assertions:**

The current tolerance (`maxUnitDelta <= 15`, `firstUnitDivergenceTick >= 145`) was sized around vespene as the sole remaining divergence source. After this fix, no divergence is expected in the 3-minute window. Replace both assertions with:

```java
assertThat(report.summary().economicallyAccurate())
    .as("No unit or building divergence expected after vespene sync (#148).\n%s",
        report.renderReport())
    .isTrue();
```

`economicallyAccurate()` returns `true` when `firstUnitDivergenceTick == -1` and `firstBuildingDivergenceTick == -1`.

**New test in `EmulatedGameTest`:**

Follows existing `EmulatedGameTest` patterns: construct game with a complete Gateway (via `game.injectBuildingForTest` or equivalent helper), apply `TrainIntent`, assert on `snapshot().myUnits()`.

Two assertions:
1. `TrainIntent(gatewayTag, STALKER)` with `vespene == 0` → no Stalker in snapshot after `SC2Data.trainTimeInTicks(STALKER)` ticks
2. `setVespeneForHarness(50)` then same `TrainIntent` → Stalker present after completing ticks

Exact helper and setup method names resolved during implementation against the current `EmulatedGameTest` helper patterns.

---

## Testing summary

| Issue | Test change |
|-------|-------------|
| #144 | +2 assertions in existing `SC2DataTest` methods |
| #147 Part 1 | No new tests — refactor only; existing coverage applies |
| #147 Part 2 | No new tests — comment only |
| #148 | +1 test in `EmulatedGameTest` (vespene gate); `ReplayValidationTest` assertion tightened |

# Design Spec: completesAt Rounding Fix (#149)

**Branch:** issue-149-completesat-rounding  
**Issue:** #149  
**Date:** 2026-05-21

## Problem

After the sub-tick fix in #142, `ReplayValidationTest` shows `firstUnitDivergenceTick = 86`.
EM completes a unit at `gameFrame = 86`; the ground-truth replay completes it at `gameFrame = 87`.

Root cause: `SC2Data.trainTimeInLoops` returns `double` (`268.8` for Probe), but SC2 stores
train times as integer game loops internally. The float literal introduces a boundary error at
`loopOffset = 17`:

```
(17 + 268.8) / 22 = 285.8 / 22 = 12.99... → floor = 12   ← wrong
(17 + 269)   / 22 = 286   / 22 = 13.0     → floor = 13   ← correct
```

The fix has two parts: determine the correct integer values empirically, then change the type.

---

## Section 1: Calibration Test

**`SC2TrainTimeCalibrationTest`** (package `io.quarkmind.sc2.replay`) independently extracts
`T_real` (train time in game loops) from two sources and asserts they agree.

### Source A — real `.SC2Replay` via scelight

1. `ReplayCommandStream` yields `List<TimedIntent>` — filter for `TrainIntent` → `(loop, unitType)` pairs.
2. Iterate tracker events via `RepParserEngine`; collect `(loop, unitType, playerId)` from
   `ID_UNIT_BORN` events for the watched player, excluding initial units born at loop 0.
3. **FIFO match per unit type:** the i-th training command of type U (sorted by loop) maps to
   the i-th born event of type U (sorted by loop). Non-queued instances give
   `born_loop − command_loop = T_real` exactly; queued instances give larger differences.
4. `T_real(U) = min(born_loop[i] − command_loop[i])` across all instances of type U.
5. Consistency check: at least two instances must agree on the minimum before asserting.

### Source C — 28 other AI Arena replays (same patch)

1. The IEM10 JSON dataset was evaluated but found incompatible: it uses a 2016 SC2 patch
   with different `abilLink` values (e.g. abilLink=167 for Nexus vs 175 in AI Arena replays),
   making direct cross-validation impossible without a patch-aware abilLink table.
2. Source C uses the 28 other `.SC2Replay` files in `replays/aiarena_protoss/` — same patch,
   same abilLink mapping, different game patterns and opponents.
3. Same command extraction and modal calibration, aggregated across all 28 replays.

### Conflict detection

Per unit type: `assert T_real_A(U) == T_real_C(U)`.  
Both sources are the same AI Arena patch so any mismatch indicates an unexpected game pattern
or parsing inconsistency. The failure message reports both values and the source replay name.

### Output

The agreed integer `T_real` values feed the assertions in `SC2DataTest` and the constants in
`SC2Data`. Run the calibration test first; record the output; fill in the constants.

---

## Section 2: SC2Data

`trainTimeInLoops(UnitType)` return type changes from `double` to `int`.

- Float literals (`268.8`, `627.2`, etc.) replaced with empirical integers from calibration.
- `trainTimeInTicks` is unchanged: `return trainTimeInLoops(type) / LOOPS_PER_TICK;`
  (integer division, same floor semantics).
- `GAME_LOOPS_PER_SECOND = 22.4` stays `double` — reference constant for documentation,
  not used in training-time arithmetic.

---

## Section 3: `startTraining` formula

**Before:**
```java
int  loopOffset  = (int)(absLoop % SC2Data.LOOPS_PER_TICK);
long completesAt = gameFrame
    + (int)((loopOffset + SC2Data.trainTimeInLoops(unitType)) / SC2Data.LOOPS_PER_TICK);
```

**After:**
```java
int  loopOffset  = (int)(absLoop % SC2Data.LOOPS_PER_TICK);
long completesAt = gameFrame
    + (loopOffset + SC2Data.trainTimeInLoops(unitType)) / SC2Data.LOOPS_PER_TICK;
```

The `(int)` cast on the inner expression is removed. With `trainTimeInLoops` returning `int`,
all operands are integer — Java integer division is exact floor, eliminating the float
boundary error.

`drainBuildingQueues` (passes `0L`, integer-tick precision) is unchanged — tracked in #145/#147.

---

## Section 4: Test Updates

### `SC2DataTest`
- `trainTimeInLoopsDefinedForProtossUnits` — update expected values to empirical integers
  (filled in after calibration run).
- `trainTimeInLoopsConsistentWithTicks` — unchanged; invariant holds with integer arithmetic.

### `EmulatedGameTest`
- `probeCompletesOnTimeWithZeroLoopOffset` (offset=0 → 12 ticks) — unchanged.
- `probeCompletesOneLaterWithLateLoopOffset` (offset=18 → 13 ticks) — unchanged;
  `(18 + T_real) / 22 = 13` holds for any T_real ≥ 269.
- **New:** `probeCompletesOneLaterWithBoundaryLoopOffset` (offset=17 → 13 ticks).
  Fails before the fix (`(17 + 268.8)/22 = 12.99 → 12`), passes after
  (`(17 + 269)/22 = 13`). Pins the exact boundary the fix targets.
  Also covers the boundary-test request in #145.

### `ReplayValidationTest`
- After the fix, run `ReplayValidationReportTest` to observe the new `firstUnitDivergenceTick`
  (shifts from 86 to the first #148 vespene-unit divergence).
- Tighten `unitCountWithinToleranceForThreeMinutes`: replace `isGreaterThanOrEqualTo(80)`
  with the observed post-fix value; update assertion message to name #148 as sole remaining cause.
- Review `maxUnitDelta ≤ 2` bound — update if the vespene-unit count implies a tighter or
  different bound.

---

## Deferred / Out of Scope

| Item | Issue |
|------|-------|
| Building construction sub-tick timing (`handleBuild` uses integer-tick resolution) | #145 |
| Queued-unit loop precision in `drainBuildingQueues` | #145, #147 |
| Vespene income model (Stalker/Immortal trains rejected) | #148 |
| IEM10 `gameEvents` support in `IEM10JsonSimulatedGame` (enable multi-game harness validation) | #150 |

---

## Acceptance Criteria

- `SC2TrainTimeCalibrationTest` passes: sources A and C agree on T_real per unit type.
- `SC2Data.trainTimeInLoops` returns `int`; values match calibration output.
- `EmulatedGameTest.probeCompletesOneLaterWithBoundaryLoopOffset` passes.
- `ReplayValidationTest.unitCountWithinToleranceForThreeMinutes` assertion tightened past tick 86.
- All existing tests pass.

# EmulatedGame Vespene Income Model

**Issue:** #394
**Branch:** issue-394-emulatedgame-vespene-income
**Date:** 2026-10-09

## Problem

EmulatedGame accumulates mineral income per tick but has no vespene income model. Workers assigned to completed gas buildings (Assimilator, Refinery, Extractor) should generate gas income, and those workers should stop contributing to mineral income. Without this, economy playbooks cannot include gas building steps, and vespene-costing units/upgrades cannot be produced through normal income.

## Design

### 1. Gas income constants in SC2Data

Add a tiered gas income model parallel to `MINERAL_TIER_RATES_PER_TICK`:

```java
public static final int GAS_WORKERS_PER_BUILDING = 3;

public static final double[] GAS_TIER_RATES_PER_TICK = {
    38.0 / 60.0 * LOOPS_PER_TICK / GAME_LOOPS_PER_SECOND,  // worker 1
    38.0 / 60.0 * LOOPS_PER_TICK / GAME_LOOPS_PER_SECOND,  // worker 2
    20.0 / 60.0 * LOOPS_PER_TICK / GAME_LOOPS_PER_SECOND,  // worker 3 (diminished)
};
```

Add `gasIncomePerTick(int workerCount)` mirroring `mineralIncomePerTick`:

```java
public static double gasIncomePerTick(int workerCount) {
    if (workerCount < 0) throw new IllegalArgumentException("...");
    double income = 0;
    int remaining = workerCount;
    for (int tier = 0; tier < GAS_TIER_RATES_PER_TICK.length && remaining > 0; tier++) {
        income += GAS_TIER_RATES_PER_TICK[tier];
        remaining--;
    }
    return income;
}
```

Gas is simpler than minerals: each geyser has exactly one "slot" per tier (not 8 patches like mineral lines). So the function loops over tiers, consuming one worker per tier.

Add `isGasBuilding(BuildingType)` predicate:

```java
public static boolean isGasBuilding(BuildingType type) {
    return type == ASSIMILATOR || type == ASSIMILATOR_RICH
        || type == REFINERY || type == EXTRACTOR;
}
```

**Initial rate values** are from community data (close geyser: ~38 gas/min for workers 1-2, ~20 gas/min for worker 3). These are not replay-calibrated — a calibration follow-up is expected per protocol PP-20260522-572156.

### 2. Worker budget in EmulatedGame.tick()

Modify `tick()` to allocate workers to gas first, then minerals:

```
1. Count completed gas buildings from friendly.buildings()
2. gasWorkerCount = min(completedGasBuildings * 3, totalWorkers)
3. mineralWorkerCount = totalWorkers - gasWorkerCount
4. For each gas building: addVespene(gasIncomePerTick(min(3, remainingGasWorkers)))
5. Distribute mineralWorkerCount across bases via existing countWorkersPerBase logic
```

The key insight: `countWorkersPerBase` assigns `Unit` objects to bases by distance — it needs actual units, not just a count. Since we don't track which specific workers are on gas, we can't filter units before passing them.

Instead, deduct gas workers from the per-base counts after assignment:

- Run `countWorkersPerBase` as-is with all workers (unchanged)
- Compute `gasWorkerBudget = min(completedGasBuildings * GAS_WORKERS_PER_BUILDING, totalWorkers)`
- Subtract `gasWorkerBudget` from the per-base counts, removing from the largest base first (workers at saturated bases are the ones most likely reassigned to gas in real SC2)
- Accumulate gas income: iterate over completed gas buildings, giving each `min(3, remainingBudget)` workers

Worker assignment order: gas buildings consume workers from the total pool first; remaining workers mine minerals. This matches SC2 behaviour where gas-assigned workers are explicitly removed from the mineral line.

### 3. PlayerState.addVespene

Add `addVespene(double amount)` to `PlayerState`, parallel to `addMinerals(double amount)`. The vespene field type changes from `int` to `double` for accumulation precision (same reason minerals is `double`). The `vespene()` accessor casts to `int` for the public API, matching how SC2 displays integer resource counts.

### 4. Playbook vespene assertions

Add vespene bounds checking to `SC2DeliveryHandler.doAssert()`:

```java
if (expect.containsKey("vespene")) {
    var vespeneBounds = (Map<String, Integer>) expect.get("vespene");
    int actual = state.vespene();
    if (vespeneBounds.containsKey("min") && actual < vespeneBounds.get("min")) {
        errors.add("vespene expected >=" + vespeneBounds.get("min") + " but was " + actual);
    }
    if (vespeneBounds.containsKey("max") && actual > vespeneBounds.get("max")) {
        errors.add("vespene expected <=" + vespeneBounds.get("max") + " but was " + actual);
    }
}
```

Also add vespene to the assert log line for observability.

### 5. Existing test impact

- **countWorkersPerBase tests**: The method signature doesn't change, but callers in `tick()` will pass fewer workers. Existing unit tests that call `countWorkersPerBase` directly are unaffected (they don't build gas buildings).
- **EmulatedGameTest**: Tests that build gas buildings will now see vespene accumulation and reduced mineral income. Any test that asserts exact mineral counts after building a gas building may need adjustment.
- **Playbook calibration tests**: Economy-only playbooks don't build gas buildings, so no impact. New playbook steps for gas buildings are a follow-up.
- **ReplayValidationHarness**: Already bypasses gas income via `setVespeneForHarness()` — no impact.

## Files Changed

| File | Change |
|------|--------|
| `SC2Data.java` | Add `GAS_TIER_RATES_PER_TICK`, `GAS_WORKERS_PER_BUILDING`, `gasIncomePerTick()`, `isGasBuilding()` |
| `PlayerState.java` | Change vespene field to `double`, add `addVespene(double)` |
| `EmulatedGame.java` | Gas worker budget computation in `tick()`, gas income accumulation loop |
| `SC2DeliveryHandler.java` | Add vespene bounds to `doAssert()` |

## Testing

- Unit test for `SC2Data.gasIncomePerTick()` — tier boundaries, zero workers, max workers
- Unit test for `SC2Data.isGasBuilding()` — all gas types return true, non-gas returns false
- Unit test for `EmulatedGame` — build gas building, verify vespene accumulates after completion
- Unit test for `EmulatedGame` — verify mineral income decreases when gas building is present (workers diverted)
- Unit test for `EmulatedGame` — multiple gas buildings, worker budget capping
- Unit test for `doAssert()` — vespene min/max bounds assertions
- Existing `EmulatedGameTest` suite must continue to pass (no gas buildings = no behaviour change)

## Out of Scope

- Replay-calibrated gas income rates (follow-up, per calibration protocol)
- Economy playbook gas building steps (follow-up, depends on this foundation)
- Rich vespene geysers (ASSIMILATOR_RICH uses same rate for now)
- Partial gas saturation (< 3 workers per geyser)
- Gas depletion mechanics

## References

- `SC2Data.java:33-60` — MINERAL_TIER_RATES_PER_TICK and mineralIncomePerTick pattern
- `EmulatedGame.java:116-124` — current tick() mineral income loop
- `EmulatedGame.java:944-968` — countWorkersPerBase
- `PlayerState.java:27-41` — current economy fields
- `SC2DeliveryHandler.java:116-155` — current doAssert
- PP-20260522-572156 — SC2Data calibration protocol (rates need replay calibration as follow-up)

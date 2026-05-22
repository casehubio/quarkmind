# Design: #153 Code Review Nits + #152 Per-Base Probe Distribution

**Date:** 2026-05-23  
**Issues:** #153 (XS), #152 (S)  
**Branch:** `issue-153-152-review-nits-and-probe-dist`

---

## #153 — Code Review Nits

Three independent, localized changes from the #143/#147 code review.

### 1. sqrt → squared distance

`ReplayValidationHarness.countProbesPerBase` (line 146) uses `Math.sqrt(dx*dx + dy*dy)` to find the nearest nexus for each probe. Only relative ordering matters — squared distance preserves ordering identically. Replace with `dx*dx + dy*dy`. `minDist` initial value (`Double.MAX_VALUE`) works unchanged.

### 2. Defensive copy in setMiningProbesPerBase

`EmulatedGame.setMiningProbesPerBase(int... probesPerBase)` stores the varargs reference directly (`this.miningProbesPerBase = probesPerBase`). All current callers pass fresh arrays (varargs creates a new one), but the method signature exposes a mutable internal field. Change to `this.miningProbesPerBase = probesPerBase.clone()`.

### 3. Zero-nexus test for countProbesPerBase

`countProbesPerBase` returns `new int[0]` when no complete nexuses exist. Correct behavior, untested. Make the method package-private (drop `private`). Add `ReplayValidationHarnessTest` in the same package with a test that constructs a `GameState` with probes but zero complete nexuses and asserts empty array return → zero mineral income.

---

## #152 — Per-Base Probe Distribution for AI Expansion

### Problem

`EmulatedGame.miningProbesPerBase` is initialized to `{INITIAL_PROBES}` in `reset()` and never recalculated. When the AI builds a second Nexus (already supported by `handleBuild`), all probes remain assigned to the first base. Mineral income doesn't reflect multi-base mining.

### Design

**Auto-compute with override flag.** `tick()` recomputes `miningProbesPerBase` from `friendly.buildings` (complete nexuses) and `friendly.units` (probes) at the start of each tick, before mineral income calculation — unless an external override was set since the last tick.

**Algorithm extraction:** Move the probe-to-nexus assignment algorithm from `ReplayValidationHarness.countProbesPerBase` into `EmulatedGame` as a `static` package-private method. `ReplayValidationHarness.countProbesPerBase` delegates to it. The algorithm: find all complete nexuses in the building list, assign each probe to its nearest nexus by squared distance (from #153 fix), return per-base counts.

**Override mechanism:** A single boolean `miningProbesOverridden` on `EmulatedGame`:
- `setMiningProbesPerBase` sets the array AND `miningProbesOverridden = true`
- `tick()` start: if `!miningProbesOverridden`, recompute from buildings/units; then clear the flag regardless
- Replay harness calls `setMiningProbesPerBase` before every `tick()` → override consumed → flag cleared → next tick would auto-compute, but harness sets override again before it runs

One boolean of state, self-clearing, explicit.

**Method on EmulatedGame:**

```java
static int[] countProbesPerBase(List<Building> buildings, List<Unit> units) {
    List<Building> nexuses = buildings.stream()
        .filter(b -> b.type() == BuildingType.NEXUS && b.isComplete())
        .toList();
    if (nexuses.isEmpty()) return new int[0];

    int[] counts = new int[nexuses.size()];
    for (Unit u : units) {
        if (u.type() != UnitType.PROBE) continue;
        int nearest = 0;
        double minDistSq = Double.MAX_VALUE;
        for (int i = 0; i < nexuses.size(); i++) {
            double dx = u.position().x() - nexuses.get(i).position().x();
            double dy = u.position().y() - nexuses.get(i).position().y();
            double dSq = dx * dx + dy * dy;
            if (dSq < minDistSq) { minDistSq = dSq; nearest = i; }
        }
        counts[nearest]++;
    }
    return counts;
}
```

**ReplayValidationHarness change:**

```java
private static int[] countProbesPerBase(GameState state) {
    return EmulatedGame.countProbesPerBase(state.myBuildings(), state.myUnits());
}
```

### Test Coverage

| Scenario | Expected |
|----------|----------|
| Single nexus, 12 probes (default reset state) | `{12}` — existing behavior preserved |
| Two nexuses, all probes near one | All assigned to nearest nexus |
| Two nexuses, probes split by proximity | Distributed proportionally |
| No complete nexus, probes exist | `new int[0]` → zero mineral income |
| Nexus completes mid-game (BuildIntent) | Distribution updates on next tick |

---

## Implementation Order

1. #153 first — sqrt, defensive copy, zero-nexus test (each independently committable)
2. #152 second — extract algorithm, add override flag, auto-compute in tick, update harness, add tests

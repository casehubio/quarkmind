# Multi-base Mining + Queued-unit Loop Fix

**Issues:** #143 (multi-base mining), #147 (queued-unit loop imprecision)
**Date:** 2026-05-22
**Branch:** issue-143-multi-base-mining

## Problem

**#143:** `SC2Data.mineralIncomePerTick(int probeCount)` applies all probes against a single base's saturation curve. Once a replay extends past the ~3-minute window where the player expands, income is understated because 20 probes through one base's tiers produce less than 10+10 across two bases. This causes mineral drift in replay validation.

**#147:** `drainBuildingQueues` passes `0L` to `startTraining` for the next queued unit, losing the sub-tick loop offset of when the previous unit completed. Queued units can complete ±1 tick vs replay ground truth. Item 1 (switch exhaustiveness) was already fixed in commit 54b8d17.

## Design

### Scope

Both changes are replay validation accuracy fixes. EmulatedGame standalone (AI self-play) is unaffected — the AI cannot expand yet. AI expansion mining is tracked separately in #152.

### #143: Per-base probe counting

**`SC2Data.mineralIncomePerTick(int probeCount)` — unchanged.** It is already a per-base function. No new overloads.

**`EmulatedGame` — per-base state:**

Replace the scalar `miningProbes` field with an array:

```java
// State
private int[] miningProbesPerBase;

// reset(): single base, 12 probes
miningProbesPerBase = new int[]{SC2Data.INITIAL_PROBES};

// tick(): sum income across bases
for (int probesAtBase : miningProbesPerBase) {
    friendly.minerals += SC2Data.mineralIncomePerTick(probesAtBase);
}

// API: replaces setMiningProbes(int)
public void setMiningProbesPerBase(int... probesPerBase) {
    this.miningProbesPerBase = probesPerBase;
}
```

`setMiningProbes(int)` is removed — no backward-compatibility shim.

**`ReplayValidationHarness` — per-base counting from GT:**

Replace `countProbes(GameState)` with `countProbesPerBase(GameState)`:

```java
private static int[] countProbesPerBase(GameState state) {
    List<Building> nexuses = state.myBuildings().stream()
        .filter(b -> b.type() == BuildingType.NEXUS && b.isComplete())
        .toList();
    if (nexuses.isEmpty()) return new int[0];

    int[] counts = new int[nexuses.size()];
    for (Unit u : state.myUnits()) {
        if (u.type() != UnitType.PROBE) continue;
        int nearest = 0;
        double minDist = Double.MAX_VALUE;
        for (int i = 0; i < nexuses.size(); i++) {
            double dx = u.position().x() - nexuses.get(i).position().x();
            double dy = u.position().y() - nexuses.get(i).position().y();
            double d = Math.sqrt(dx * dx + dy * dy);
            if (d < minDist) { minDist = d; nearest = i; }
        }
        counts[nearest]++;
    }
    return counts;
}
```

Caller update: `emulated.setMiningProbesPerBase(countProbesPerBase(gtBefore))`.

**Approximation:** probes in transit (scouting, transferring) are assigned to their nearest nexus. This matches the existing approximation where `countProbes` counts ALL probes as mining.

### #147: Queued-unit completion loop propagation

**`PlayerState` — new field:**

```java
final Map<String, Long> buildingCompletionAtLoop = new HashMap<>();
```

Cleared in `clear()`.

**`EmulatedGame.startTraining` — store completion loop:**

```java
state.buildingCompletionAtLoop.put(buildingTag,
    absLoop + SC2Data.trainTimeInLoops(unitType));
```

**`EmulatedGame.drainBuildingQueues` — propagate loop:**

```java
long completionLoop = state.buildingCompletionAtLoop.getOrDefault(buildingTag, 0L);
state.buildingCompletionAtLoop.remove(buildingTag);
startTraining(buildingTag, next, state, completionLoop);
```

The propagated loop gives the next queued unit the correct sub-tick offset, so `loopOffset = completionLoop % LOOPS_PER_TICK` reflects when the building actually became free rather than assuming tick boundary (offset 0).

## Files changed

| File | Change |
|------|--------|
| `EmulatedGame.java` | Replace `miningProbes` with `miningProbesPerBase`; update `tick()`, `reset()`, API. Propagate completion loop in `startTraining`/`drainBuildingQueues`. |
| `PlayerState.java` | Add `buildingCompletionAtLoop` map; clear in `clear()`. |
| `ReplayValidationHarness.java` | Replace `countProbes` with `countProbesPerBase`; update caller. |
| `SC2DataTest.java` | No change — per-base tests are already correct. |
| `EmulatedGameTest.java` | Update `setMiningProbes(0)` → `setMiningProbesPerBase(0)`. Add queued-unit sub-tick precision test. |
| `ReplayValidationTest.java` | Run to verify improved divergence (if multi-base replays are in the corpus). |

## Out of scope

- AI expansion mining (EmulatedGame standalone) — tracked in #152
- Per-probe mining assignment (which specific mineral patch each probe mines) — not modelled
- Vespene multi-base — gas is already synced from GT (#148)

## Alternatives rejected

- **Even distribution `(int probeCount, int nexusCount)`** — wrong for replay validation. A 16/4 split produces 100 min/min less than the 10/10 that even distribution would model. Causes drift, not accuracy.
- **Income override on EmulatedGame** — introduces override/fallback pattern that obscures where income comes from. The per-base counts API is cleaner.

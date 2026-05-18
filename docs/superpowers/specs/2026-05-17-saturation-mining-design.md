# Saturation-Aware Probe Mining Model — Design Spec

**Issue:** #141
**Date:** 2026-05-17
**Epic:** epic-saturation-mining

## Problem

`EmulatedGame` uses a flat mining rate with no saturation cap:

```java
friendly.minerals += miningProbes * SC2Data.MINERALS_PER_PROBE_PER_TICK;
```

`miningProbes` is set by `ReplayValidationHarness` as `probeCount * LOOPS_PER_TICK` — a confusing unit (game-loop-equivalents, not probes). The flat model gives every probe the same rate regardless of how many are mining, meaning probe 1 and probe 20 earn identically. Real SC2 uses a three-tier saturation model: income diminishes as workers per patch increases.

**Impact:** `ReplayValidationTest` currently accepts `maxUnitDelta ≤ 2` because the emulated bank is always larger than GT, allowing units to be trained up to 1 tick early. The acceptance criterion for this issue is `firstUnitDivergenceTick == -1` — no unit count divergence at any tick over three minutes.

## Approach

Approach A: piecewise function in `SC2Data` (domain, plain Java). No new abstraction layer. Cleans up the `* LOOPS_PER_TICK` semantic mismatch in the harness. Empirical calibration after implementation to verify acceptance criterion; tier rates adjusted if needed.

## Design

### SC2Data — tier configuration and income function

Remove `MINERALS_PER_PROBE_PER_TICK`. Add:

```java
// Base geometry — change these to adapt to a different RTS economy
public static final int MINERAL_PATCHES_PER_BASE = 8;

// Per-worker-slot-per-patch rates. Length = max effective workers per patch.
// Each tier spans MINERAL_PATCHES_PER_BASE probes.
// Swap or extend this array to change the saturation curve.
public static final double[] MINERAL_TIER_RATES_PER_TICK = {
    50.0 / 60.0 * 22.0 / 22.4,   // first worker per patch  (~0.818 min/tick)
    25.0 / 60.0 * 22.0 / 22.4,   // second worker per patch (~0.409 min/tick)
     5.0 / 60.0 * 22.0 / 22.4,   // third worker per patch  (~0.082 min/tick)
};

public static double mineralIncomePerTick(int probeCount) {
    double income = 0;
    for (int tier = 0; tier < MINERAL_TIER_RATES_PER_TICK.length; tier++) {
        int tierStart    = tier * MINERAL_PATCHES_PER_BASE;
        int probesInTier = Math.min(Math.max(probeCount - tierStart, 0), MINERAL_PATCHES_PER_BASE);
        income += probesInTier * MINERAL_TIER_RATES_PER_TICK[tier];
    }
    return income;
}
```

**Configuration semantics:**
- `MINERAL_PATCHES_PER_BASE = 8` — one worker-slot per patch per tier; determines tier width
- `MINERAL_TIER_RATES_PER_TICK.length` — implicitly defines max effective workers per patch (currently 3 → cap at 24 probes)
- Adding a 4th tier: append an element. Removing tier 3: shorten the array. No structural code changes.

**Cap behaviour:** probes beyond `PATCHES_PER_BASE × TIER_RATES.length` (currently 24) contribute zero income. This matches real SC2 where a 4th worker per patch is completely unproductive.

### EmulatedGame — tick() and miningProbes semantics

`miningProbes` changes meaning from "probe-count × LOOPS_PER_TICK" to "actual probe count". In `tick()`:

```java
// Before:
friendly.minerals += miningProbes * SC2Data.MINERALS_PER_PROBE_PER_TICK;
// After:
friendly.minerals += SC2Data.mineralIncomePerTick(miningProbes);
```

`reset()` is unchanged — `miningProbes = SC2Data.INITIAL_PROBES` already stores probe count (12).

**Side-effect on mock mode:** `EmulatedEngine` (mock/emulated profiles) also calls `tick()`. Income at 12 probes drops from ~9.82 to ~8.18 min/tick (~17% reduction). This is more accurate. Any `EmulatedGameTest` assertions that hardcode mineral accumulation values will need updating.

### ReplayValidationHarness — probe count sync

Drop the `LOOPS_PER_TICK` multiplication:

```java
// Before:
emulated.setMiningProbes(countProbes(gtBefore) * LOOPS_PER_TICK);
// After:
emulated.setMiningProbes(countProbes(gtBefore));
```

The explicit probe-count sync from GT is retained — it ensures mining income matches the ground truth probe count regardless of any EmulatedGame unit-count drift.

### Calibration loop

After implementing:

1. Run `mvn test -Preport` — examine `firstUnitDivergenceTick`
2. If `-1`: acceptance criterion met — record empirical `maxMineralDelta` and use it to tighten the mineral assertion in `ReplayValidationTest`
3. If not `-1`: inspect the first diverging tick — if emulated bank is ahead of GT, income is still too high (lower tier 2 or tier 3 rate); if behind, too low (raise). Adjust and repeat.

## Testing

### New tests in `SC2DataTest` (plain JUnit, no CDI)

*(Spec named this class `SC2DataMiningTest`; tests were added to the existing `SC2DataTest` instead to keep domain tests consolidated.)*

Boundary cases for `mineralIncomePerTick`:

| Input | Behaviour |
|-------|-----------|
| 0 | returns 0.0 |
| 1 | 1 × TIER1 rate |
| 8 | 8 × TIER1 rate (full tier 1) |
| 9 | 8 × TIER1 + 1 × TIER2 (tier boundary) |
| 16 | 8 × TIER1 + 8 × TIER2 (full tier 2) |
| 17 | 8 × TIER1 + 8 × TIER2 + 1 × TIER3 |
| 24 | 8 × TIER1 + 8 × TIER2 + 8 × TIER3 (capacity) |
| 25 | same as 24 (cap enforced) |
| 100 | same as 24 |

### Updated: `ReplayValidationTest`

- `unitCountWithinTwoOfGroundTruthForThreeMinutes` → asserts `firstUnitDivergenceTick == -1`
- `mineralDeltaWithinToleranceForThreeMinutes` → upper bound tightened to empirical value from calibration run (replaces the current sanity-level `< 5000`)

### Existing tests that may need updating

Any `EmulatedGameTest` assertions that hardcode mineral accumulation based on the old flat rate will produce different values under the saturation model. These are identified and updated during implementation.

## Out of Scope

- **Multi-base mining:** `mineralIncomePerTick` assumes a single Nexus. Multi-base requires distributing probes across bases, which requires knowing the base count and probe assignment — not available in the current model. Future extension point: `mineralIncomePerTick(int probeCount, int nexusCount)`.
- **Vespene saturation:** 3 probes per geyser is the SC2 optimum, but vespene divergence is a separate concern (#TBD).
- **Race-specific patches:** Zerg/Terran bases have the same 8-patch geometry for minerals.

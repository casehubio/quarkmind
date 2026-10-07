# Cross-Patch Regression Suite — Design Spec

**Issue:** casehubio/quarkmind#371
**Branch:** issue-380-restoration-coverage-audit
**Date:** 2026-10-07

## Goal

Extend `DivergenceRegressionTest` to cover all 4 feature categories
(units, buildings, upgrades, economy) and run across multiple patch-era
datasets, catching regressions as the EmulatedGame evolves.

## Scope

**In scope:**
- Add economy category to `perCategoryAccuracyWithinThreshold()`
- Add cross-patch test method running against tournament datasets
- Set per-dataset, per-category baselines at measured values + 10% margin
- Calibration run to measure initial baselines per dataset
- Update CLAUDE.md

**Out of scope:**
- Improving EmulatedGame accuracy (tracked by separate issues)
- New markdown benchmark files (this is a pass/fail regression gate)
- Changing the existing `oracleDivergenceWithinBaseline()` test

## Architecture

### Existing test structure (unchanged)

`DivergenceRegressionTest` has two test methods:

1. `oracleDivergenceWithinBaseline()` — per-matchup unit/building deltas
   at 5-min checkpoint against oracle 118. Unchanged.
2. `perCategoryAccuracyWithinThreshold()` — per-category accuracy against
   oracle 118. Currently: units ≥30%, buildings ≥95%, upgrades ≥0%.
   **Extended:** add economy category.

### Changes

**1. Add economy to `perCategoryAccuracyWithinThreshold()`**

Add economy error tracking to the existing oracle loop. For each player
run, collect `TickSnapshot.groundTruthEconomy()` and
`TickSnapshot.emulatedEconomy()` at the 5-min checkpoint. Compute MAPE
across 4 core fields:

- `mineralsCurrent`
- `vespeneCurrent`
- `mineralsCollectionRate`
- `vespeneCollectionRate`

These 4 fields are the most stable economy indicators. The spending
fields (`mineralsUsedCurrentArmy`, etc.) have 1000%+ MAPE and are too
noisy for regression detection.

Add an `ECONOMY_MAPE_CEILING` threshold. Unlike the accuracy floors
(higher is better), MAPE is an error metric (lower is better), so the
assertion is `≤ ceiling` not `≥ floor`.

Check `TickSnapshot` to confirm `groundTruthEconomy()` and
`emulatedEconomy()` are available — these were added in #379.

**2. Add `crossPatchAccuracyWithinBaseline()`**

New test method that:

1. Uses `discoverDatasets()` to enumerate tournament datasets with tracker
   events (same list as `CrossPatchExtractionTest` minus ladder datasets)
2. For each dataset: samples up to 20 `.SC2Replay` files (sorted by name
   for determinism, take first 20)
3. For each replay: runs `ReplayValidationHarness.run()` for both players
   up to 5-min checkpoint
4. Collects per-category accuracy (units, buildings, upgrades) and economy
   MAPE per dataset
5. Asserts each metric stays within per-dataset baselines + margin

**Baseline structure:**

```java
record DatasetBaseline(double unitFloor, double buildingFloor,
                       double upgradeFloor, double economyMapeCeiling) {}

private static final Map<String, DatasetBaseline> CROSS_PATCH_BASELINES = Map.of(
    "AI Arena (4.9.3)",        new DatasetBaseline(0.30, 0.95, 0.0, 500.0),
    "HSC XXVII 2025",          new DatasetBaseline(...),
    "IEM PyeongChang 2018",    new DatasetBaseline(...),
    // ... per-dataset baselines measured by calibration run
);
```

Datasets not in the map are skipped with a log (new tournament packs
need a calibration run before they get thresholds).

**Calibration mode:** On the first run (no baselines yet), the test
prints measured values per dataset in a format that can be copy-pasted
into the baseline map. This is the same pattern used by
`GameLoopBenchmarkTest` — measure first, then lock in.

**Sample cap:** 20 replays per dataset keeps runtime under 5 minutes
for the cross-patch method. The oracle test (118 replays, full) remains
the primary regression gate; cross-patch is a coverage extension.

### TickSnapshot fields used

From `DivergenceReport.TickSnapshot` (extended in #379):

- `unitDelta()`, `buildingDelta()` — existing, used by delta test
- `groundTruthUnitsByType()`, `emulatedUnitsByType()` — existing, used
  by per-category test
- `groundTruthBuildingsByType()`, `emulatedBuildingsByType()` — existing
- `groundTruthUpgrades()`, `emulatedUpgrades()` — existing
- `groundTruthEconomy()`, `emulatedEconomy()` — added in #379, need
  verification that they return `PlayerEconomyStats` or equivalent with
  the 4 core fields

### Performance

- Oracle test (existing): ~2-3 minutes for 118 replays × 2 players
- Cross-patch test (new): ~3-5 minutes for ~100 replays (20 per dataset
  × 5 datasets) × 2 players
- Total: ~5-8 minutes — acceptable for `@Tag("report")`

### CLAUDE.md updates

- Update `DivergenceRegressionTest` description to mention economy
  category and cross-patch coverage
- Update the issue's acceptance criteria comment on GitHub

## Decisions

- **D1:** Baseline + margin thresholds (not aspirational ≥99%) — catches
  real regressions without permanent false failures
- **D2:** Sample 20 replays per dataset — balances cross-patch coverage
  against runtime cost

## References

- `DivergenceRegressionTest.java` — existing test being extended
- `DivergenceReport.TickSnapshot` — data model for per-tick comparisons
- `ReplayValidationHarness.java` — harness that runs EmulatedGame against replay
- `CrossPatchExtractionTest.java` — `discoverDatasets()` pattern
- `docs/benchmarks/emulated-game-accuracy-baseline.md` — baseline measurements
- `docs/benchmarks/oracle-accuracy-baseline.md` — extractor accuracy (reference)
- `OracleAccuracyBaselineTest.java` — economy MAPE calculation pattern
- casehubio/quarkmind#371 — focal issue
- casehubio/quarkmind#379 — TickSnapshot economy fields
- casehubio/quarkmind#366 — epic: Phase 2.5 reconstitution accuracy gate

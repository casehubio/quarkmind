# EmulatedGame Accuracy Baseline — Design Spec

**Issue:** #379 (child of Epic #366: Phase 2.5 Reconstitution Accuracy Gate)
**Branch:** `issue-379-emulatedgame-accuracy-baseline`
**Date:** 2026-10-07

## Problem

EmulatedGame's accuracy has only been measured via aggregate deltas (total unit count, total building count, minerals, vespene) against ReplaySimulatedGame ground truth. There is no per-type breakdown — we know "you're off by 25 units at 5 min" but not "you're missing 12 Marines and have 3 extra Zealots." Additionally, EmulatedGame produces `PlayerEconomyStats.EMPTY`, so economy accuracy beyond mineral/vespene totals has never been measured.

The existing `DivergenceBaselineReportTest` and `DivergenceRegressionTest` use the harness infrastructure but only at aggregate granularity. `OracleAccuracyBaselineTest` measures per-type accuracy but only for `StrippedReplayFeatureExtractor` — it never touches EmulatedGame.

## Approach

Extend the existing `ReplayValidationHarness` infrastructure to capture per-type data, add economy stat derivation to EmulatedGame, and create a new accuracy baseline test that measures EmulatedGame per-type accuracy against tracker event ground truth.

## Components

### 1. Extended TickSnapshot

Add per-type maps alongside existing aggregate fields:

```java
public record TickSnapshot(
    int tick,
    // existing aggregate fields unchanged
    int groundTruthUnits, int emulatedUnits,
    int groundTruthBuildings, int emulatedBuildings,
    int groundTruthMinerals, int emulatedMinerals,
    int groundTruthVespene, int emulatedVespene,
    // new per-type maps
    Map<UnitType, Integer> groundTruthUnitsByType,
    Map<UnitType, Integer> emulatedUnitsByType,
    Map<BuildingType, Integer> groundTruthBuildingsByType,
    Map<BuildingType, Integer> emulatedBuildingsByType,
    // new upgrade sets
    Set<String> groundTruthUpgrades,
    Set<String> emulatedUpgrades,
    // new economy stats
    PlayerEconomyStats groundTruthEconomy,
    PlayerEconomyStats emulatedEconomy
)
```

Existing convenience methods (`unitDelta()`, `buildingDelta()`, etc.) remain unchanged. New methods added for per-type access. The `DivergenceReport.Summary` gains per-type accuracy fields.

Backward compatibility: all existing test code that constructs `TickSnapshot` will need updating, but TickSnapshot is internal to the replay validation infrastructure — no external consumers.

### 2. ReplayValidationHarness extension

At each tick where a snapshot is recorded, the harness already has access to both `ReplaySimulatedGame` (ground truth) and `EmulatedGame` state. The extension:

- Extracts per-type unit counts from both `GameState.myUnits()` (emulated) and `ReplaySimulatedGame.snapshot()` (ground truth), grouped by `UnitType`
- Extracts per-type building counts from `GameState.myBuildings()`, grouped by `BuildingType`
- Extracts completed upgrade names from both sides
- Extracts `PlayerEconomyStats` from both sides (GT from ReplaySimulatedGame's tracker PlayerStats, emulated from the new derivation)

### 3. EmulatedGame economy stat derivation

EmulatedGame currently returns `PlayerEconomyStats.EMPTY` in `snapshot()`. We derive stats from existing tracked state:

| PlayerStats field | Derivation |
|---|---|
| `mineralsCurrent` | Already tracked: `friendly.minerals()` |
| `vespeneCurrent` | Already tracked: `friendly.vespene()` |
| `mineralsCollectionRate` | Delta minerals per 160-loop interval (matching SC2's PlayerStats sample rate) |
| `vespeneCollectionRate` | Delta vespene per 160-loop interval |
| `foodMade` | Sum of supply values for completed supply structures + starting supply |
| `foodUsed` | Sum of supply costs for alive units |
| `workersActiveCount` | Count of alive worker units (Probe/SCV/Drone) |
| `mineralsUsedCurrentArmy` | Cumulative minerals spent on army units (from intent execution) |
| `mineralsUsedCurrentEconomy` | Cumulative minerals spent on workers + expansions |
| `mineralsUsedCurrentTechnology` | Cumulative minerals spent on upgrades + tech buildings |
| `vespeneUsedCurrent*` | Same split for vespene |

Implementation: add a `EconomyTracker` helper class in `io.quarkmind.sc2.emulated` that accumulates spending categories as intents execute, and calculates rate fields from per-interval deltas. `EmulatedGame.snapshot()` calls `economyTracker.currentStats()` instead of returning `EMPTY`.

The derived stats will diverge from ground truth — that's the measurement goal. The flat mining model produces different collection rates than SC2's saturation curves, and spending categories may not perfectly match SC2's internal accounting.

### 4. EmulatedGameAccuracyBaselineTest

New `@Tag("report")` test modeled on `OracleAccuracyBaselineTest`:

- Runs `ReplayValidationHarness` over oracle replays (118, v4.9.3)
- Collects extended `TickSnapshot` data at checkpoints (1, 3, 5, 7 min)
- Produces per-category accuracy report:

**Unit accuracy** (tiered, matching OracleAccuracyBaselineTest):
- T1 Commanded: units produced via player commands
- T2 Auto-spawn: Larva, Interceptors
- T3 Combat: units created by combat (Broodlings, Locusts) — expected 0% from EmulatedGame

**Building accuracy:** per-type count match

**Upgrade accuracy:** set intersection (completed upgrades at each checkpoint)

**Economy accuracy:** MAPE per PlayerStats field at each checkpoint, matching OracleAccuracyBaselineTest's format

**Output:** Writes `docs/benchmarks/emulated-game-accuracy-baseline.md` (overwrites existing aggregate-only version). Stdout summary.

### 5. DivergenceRegressionTest update

Add per-category accuracy percentage thresholds:

```java
static final double UNIT_ACCURACY_THRESHOLD = 0.80;     // set from baseline
static final double BUILDING_ACCURACY_THRESHOLD = 0.70;  // set from baseline
static final double UPGRADE_ACCURACY_THRESHOLD = 0.95;   // set from baseline
```

Thresholds are initially set conservatively from the first baseline run, then tightened as EmulatedGame improves. Existing aggregate delta assertions remain alongside.

### 6. Root cause triage

The baseline report includes a triage section for each category where accuracy < 95%:

| Classification | Meaning | Example |
|---|---|---|
| Physics error | Wrong constant or formula | Train time off by 1 tick |
| Missing mechanic | Not modeled at all | Chrono Boost, MULE income |
| Known limitation | Documented, accepted | Flat mining rate |
| Extraction noise | GT-side artifact | ReplaySimulatedGame state mismatch |

The triage is written as a section in the baseline report, not as code assertions.

## File changes

| File | Change |
|---|---|
| `DivergenceReport.java` | Extend TickSnapshot with per-type maps, upgrade sets, economy stats |
| `ReplayValidationHarness.java` | Collect per-type data at snapshot points |
| `EmulatedGame.java` | Replace `EMPTY` economy stats with derived values |
| New: `EconomyTracker.java` | Accumulates spending categories and rate calculations |
| New: `EmulatedGameAccuracyBaselineTest.java` | Per-category accuracy report (`@Tag("report")`) |
| `DivergenceRegressionTest.java` | Add per-category accuracy percentage thresholds |
| `DivergenceBaselineReportTest.java` | Update TickSnapshot construction (if manually constructed) |
| `docs/benchmarks/emulated-game-accuracy-baseline.md` | Regenerated with per-type data |

## Testing strategy

- **Unit tests:** `EconomyTracker` tested in isolation with known intents and expected stats
- **Existing tests:** All existing `ReplayValidationHarness`-dependent tests must pass with extended TickSnapshot
- **Report test:** `EmulatedGameAccuracyBaselineTest` run manually via `-Preport` profile; output reviewed for correctness
- **Regression:** Updated `DivergenceRegressionTest` runs in `-Preport` profile with new accuracy thresholds

## Non-goals

- Fixing EmulatedGame physics (train times, mining model) — this issue measures, future issues fix
- Changing the mining model to saturation-based — separate issue
- Adding new categories not in PlayerStats — stick to existing 13 fields
- Cross-patch EmulatedGame accuracy — oracle replays only (v4.9.3)

## References

- `quarkmind-sc2/src/main/java/io/quarkmind/sc2/replay/ReplayValidationHarness.java` — existing harness
- `quarkmind-sc2/src/main/java/io/quarkmind/sc2/replay/DivergenceReport.java` — existing snapshot/report
- `quarkmind-sc2/src/main/java/io/quarkmind/domain/PlayerEconomyStats.java` — 13-field economy record
- `quarkmind-sc2/src/main/java/io/quarkmind/sc2/emulated/EmulatedGame.java` — physics engine
- `quarkmind-sc2/src/test/java/.../OracleAccuracyBaselineTest.java` — per-type accuracy pattern
- `quarkmind-sc2/src/test/java/.../DivergenceBaselineReportTest.java` — existing aggregate divergence
- `quarkmind-sc2/src/test/java/.../DivergenceRegressionTest.java` — existing regression thresholds
- `docs/benchmarks/oracle-accuracy-baseline.md` — StrippedReplayFeatureExtractor accuracy baseline
- `docs/benchmarks/emulated-game-divergence-baseline.md` — existing aggregate EmulatedGame baseline
- D1–D4 in `decisions.md` — approach, economy scope, regression style, economy model fidelity

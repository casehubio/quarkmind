# Design: CreepTumor and Special Building UnitInit Coverage (#325)

## Problem

The `StrippedReplayFeatureExtractor` misses several building types that appear in
oracle UnitInit data, and under-counts existing building types:

**Missing types (no extraction path):**

| Type | Oracle Count | Source | Gap |
|------|-------------|--------|-----|
| CreepTumor | 646 | Auto-spread from existing tumor | No BuildingType, no abilLink, no code path |
| CreepTumorQueen | 313 | Queen ability placement | No BuildingType, no abilLink, no code path |
| NydusCanal | 6 | Nydus Network ability | BuildingType exists but unmapped in buildingTypeToPythonName |
| OracleStasisTrap | 2 | Oracle ability | No BuildingType, no abilLink |
| AssimilatorRich | 2 | Rich geyser build | No BuildingType, Probe build maps to "Assimilator" only |

**Under-counted types (extraction path exists but incomplete):**

| Type | Oracle | Java | Coverage |
|------|--------|------|----------|
| SupplyDepot | 779 | 585 | 75% |
| Pylon | 630 | 516 | 82% |
| MissileTurret | 288 | 184 | 64% |
| Refinery | 356 | 293 | 82% |

## Architecture

The extractor pipeline: `CmdEvent → AbilityMapping.process() → ReplayCommand → handler`.

Three existing command paths:
- `BuildCommand → handleBuild()` — emits UnitInit + UnitDone (all buildings)
- `TrainCommand → handleTrain()` — emits UnitBorn (units) or UnitInit (warp-gated)
- `MorphCommand → handleMorph()` — emits UnitDied + UnitInit + UnitDone

**Decision D1:** Ability-placed structures route through existing `BuildCommand →
handleBuild()`. The semantic distinction between worker-built and ability-placed is
irrelevant for feature extraction — only event type and timing matter.

**Decision D2:** Auto-spread tumors use a synthesis step after the main extraction
loop. Each tumor spreads exactly once after maturation (SC2 mechanic — not repeated),
producing linear chain growth.

**Decision D3:** Building under-counting diagnosed first via coverage diagnostic,
then targeted fix for identified gaps.

## Design

### 1. BuildingType Enum Additions

Add to `BuildingType.java`:

```
CREEP_TUMOR, CREEP_TUMOR_QUEEN, ORACLE_STASIS_TRAP, ASSIMILATOR_RICH
```

### 2. buildingTypeToPythonName() Updates

Add cases in `StrippedReplayFeatureExtractor.buildingTypeToPythonName()`:

| BuildingType | Python Name | Notes |
|---|---|---|
| CREEP_TUMOR | "CreepTumor" | |
| CREEP_TUMOR_QUEEN | "CreepTumorQueen" | |
| NYDUS_CANAL | "NydusCanal" | Existing enum, was unmapped |
| ORACLE_STASIS_TRAP | "OracleStasisTrap" | |
| ASSIMILATOR_RICH | "AssimilatorRich" | |
| LURKER_DEN | "LurkerDenMP" | Name forced by oracle data format |

Convert `default -> null` to explicit `case UNKNOWN -> null` per protocol
`enum-switch-exhaustive-required`. This ensures any future enum additions trigger
a compile error rather than silently returning null.

### 3. SC2Data Updates — All BuildingType Switches

Adding new BuildingType values requires updating **every** switch over BuildingType
in SC2Data to maintain protocol compliance. No `default ->` arms may remain.

**Build times** (`buildTimeInLoops`):

| BuildingType | Build Time (loops) | Source |
|---|---|---|
| CREEP_TUMOR | 224 | ~10s at Faster — calibrate from oracle |
| CREEP_TUMOR_QUEEN | 224 | Same as CreepTumor |
| ORACLE_STASIS_TRAP | 67 | ~3s — calibrate from oracle |
| ASSIMILATOR_RICH | 480 | Same as ASSIMILATOR |

**Complete SC2Data values for new types:**

| Method | CREEP_TUMOR / CREEP_TUMOR_QUEEN | ORACLE_STASIS_TRAP | ASSIMILATOR_RICH |
|---|---|---|---|
| mineralCost | 0 (energy-only: 25 Queen energy) | 0 (energy-only: 50 Oracle energy) | 75 (same as Assimilator) |
| maxBuildingHealth | 50 | 30 | 450 (same as Assimilator) |
| buildingRadius | 0.5f | 0.5f | 1.5f (same as Assimilator) |
| sightRange | 0 (burrowed) | 0 | 9 (same as Assimilator) |
| supplyBonus | 0 | 0 | 0 |
| techTier | empty | empty | empty |
| isBase | false | false | false |
| isProductionBuilding | false | false | false |

**Scope:** Enumerate all existing `default`-armed switches over `BuildingType` in
SC2Data and the extractor. Each must be converted to explicit cases for ALL values
including UNKNOWN, NYDUS_CANAL, LURKER_DEN, and the four new types.

### 4. AbilityMapping — abilLink Discovery and Dispatch

**Discovery phase:** Write a diagnostic test (`CreepTumorAbilityDiscoveryTest`)
that correlates CmdEvent abilLinks with oracle UnitInit events for the target
building types. Pattern: for each oracle UnitInit of type CreepTumor/NydusCanal/
OracleStasisTrap, find nearby CmdEvents (within ±50 loops) and correlate abilLinks.
Follow the existing `AbilityDiscoveryCalibrationTest` pattern.

**Dispatch phase:** Add constants and dispatch cases in
`AbilityMapping.dispatchHuman()` using the existing `buildCommand()` helper
(which extracts target point, guards against null, and constructs the full
3-arg BuildCommand with scaled coordinates):

```java
// New abilLink constants (values TBD from diagnostic)
static final int ABIL_QUEEN_CREEP_TUMOR = ???;
static final int ABIL_NYDUS_SPAWN = ???;
static final int ABIL_ORACLE_STASIS_WARD = ???;

// In dispatchHuman():
case ABIL_QUEEN_CREEP_TUMOR -> buildCommand(loop, "CreepTumorQueen", event);
case ABIL_NYDUS_SPAWN -> buildCommand(loop, "NydusCanal", event);
case ABIL_ORACLE_STASIS_WARD -> buildCommand(loop, "OracleStasisTrap", event);
```

**AssimilatorRich:** The Probe build index 2 currently maps to "Assimilator".
Distinguishing AssimilatorRich requires knowing whether the geyser is a rich
geyser — information not available in the CmdEvent. Two options:
- Map index 2 to "Assimilator" always and accept the 2-event discrepancy
- Map a separate abilCmdIndex if rich geysers use a different one (to be verified
  by diagnostic)

### 5. Auto-Spread CreepTumor Synthesis

**SC2 mechanic:** Each creep tumor can spread **exactly once** after maturation.
After spreading, the tumor remains alive (generating creep) but cannot spread
further. Growth is a linear chain:

1. Queen places CreepTumorQueen → matures → spreads once → child CreepTumor
2. Child matures → spreads once → grandchild CreepTumor
3. Chain continues until game end or tumor killed

The oracle ratio (646 auto-spread / 313 Queen-placed ≈ 2.1) confirms short linear
chains, not exponential growth.

**Synthesis algorithm:**

```java
private int synthesizeCreepTumorSpread(int playerId, List<SyntheticEvent> events,
                                       int tagCounter, int gameLength) {
    // 1. Collect all CreepTumor/CreepTumorQueen UnitDone events from the event list
    // 2. Sort seed tumors by game loop
    // 3. Walk timeline with a priority queue of pending spread events:
    //    - When a tumor matures (UnitDone loop), schedule ONE spread at maturation + SPREAD_DELAY
    //    - When a spread fires: emit UnitInit + UnitDone for new CreepTumor,
    //      schedule the NEW tumor's spread at its maturation + SPREAD_DELAY
    //    - Mark the parent tumor as "has_spread = true" (no further spreads)
    // 4. Cap total active tumors per player at MAX_ACTIVE_TUMORS
    // 5. Stop at gameLength
}
```

Constants (calibrated from oracle data):
- `SPREAD_DELAY` — loops between maturation and the single spread event (TBD,
  calibrated from oracle UnitInit timing gaps)
- `MAX_ACTIVE_TUMORS` — per-player cap (TBD from oracle max counts)
- Tumor maturation time: same as `CREEP_TUMOR` build time

**Placement in extract():** Inside the per-player loop, **inside** the existing
`if (elapsedLoops != null && elapsedLoops > 0)` guard, after `generatePlayerStats`:

```java
if (elapsedLoops != null && elapsedLoops > 0) {
    generatePlayerStats(playerId, state, syntheticEvents, elapsedLoops);
    tagCounter = synthesizeCreepTumorSpread(playerId, syntheticEvents,
                                             tagCounter, elapsedLoops);
}
```

**Direct event emission (intentional):** The synthesis method creates
`SyntheticEvent` objects directly without going through `handleBuild()`. This is
correct because auto-spread tumors:
- Cost zero minerals/gas (energy-free autonomous spread)
- Are not production buildings
- Don't consume workers
- Don't need tracking in `productionBuildingCounts` or `trackedBuildings`

If `handleBuild()` gains new externally-visible side effects in future issues,
revisit whether synthesis should route through it.

**Design boundary note:** This is the first simulation-like logic in the extractor.
The extractor's role is reconstructing events that stripped replays lack — auto-spread
tumors are an event gap, not a simulation. If the model grows complex (position-
dependent spreading, creep coverage), extract it to a dedicated
`CreepTumorSynthesizer` class.

### 6. Extractor Side-Effect Guards

**ZERG_BUILDINGS:** Ability-placed Zerg buildings (CreepTumorQueen, NydusCanal,
auto-spread CreepTumor) must NOT be added to `ZERG_BUILDINGS`. That set triggers
a Drone UnitDied event in `handleBuild()`, which is correct for drone-built
buildings but wrong for ability-placed ones (no drone is consumed).

**GAS_BUILDINGS:** Add "AssimilatorRich" to `GAS_BUILDINGS` set so that rich geyser
income is tracked correctly in `applyEventToEconomy()`.

### 7. Building Under-Counting Diagnostic

Write `BuildingCoverageDiagnosticTest` (tagged `@Tag("diagnostic")`) that:

1. Iterates oracle replays
2. Counts oracle UnitInit events per building type
3. Runs Java extractor on the corresponding stripped replay
4. Counts Java UnitInit events per building type
5. Prints per-type divergence with percentage
6. For types below 90% coverage, correlates oracle events with CmdEvents to
   identify which abilLinks/abilCmdIndex values produce the missing events

Root cause fixes applied based on diagnostic findings. Likely causes:
- Additional abilCmdIndex values not mapped (e.g., Terran add-ons built via
  different index than the main building)
- CmdUpdateTargetPointEvent not handled for building re-placement

### 8. Protocol Compliance

- **All** switch expressions over `BuildingType` must enumerate every value
  explicitly — no `default ->` arms (protocol `enum-switch-exhaustive-required`)
- This includes `buildingTypeToPythonName()`, all SC2Data methods, and
  `gasCostForBuilding()` in the extractor
- New switch expressions introduced by this work must also be exhaustive

## Testing

| Test | Type | What it validates |
|------|------|-------------------|
| CreepTumorAbilityDiscoveryTest | @Tag("diagnostic") | Discovers abilLinks for ability-placed structures |
| BuildingCoverageDiagnosticTest | @Tag("diagnostic") | Identifies building under-counting root causes |
| AbilityMappingTest (extended) | Unit | New dispatch cases return correct BuildCommand |
| StrippedReplayFeatureExtractorTest (extended) | Unit | buildingTypeToPythonName covers all new types |
| CreepTumorSynthesisTest (new) | Unit | Single-spread chain model produces correct events |
| StrippedReplayValidationTest | @Tag("report") | Oracle vs Java divergence report — run before and after |

## Acceptance Criteria (from issue)

- [ ] CreepTumor and CreepTumorQueen appear in UnitInit output
- [ ] Existing building under-counting reduced (each type within 10% of oracle)
- [ ] NydusCanal, OracleStasisTrap, AssimilatorRich recognized as building types

## Work Order

1. **Diagnostic phase** — discover abilLinks, diagnose under-counting
2. **Enum + mapping** — BuildingType additions, buildingTypeToPythonName, SC2Data
   (all switch expressions updated, no default arms)
3. **Extractor guards** — GAS_BUILDINGS update, ZERG_BUILDINGS exclusion documented
4. **AbilityMapping dispatch** — wire abilLinks to BuildCommand via buildCommand() helper
5. **Auto-spread synthesis** — single-spread chain model, calibrated from oracle
6. **Under-counting fixes** — based on diagnostic findings
7. **Validation** — run StrippedReplayValidationTest, verify acceptance criteria

## References

- StrippedReplayFeatureExtractor.java:326 (handleBuild), :269 (handleTrain),
  :160 (extract loop), :630 (GAS_BUILDINGS), :790 (gasCostForBuilding)
- AbilityMapping.java:420 (dispatchHuman), :161-189 (build maps),
  :462 (buildCommand helper)
- BuildingType.java — existing enum
- SC2Data.java:254 (buildTimeInLoops), :759 (mineralCost), :700 (maxBuildingHealth),
  :1048 (buildingRadius), :1021 (sightRange)
- AbilityDiscoveryCalibrationTest — pattern for abilLink discovery
- AbilityMappingCoverageDiagnosticTest — pattern for coverage diagnostics
- Protocol: enum-switch-exhaustive-required (PP-20260913-4d73d1)
- Protocol: extractor-separate-from-simulated-game (PP-20260528-612dee)
- Issue #325, epic #318
- Design review: issue-325-special-buildings-20261001-110556 (12 findings addressed)

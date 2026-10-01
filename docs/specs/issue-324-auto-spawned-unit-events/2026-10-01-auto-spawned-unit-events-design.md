# Auto-Spawned Unit Event Synthesis — Design Spec

**Issue:** casehubio/quarkmind#324
**Branch:** issue-324-auto-spawned-unit-events
**Date:** 2026-10-01

## Problem

The `StrippedReplayFeatureExtractor` reconstructs tracker events from
stripped ladder replays (151K+) for the classifier training pipeline.
Auto-spawned units — created by timers, triggers, or abilities rather
than explicit player commands — account for ~13,000 missing UnitBorn
events across 118 oracle replays. These units have no CmdEvent in
stripped replays (or, in MULE's case, an ability CmdEvent not yet mapped).

### Scope

Three feasible high-volume types:

| Type | Missing | Mechanism | Synthesis approach |
|------|---------|-----------|-------------------|
| Larva | 9,690 | Hatchery auto-spawn timer (max 3/base) | Timer-based post-loop |
| MULE | 623 | Orbital Command calldown ability | Command-based (AbilityMapping) |
| Interceptor | 322 | Carrier auto-build (max 8) | Timer-based post-loop |

### Permanent gaps

| Type | Missing | Reason infeasible |
|------|---------|-------------------|
| Broodling + BroodlingEscort | 1,726 | BroodLord auto-attacks produce no CmdEvent; no spatial data in stripped replays to approximate combat timing |
| AdeptPhaseShift | 197 | Shade ability; low volume, no clear classifier value |
| LocustMP + Precursor | 298 | SwarmHost periodic ability; would need similar timer logic for 298 events |
| InfestedTerransEgg | 146 | Infestor ability; low volume |
| KD8Charge | 81 | Reaper grenade; transient combat unit |
| Changeling variants | ~20 | Overseer ability; extremely low volume |

### Acceptance criteria reconciliation

Issue #324 is titled with four types but acceptance criteria require
"at least Larva and MULE implemented." This spec delivers three types
(Larva, MULE, Interceptor) and documents Broodling as a permanent gap
(infeasible, not deferred). All acceptance criteria are addressable:

- "Larva auto-spawn logic produces events within 20% of oracle count" — addressed (with Inject Larva prerequisite)
- "MULE calldown detection captures ability commands" — addressed
- "Design decision documented: which types are worth synthesizing vs permanent gaps" — this spec

## Architecture

Two synthesis mechanisms, matching the nature of each unit type.

### MULE — command-based dispatch

MULE calldown is a CmdEvent with a specific abilLink. It follows the
established pattern for ability-based dispatch:

1. Discover the MULE calldown abilLink via diagnostic test (correlate
   CmdEvents with oracle UnitBorn("MULE") across 118 replays)
2. Add `ABIL_MULE_CALLDOWN` constant to `AbilityMapping`
3. Wire into `dispatchHuman()` as a new case returning
   `IntentCommand(TrainIntent(UnitType.MULE))`
4. The existing `handleTrain()` in `StrippedReplayFeatureExtractor`
   produces the UnitBorn event — no extractor changes needed for MULE
   itself, only adding "MULE" → "MULE" to `UNIT_PYTHON_NAMES`

This matches the wiring pattern for WarpGate warp-in (abilLink 214),
Archon merge (267), and all morph commands.

**MULE train time:** MULE appears instantly on calldown (train time = 0).
The calldown loop IS the birth loop. `SC2Data.trainTimeInLoops(MULE)`
should return 0. Verify against oracle data.

### Larva — timer-based post-loop synthesis

Inline post-loop method in `extract()`, following `emitWarpGateAutoMorph()`.

**Prerequisites:**
- Larva spawn interval constant calibrated from oracle data
- Inject Larva abilLink discovered (prerequisite, not deferred — see
  Inject Larva section below)

**Starting Hatchery handling:**
`initStartingBuildings()` currently populates `productionBuildingCounts`
but does NOT add a `TrackedBuilding` entry. The starting Hatchery must
be added to `trackedBuildings` with `doneLoop = 0` (already complete at
game start). Without this, the post-loop synthesis would miss all Larva
from the main base — the most important Larva-producing base.

**State tracking (during main loop):**
- `PlayerState` gains a `larvaConsumptionLoops: List<Long>` field —
  an ordered list of game loops when Larva were consumed by train
  commands. A simple counter is insufficient because the post-loop
  synthesis must interleave consumption events chronologically with
  spawn events to compute the running count at any point in time.
- Each Zerg train command from a Larva-producing abilLink (193) appends
  the command's game loop to `larvaConsumptionLoops`.
- Consumption tracking is global (not per-base). The tag namespace
  mismatch between replay selection tags (e.g. `"r-42-0"`) and synthetic
  `TrackedBuilding` tags (sequential integers from `tagCounter`) makes
  per-base consumption tracking infeasible — there is no mapping between
  the selection state's actual replay tag and the synthetic building tag.
  Global tracking is sufficient for the 20% total-count accuracy target.

**Post-loop synthesis (`emitAutoSpawnedLarva`):**
- For each Hatchery/Lair/Hive in `trackedBuildings` (including the
  starting Hatchery added in `initStartingBuildings()`):
  - Starting at `completionLoop + LARVA_SPAWN_INTERVAL`
  - Emit UnitBorn("Larva") every `LARVA_SPAWN_INTERVAL` loops
  - Track running count of auto-spawned Larva at this base (per-base cap)
  - Pause spawning when count reaches 3 (the per-base auto-spawn cap)
  - Walk `larvaConsumptionLoops` chronologically: each consumption
    decrements the global running count, which may re-enable spawning
    at bases that were capped
  - Stop at game end loop
- Hatchery→Lair→Hive morphs do NOT interrupt Larva spawning. The
  original Hatchery entry in `trackedBuildings` is used throughout —
  the morph produces UnitDied/UnitInit events but doesn't affect Larva
  production timing or capacity.

**Inject Larva distinction (PREREQUISITE):**
In competitive play, Inject Larva (Queen ability) produces 3 Larva per
inject every ~29 seconds — roughly equal to or exceeding auto-spawned
Larva production. Injected Larva bypass the 3-per-base auto-spawn cap.
Without distinguishing them, the auto-spawn model produces fundamentally
wrong counts.

The Inject Larva abilLink MUST be discovered as part of the
`AutoSpawnCalibrationTest` diagnostic test, BEFORE implementing Larva
synthesis. The calibration test correlates oracle UnitBorn("Larva")
events with CmdEvents to identify which abilLink corresponds to Inject.
Once discovered, Inject commands are excluded from auto-spawn counting
(they produce their own UnitBorn events via a separate mechanism in
the main loop, or are tracked as a distinct count).

If the abilLink is undiscoverable, the Larva 20% accuracy target is
revised to acknowledge Inject Larva as a source of systematic error.

**Timer constant:** `LARVA_SPAWN_INTERVAL` — calibrated from oracle
replays via `AutoSpawnCalibrationTest`. Community value ~11 seconds
(~247 loops at 22.4 loops/second), but must be measured as integer
game loops from replay ground truth per protocol PP-20260522-572156.

### Interceptor — timer-based post-loop synthesis

Inline post-loop method in `extract()`.

**Post-loop synthesis (`emitAutoSpawnedInterceptor`):**
- For each Carrier UnitBorn event in the main-loop output:
  - Starting at `carrierBirthLoop + INTERCEPTOR_BUILD_TIME`
  - Emit UnitBorn("Interceptor") every `INTERCEPTOR_BUILD_TIME` loops
  - Cap at 8 per Carrier (the maximum Interceptor capacity)
  - Stop at game end loop

**Bidirectional divergence caveat:** Stripped replays lack death events,
causing two types of divergence:
- **Over-count**: Interceptors that die in combat are not subtracted
  from the running count, so the cap of 8 may not be reached when it
  should have been (dead Interceptors "block" new builds in reality)
- **Under-count**: Carriers that lose Interceptors and rebuild them
  produce more than 8 UnitBorn events total in the oracle; the cap at 8
  per Carrier prevents synthesizing these rebuilds

The net effect depends on the game. Acceptable for composition detection
— the classifier sees "Interceptors were produced" rather than an exact
count. The validation test will quantify both directions of divergence.

**Timer constant:** `INTERCEPTOR_BUILD_TIME` — calibrated from oracle
replays. Community value ~9 seconds (~202 loops). Must be measured from
replay ground truth.

### Event types

All three types emit UnitBorn (not UnitInit+UnitDone):
- Larva: instant appearance when timer fires
- MULE: instant appearance on calldown
- Interceptor: instant appearance when build timer completes

Verify against oracle event types in the calibration test. If oracle
uses a different event type for any of these (e.g. UnitInit for MULE),
adjust accordingly.

### Tag generation

Auto-spawned units use the existing `tagCounter` mechanism in
`StrippedReplayFeatureExtractor`. Each synthetic UnitBorn increments
the tag counter and generates a unique `unitTagIndex`. No new tag
prefix needed — these are synthetic events within the same extraction
pipeline, not a new replay source (protocol PP-20260528-d9f967 governs
replay source prefixes, not intra-extractor synthesis).

## Classifier Integration

### Python classifier pipeline

`sc2egset_extractor.py`: append `"Larva"`, `"MULE"`, `"Interceptor"` to
the `UNITS` list. Ordering matters — these must be the last three
entries to maintain backward compatibility of existing indices 0–52.

### Java FeatureIndexMaps

- `FeatureIndexMaps.N_UNITS`: increment from 53 to 56
- `FeatureIndexMaps.buildUnitIndex()`: add entries:
  - `UnitType.LARVA → 53`
  - `UnitType.MULE → 54`
  - `UnitType.INTERCEPTOR → 55`
- Index ordering MUST match the Python UNITS list ordering exactly

### UnitType enum and UNIT_PYTHON_NAMES

- Add `UNIT_PYTHON_NAMES` entries:
  - `UnitType.MULE → "MULE"` (MULE already in UnitType enum)
  - `UnitType.INTERCEPTOR → "Interceptor"` (INTERCEPTOR already in enum)
  - `UnitType.LARVA → "Larva"` (LARVA must be added to UnitType enum)

### SC2Data exhaustive maps

Adding `UnitType.LARVA` to the enum triggers the exhaustive validation
in SC2Data's static initializer. Required entries:

| Map | LARVA value | Rationale |
|-----|-------------|-----------|
| `UNIT_TRAIN_TIMES` | 0 | Larva don't train |
| `UNIT_COSTS` | (0, 0, 0) | Zero minerals, gas, supply |
| `UNIT_DEFENSES` | (25, 10, 0) | 25 HP, 10 shield? (verify), 0 armour |
| `UNIT_ATTRIBUTES` | {Light, Biological} | Standard |
| `UNIT_COMBAT_STATS` | (0, 0, 0) | No attack |
| `UNIT_SIGHT_RANGES` | 5 | Minimal sight |
| `UNIT_SPEEDS` | 0 | Immobile |

Exact values should be verified against SC2 game data. These are
non-critical for the classifier (Larva stats don't affect strategy
classification), but must be present to satisfy the exhaustive check.

**Note:** `INTERCEPTOR` and `MULE` are already in the UnitType enum and
already have SC2Data entries — no changes needed for them.

**Pre-existing gap:** `Battlecruiser` is in `UNIT_PYTHON_NAMES` but
absent from the Python UNITS list and `buildUnitIndex()`. This issue
does not fix it; Battlecruiser can be added at index 56+ in a future
change.

**Breaking change:** N_UNITS dimension change means existing trained
models are incompatible. Acceptable at pre-release stage.

## Testing

### Diagnostic tests (@Tag("diagnostic"))

**MuleAbilLinkDiscoveryTest:**
- Scan oracle replays for Terran players with Orbital Command
- Correlate CmdEvents (by abilLink) with oracle UnitBorn("MULE")
  events within a tight time window
- Print discovered abilLink and confidence

**AutoSpawnCalibrationTest:**
- Discover Larva spawn interval: correlate oracle UnitBorn("Larva")
  events with Hatchery/Lair/Hive completion times, compute inter-birth
  intervals, take modal value across replays
- Discover Inject Larva abilLink: correlate CmdEvents with oracle
  UnitBorn("Larva") clusters (3 Larva at once = Inject), identify the
  triggering abilLink. This is a prerequisite for Larva synthesis.
- Discover Interceptor build time: correlate oracle
  UnitBorn("Interceptor") events with Carrier birth times, compute
  inter-birth intervals, take modal value
- Print all calibrated constants

### Unit tests (plain JUnit, no Quarkus)

- `AbilityMappingTest` (extend): MULE calldown abilLink produces
  IntentCommand with TrainIntent(MULE)
- `StrippedReplayFeatureExtractorTest` (extend):
  - Starting Hatchery: Zerg player's starting Hatchery is in
    trackedBuildings with doneLoop=0
  - Larva auto-spawn: Hatchery completes → Larva UnitBorn events
    appear at calibrated intervals, capped at 3 per base
  - Larva consumption: train command records consumption loop, spawning
    resumes when running count drops below 3
  - Larva with multiple bases: two Hatcheries each independently
    spawn and cap at 3
  - Interceptor auto-build: Carrier born → Interceptor UnitBorn events
    at calibrated intervals, capped at 8
  - MULE: MULE calldown CmdEvent → UnitBorn("MULE") in output

### Validation test (@Tag("report"))

- Extend `StrippedReplayValidationTest` to include Larva, MULE,
  Interceptor in the per-type divergence report (these types already
  appear on the oracle side of the report — the change adds Java-side
  counts to compare against)
- Add per-type threshold assertion: Larva within 20% of oracle count
  (per issue #324 acceptance criteria)
- MULE and Interceptor: report divergence without hard threshold
  initially (MULE should be close; Interceptor divergence is
  bidirectional and expected)

## Implementation Order

1. **Diagnostic: MULE abilLink discovery** — MuleAbilLinkDiscoveryTest
2. **Diagnostic: auto-spawn calibration** — AutoSpawnCalibrationTest
   (Larva interval, Inject Larva abilLink, Interceptor build time)
3. **MULE synthesis** — AbilityMapping.dispatchHuman() + UNIT_PYTHON_NAMES
4. **Larva synthesis** — starting Hatchery in trackedBuildings,
   consumption tracking in PlayerState, emitAutoSpawnedLarva()
5. **Interceptor synthesis** — emitAutoSpawnedInterceptor()
6. **Classifier integration** — UnitType.LARVA enum + SC2Data entries,
   Python UNITS list, Java FeatureIndexMaps
7. **Validation** — extend StrippedReplayValidationTest

Steps 1-2 are diagnostic (discover constants). Steps 3-5 are
implementation (one per type). Steps 6-7 close the loop.

## Files Modified

| File | Change |
|------|--------|
| `quarkmind-sc2/.../replay/AbilityMapping.java` | Add ABIL_MULE_CALLDOWN, dispatchHuman() case |
| `quarkmind-sc2/.../replay/StrippedReplayFeatureExtractor.java` | Add MULE/Larva/Interceptor to UNIT_PYTHON_NAMES, initStartingBuildings() adds Hatchery to trackedBuildings, PlayerState.larvaConsumptionLoops, emitAutoSpawnedLarva(), emitAutoSpawnedInterceptor() |
| `quarkmind-sc2/.../domain/SC2Data.java` | Add LARVA_SPAWN_INTERVAL, INTERCEPTOR_BUILD_TIME, trainTimeInLoops(MULE)=0, exhaustive map entries for LARVA |
| `quarkmind-sc2/.../domain/UnitType.java` | Add LARVA enum value |
| `quarkmind-sc2/.../domain/FeatureIndexMaps.java` | N_UNITS 53→56, buildUnitIndex() entries for LARVA/MULE/INTERCEPTOR |
| `quarkmind-classifier/src/sc2egset_extractor.py` | Append Larva, MULE, Interceptor to UNITS |

### New test files

| File | Type |
|------|------|
| `quarkmind-sc2/src/test/.../replay/MuleAbilLinkDiscoveryTest.java` | @Tag("diagnostic") |
| `quarkmind-sc2/src/test/.../replay/AutoSpawnCalibrationTest.java` | @Tag("diagnostic") |

### Modified test files

| File | Change |
|------|--------|
| `quarkmind-sc2/src/test/.../replay/AbilityMappingTest.java` | MULE dispatch test |
| `quarkmind-sc2/src/test/.../replay/StrippedReplayFeatureExtractorTest.java` | Starting Hatchery, Larva, Interceptor, MULE synthesis tests |
| `quarkmind-sc2/src/test/.../replay/StrippedReplayValidationTest.java` | Auto-spawn types in divergence report + Larva threshold |

## Risks and Mitigations

| Risk | Mitigation |
|------|-----------|
| MULE abilLink differs in human vs bot replays | Diagnostic test discovers from human replay data |
| Larva spawn interval varies by game patch | Modal analysis across 118 replays will surface variance |
| Inject Larva abilLink undiscoverable | Calibration test is a prerequisite; if undiscoverable, revise Larva accuracy target |
| Interceptor divergence (bidirectional) | Validation report quantifies both over- and under-counting |
| Tag namespace mismatch (replay vs synthetic) | Global consumption model avoids per-base tag correlation |
| Starting Hatchery missing from trackedBuildings | Explicitly added in initStartingBuildings() |
| SC2Data exhaustive maps break on new LARVA | All required entries enumerated in spec |
| N_UNITS dimension change breaks trained models | Pre-release stage, no deployed models |

## References

- `quarkmind-sc2/src/main/java/io/quarkmind/sc2/replay/StrippedReplayFeatureExtractor.java` — main extraction pipeline, SyntheticEvent, PlayerState, emitWarpGateAutoMorph() precedent, initStartingBuildings()
- `quarkmind-sc2/src/main/java/io/quarkmind/sc2/replay/AbilityMapping.java` — command dispatch, abilLink constants, dispatchHuman()
- `quarkmind-sc2/src/main/java/io/quarkmind/domain/SC2Data.java` — timing constants, exhaustive validation maps
- `quarkmind-sc2/src/main/java/io/quarkmind/domain/UnitType.java` — unit type enum
- `quarkmind-sc2/src/main/java/io/quarkmind/domain/FeatureIndexMaps.java` — N_UNITS, buildUnitIndex()
- `quarkmind-sc2/src/test/java/io/quarkmind/sc2/replay/StrippedReplayValidationTest.java` — oracle comparison framework
- `quarkmind-sc2/src/test/java/io/quarkmind/sc2/mock/AbilityDiscoveryCalibrationTest.java` — abilLink discovery pattern
- `quarkmind-sc2/src/test/java/io/quarkmind/sc2/mock/SC2TrainTimeCalibrationTest.java` — timing calibration pattern
- `quarkmind-classifier/src/sc2egset_extractor.py` — UNITS list, UNIT_IDX
- PP-20260522-572156 — train-times-require-calibration protocol
- PP-20260528-612dee — extractor-separate-from-simulated-game protocol
- PP-20260528-d9f967 — replay-tag-prefix-per-source protocol
- casehubio/quarkmind#324 — issue body (unit count table, acceptance criteria)
- casehubio/quarkmind#318 — parent epic (StrippedReplayFeatureExtractor oracle coverage)

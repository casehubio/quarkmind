# Design: Synthesize morph-based unit events (#328)

## Problem

`StrippedReplayFeatureExtractor` has full morph emission infrastructure (`handleMorph()` with three-way Archon/building/standard split) but `AbilityMapping` only knows one morph abilLink (`ABIL_ARCHON_MERGE=267`). No MorphCommands are produced for real replay data except Archon merges. Secondary gaps: no morph spending tracking, uncalibrated morph times (instant UnitBorn instead of timed), no multiplication for batch morphs.

## Scope

Full vertical slice through the replay feature extraction pipeline:

| Layer | Change |
|---|---|
| Discovery | New `discoverMorphAbilLinks()` in `AbilityDiscoveryCalibrationTest` |
| Mapping | New morph abilLink constants + dispatch cases in `AbilityMapping` |
| Emission | Timed UnitInit+UnitDone for unit morphs, morph spending, selection-based multiplication |
| Calibration | Calibrate morph times in `SC2Data` from replay data |

### Morph inventory

| Category | Source → Target | Multiplication | Morph time |
|---|---|---|---|
| Unit (Zerg) | Zergling → Baneling | Selection-based | ~448 loops (20s) |
| Unit (Zerg) | Roach → Ravager | Selection-based | ~269 loops (12s) |
| Unit (Zerg) | Hydralisk → Lurker | Selection-based | ~536 loops (24s) |
| Unit (Zerg) | Corruptor → BroodLord | Selection-based | ~804 loops (36s) |
| Unit (Zerg) | Overlord → Overseer | Selection-based | ~269 loops (12s) |
| Building upgrade | CC → OrbitalCommand | Always 1 | 550 loops (already in SC2Data) |
| Building upgrade | CC → PlanetaryFortress | Always 1 | 660 loops (already in SC2Data) |
| Building upgrade | Hatchery → Lair | Always 1 | 1254 loops (already in SC2Data) |
| Building upgrade | Lair → Hive | Always 1 | 1254 loops (already in SC2Data) |
| Building upgrade | Spire → GreaterSpire | Always 1 | 1562 loops (already in SC2Data) |

Building morph times are already in `SC2Data.buildTimeInLoops()`. Unit morph times currently have placeholder 672-loop values — need calibration.

## Component Design

### 1. Discovery — `AbilityDiscoveryCalibrationTest.discoverMorphAbilLinks()`

New method in the existing calibration test. Same pattern as `discoverUnitTrainAbilLinks()` but targets UnitTypeChangeEvent instead of UnitBorn.

**Algorithm:**
1. Load oracle-restored replays (tracker events) + stripped counterparts (game events/CmdEvents)
2. For each UnitTypeChangeEvent in tracker data, find the closest preceding CmdEvent from the same player
3. Correlation window: 0–500 loops lookback (shorter than train discovery's 100–1500 because morphs are immediate commands with no production queue)
4. Modal `(abilLink, abilCmdIndex)` pair across all replays is the mapping
5. Output: `unitTypeName → (abilLink, abilCmdIndex)` table

**Target units for discovery:** Baneling, Ravager, Lurker, BroodLord, Overseer, OrbitalCommand, PlanetaryFortress, Lair, Hive, GreaterSpire.

**Fallback:** If oracle replays lack UnitTypeChangeEvent data for rare morphs (Lurker, BroodLord, GreaterSpire), use `AbilityMappingCoverageDiagnosticTest` output from 500 ladder replays — identify high-frequency unmapped abilLinks in the 100–300 range and correlate manually.

### 2. Mapping — `AbilityMapping` additions

New constants (values populated from discovery output):
```java
private static final int ABIL_BANELING_MORPH = ???;
private static final int ABIL_RAVAGER_MORPH = ???;
private static final int ABIL_LURKER_MORPH = ???;
private static final int ABIL_BROODLORD_MORPH = ???;
private static final int ABIL_OVERSEER_MORPH = ???;
private static final int ABIL_ORBITAL_COMMAND_MORPH = ???;
private static final int ABIL_PLANETARY_FORTRESS_MORPH = ???;
private static final int ABIL_LAIR_MORPH = ???;
private static final int ABIL_HIVE_MORPH = ???;
private static final int ABIL_GREATER_SPIRE_MORPH = ???;
```

New cases in `dispatchHuman()`:
```java
case ABIL_BANELING_MORPH -> isRace(Race.ZERG)
    ? List.of(new ReplayCommand.MorphCommand(loop, "Zergling", "Baneling"))
    : null;
case ABIL_RAVAGER_MORPH -> isRace(Race.ZERG)
    ? List.of(new ReplayCommand.MorphCommand(loop, "Roach", "Ravager"))
    : null;
// ... etc for all morphs
```

Each morph has a unique abilLink — no abilCmdIndex disambiguation needed (unlike train commands where one abilLink maps to multiple unit types). Building morphs follow the same pattern.

### 3. Emission — `StrippedReplayFeatureExtractor` changes

#### 3a. Timed morphs — modify `handleMorph()`

**Signature change:** `handleMorph()` currently takes `(MorphCommand, int playerId, List<SyntheticEvent>, int tagCounter)`. Add `PlayerState state` parameter (needed for morph spending in §3b). Update the call site in `extract()` to pass `state`.

Current logic for standard unit morphs emits instant UnitBorn. Change to UnitInit+UnitDone:

```java
// Before (standard unit morphs):
events.add(new SyntheticEvent(commandLoop, EventOrdinal.UNIT_BORN, ...));

// After (all morphs use UnitInit+UnitDone):
int morphTime = getMorphTime(targetName);
events.add(new SyntheticEvent(commandLoop, EventOrdinal.UNIT_INIT, ...));
long doneLoop = commandLoop + morphTime;
events.add(new SyntheticEvent(doneLoop, EventOrdinal.UNIT_DONE, ...));
```

This unifies the three-way split into two paths: Archon (2 source deaths) vs everything else (1 source death). Both emit UnitInit+UnitDone with calibrated morph time.

New `getMorphTime(String targetName)` method reads from `SC2Data.trainTimeInLoops()` for unit morphs and `SC2Data.buildTimeInLoops()` for building morphs. No new `morphTimeInLoops()` method — update the existing placeholder 672-loop entries in `trainTimeInLoops()` with calibrated values. Single source of truth.

**Supply tracking for UnitDone:** Switching from UnitBorn to UnitInit+UnitDone breaks supply tracking for Overseer (and any future supply-providing morph target). Currently `applyEventToEconomy` handles UnitBorn for Overseer supply (`foodMade += 8 * 4096`) but has no UnitDone case for supply. Add UnitDone handling in `applyEventToEconomy` for supply-providing unit types (Overseer) to restore the supply balance after the source unit's UnitDied event deducts supply.

**Existing test update:** `StrippedReplayMorphTest.standardUnitMorphEmitsSingleDeath()` currently asserts UnitBorn for Zergling→Baneling. This test must be updated to assert UnitInit+UnitDone instead.

#### 3b. Morph spending — new `trackMorphSpending()`

Analogous to `trackTrainSpending()` and `trackBuildSpending()`. Deducts delta costs from player economy state:

| Target | Minerals | Gas | Supply delta |
|---|---|---|---|
| Baneling | 25 | 25 | 0 |
| Ravager | 25 | 75 | +1 |
| Lurker | 50 | 100 | +1 |
| BroodLord | 150 | 150 | +2 |
| Overseer | 50 | 50 | 0 |

Building morphs: use `SC2Data.mineralCost()` / `gasCost()` for the target building directly. These already store morph delta costs (e.g., OrbitalCommand returns 150 minerals — the morph cost, not the 550 total). Do NOT subtract source building cost — that would double-delta.

#### 3c. Selection-based multiplication

Wire `SelectionUnitLinkTracker` into the main `extract()` loop:

1. **Instantiation:** One `SelectionUnitLinkTracker` per player, created alongside each `AbilityMapping` instance at extraction start.

2. **Dual feeding:** Every SelectionDeltaEvent goes to both `mapping.onSelection(sel)` (existing) AND `tracker.onSelection(sel)` (new). The two serve different purposes — `AbilityMapping.SelectionState` tracks tags for command dispatch; `SelectionUnitLinkTracker` tracks unitLinks for multiplication counts.

3. **On morph CmdEvent:** After `AbilityMapping.process()` returns a `MorphCommand`, look up source unit's unitLink value(s) from a `MORPH_TARGET_TO_SOURCE_LINKS` map keyed by **target name** (not abilLink — MorphCommand doesn't carry abilLink, and target name is unique per morph type). Call `tracker.countMatching(sourceUnitLinks)` to get the count. Emit that many MorphCommands:

```java
case ReplayCommand.MorphCommand mc -> {
    int count = 1;
    if (!BUILDING_MORPH_TARGETS.contains(mc.targetName())) {
        Set<Integer> sourceLinks = MORPH_TARGET_TO_SOURCE_LINKS.get(mc.targetName());
        if (sourceLinks != null) {
            int selected = tracker.countMatching(sourceLinks);
            if (selected > 0) {
                count = Math.min(selected, MAX_MULTIPLICATION);
            }
        }
    }
    for (int r = 0; r < count; r++) {
        tagCounter = handleMorph(mc, playerId, state, syntheticEvents, tagCounter);
    }
}
```

4. **Cap:** Apply `MAX_MULTIPLICATION` (currently 4) to prevent runaway counts from corrupted selection state.

5. **Building morphs excluded:** Building morphs are always 1:1 — no multiplication. Only unit morphs (Baneling, Ravager, Lurker, BroodLord, Overseer) use selection-based counts.

**`MORPH_TARGET_TO_SOURCE_LINKS` map:** Maps morph target name to the set of unitLink values for the source unit type. Example: `"Baneling" → Set.of(ZERGLING_UNIT_LINK)`. UnitLink values are discovered from SelectionDelta subgroups in the same oracle replays used for abilLink discovery.

**Tracker lifecycle:** One tracker per player. Created at extraction start, fed all SelectionDeltaEvents for that player. The tracker already handles tag-based dedup and supports ZeroIndices/OneIndices/Mask removal variants.

### 4. Calibration — `SC2Data` morph times

Update the existing placeholder entries in `SC2Data.trainTimeInLoops()` (currently all 672 loops for Baneling, Ravager, Lurker, BroodLord, Overseer) with calibrated values. No new `morphTimeInLoops()` method — single source of truth in `trainTimeInLoops()`.

`getMorphTime()` in the extractor dispatches: `trainTimeInLoops()` for unit morphs, `buildTimeInLoops()` for building morphs (already exists and calibrated).

Per protocol `sc2data-train-times-require-calibration`: calibrate from replay data, not from formula. Run `SC2TrainTimeCalibrationTest` pattern — new `SC2MorphTimeCalibrationTest` that measures empirical morph durations from oracle replays (time delta between morph CmdEvent and corresponding UnitTypeChangeEvent) and asserts they match the constants.

Fallback: if oracle replays lack sufficient morph samples for rare units (Lurker, BroodLord), use Liquipedia values converted to game loops (÷ 22.4 loops/second at Faster speed). This is a known deviation from the calibration protocol — file a follow-up issue to re-calibrate when data is available.

## Out of Scope

- **TrackedBuildings state update on building morph:** When CC morphs to OrbitalCommand, `PlayerState.trackedBuildings` retains "CommandCenter" as the name. Currently harmless — no consumer queries tracked buildings by name post-morph (only WarpGate auto-morph checks `"Gateway".equals(b.name)` and that's a separate path). Document but don't fix.
- **Archon DT source identification:** `ABIL_ARCHON_MERGE` hardcodes "HighTemplar" as source, but Dark Templar can also merge into Archons. Pre-existing bug — file a separate issue.

## Testing Strategy

| Test | Type | What it validates |
|---|---|---|
| `AbilityDiscoveryCalibrationTest.discoverMorphAbilLinks()` | Diagnostic | Discovers morph abilLinks from oracle replays |
| `AbilityMappingTest` — new morph cases | Unit | Each morph abilLink dispatches correct MorphCommand |
| `StrippedReplayMorphTest` — timed morphs | Unit | Standard unit morphs emit UnitInit+UnitDone (not UnitBorn). Updates existing `standardUnitMorphEmitsSingleDeath()` |
| `StrippedReplayMorphTest` — morph spending | Unit | Economy state updated after morph |
| `StrippedReplayMorphTest` — multiplication | Unit | Selection with N source units produces N morph events |
| `StrippedReplayMorphTest` — Overseer supply | Unit | Overseer morph via UnitInit+UnitDone preserves supply balance |
| `SC2MorphTimeCalibrationTest` | Calibration | Empirical morph times match SC2Data constants |
| `StrippedReplayValidationTest` — morph coverage | Report | Baneling coverage >= 70%, building morphs tracked |

## Acceptance Criteria (from issue)

- [ ] Baneling coverage >= 70% (largest morph unit by volume)
- [ ] Building upgrade morphs tracked (OrbitalCommand, Lair, Hive)
- [ ] No new over-counting introduced

## Design Review Findings (Light — 1 round)

11 findings, all addressed:

| ID | Priority | Finding | Resolution |
|---|---|---|---|
| R1-02 | HIGH | Building morph cost uses wrong delta formula | Fixed: SC2Data already stores delta costs — use directly, no subtraction |
| R1-03 | HIGH | Overseer morph breaks supply tracking | Fixed: add UnitDone handling in applyEventToEconomy for supply-providing units |
| R1-04 | HIGH | handleMorph() lacks PlayerState parameter | Fixed: spec now includes signature change |
| R1-05 | HIGH | Dual morph time storage ambiguity | Fixed: update existing trainTimeInLoops() entries, no new method |
| R1-06 | MEDIUM | Selection multiplication under-specified | Fixed: added instantiation, dual feeding, target-name-keyed lookup with code example |
| R1-07 | MEDIUM | Building morph trackedBuildings stale | Documented as out-of-scope — no current consumer affected |
| R1-08 | MEDIUM | Existing test asserts UnitBorn | Fixed: noted in testing strategy that existing test must be updated |
| R1-09 | MEDIUM | MorphCommand lacks abilLink for lookup | Fixed: use MORPH_TARGET_TO_SOURCE_LINKS keyed by target name |
| R1-10 | LOW | Missing GitHub issues for fallbacks | Will create during implementation |
| R1-11 | LOW | Archon DT source hardcoded | Documented as out-of-scope — file separate issue |

## References

- `quarkmind-sc2/src/main/java/io/quarkmind/sc2/replay/AbilityMapping.java` — current morph dispatch (Archon only)
- `quarkmind-sc2/src/main/java/io/quarkmind/sc2/replay/StrippedReplayFeatureExtractor.java` — handleMorph(), SelectionUnitLinkTracker
- `quarkmind-sc2/src/main/java/io/quarkmind/sc2/replay/ReplayCommand.java` — MorphCommand record
- `quarkmind-sc2/src/main/java/io/quarkmind/domain/SC2Data.java` — morph costs and times
- `quarkmind-sc2/src/test/java/io/quarkmind/sc2/replay/StrippedReplayMorphTest.java` — existing morph test coverage
- `quarkmind-sc2/src/test/java/io/quarkmind/sc2/replay/AbilityDiscoveryCalibrationTest.java` — discovery infrastructure
- `quarkmind-sc2/src/test/java/io/quarkmind/sc2/replay/SelectionUnitLinkTrackerTest.java` — tracker tests
- `docs/protocols/extractor-separate-from-simulated-game.md` — morph logic stays in extractor, not SimulatedGame
- `docs/protocols/sc2data-train-times-require-calibration.md` — calibrate from replays, not formula
- `docs/protocols/replay-tag-prefix-per-source.md` — tag prefix rules for synthesized events
- `docs/protocols/enum-switch-exhaustive-required.md` — exhaustive switches if any enums change

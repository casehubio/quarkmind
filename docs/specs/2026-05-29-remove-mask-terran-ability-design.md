# Design: removeMask tests, isComplete(), and Terran AbilityMapping

**Date:** 2026-05-29  
**Branch:** `issue-160-remove-mask-tests`  
**Issues:** #160, #161, #140  
**Spec review:** addressed 2026-05-29 after empirical discovery run

---

## Scope

Three issues delivered in one branch in order:

1. **#160** — Focused unit tests for `IEM10CommandExtractor.applySelectionDelta` (all four removeMask variants)
2. **#161** — `SimulatedGame.isComplete()` base-contract unit test (CLAUDE.md already updated in bca77f2)
3. **#140** — Terran ability IDs: discover from existing PvT replays, populate `AbilityMapping`, test at three layers; also fix `AbilityMapping.onSelection` removeMask bug uncovered during discovery

---

## #160 — removeMask unit tests

### Visibility change

`applySelectionDelta` in `IEM10CommandExtractor` changes from `private static` to `static` (package-private). One keyword removed. No other signature changes. Follows the CLAUDE.md convention: "Package-private static methods on CDI beans are tested from the same package without CDI — make them static (not private) to enable this."

### New test class

`IEM10CommandExtractorSelectionDeltaTest` in `io.quarkmind.sc2.mock`, alongside `IEM10CommandExtractorTest`. No filesystem IO — all fixtures built inline with Jackson's `ObjectMapper`.

**ObjectMapper instantiation:** `private static final ObjectMapper MAPPER = new ObjectMapper();`

**None variant fixture construction:** `removeMask.putNull("None")` — not `put("None", null)` (which compiles but may serialize differently depending on Jackson version).

**Packed tag arithmetic:** `packed = (index << 18) | recycle`, decoded as `"j-" + index + "-" + recycle`.  
Example: index=3, recycle=7 → packed=786439 → `"j-3-7"`.

### Test cases

| Test | removeMask variant | Input selection | addUnitTags | Expected result |
|------|--------------------|-----------------|-------------|-----------------|
| `maskVariantRemovesCorrectBitPositions` | Mask=5 (0b0101) | ["a","b","c","d"] | none | ["b","d"] |
| `maskVariantWithZeroMaskRemovesNothing` | Mask=0 | ["a","b"] | none | ["a","b"] |
| `sweepToEndTruncatesFromIndex` | SweepToEnd=1 | ["a","b","c"] | none | ["a"] |
| `sweepToEndZeroRemovesAll` | SweepToEnd=0 | ["a","b"] | none | [] |
| `oneIndiceRemovesSingleItem` | OneIndice=1 | ["a","b","c"] | none | ["a","c"] |
| `oneIndiceOutOfBoundsIsNoOp` | OneIndice=5 | ["a","b"] | none | ["a","b"] |
| `noneVariantPreservesExistingSelection` | None | ["a","b"] | none | ["a","b"] |
| `addUnitTagsDecodesPackedTag` | None | [] | (3,7) → 786439 | ["j-3-7"] |
| `noneVariantAppendsTagsToExisting` | None | ["a"] | (3,7) | ["a","j-3-7"] |
| `duplicateTagNotAdded` | None | [] | same tag twice | tag appears once |

### CLAUDE.md update

Add `IEM10CommandExtractorSelectionDeltaTest` to the unit test inventory alongside `IEM10CommandExtractorTest`.

---

## #161 — isComplete() base-contract test

Add to `SimulatedGameTest`:
```java
@Test
void isCompleteReturnsFalseByDefault() {
    assertThat(game.isComplete()).isFalse();
}
```

Uses the existing `game` field (reset in `@BeforeEach`). Documents the contract: base `SimulatedGame` never completes; subclasses override for replay-driven games.

CLAUDE.md test inventory already includes `IEM10CommandExtractorTest` (added in bca77f2). No additional CLAUDE.md changes needed for #161.

---

## #140 — Terran AbilityMapping

### Why no new replay acquisition

The `aiarena_protoss/` directory already contains PvT replays with Terran players. The issue was filed before this was confirmed.

**Empirically verified PvT replays (from `TerranDiscoveryTest` run 2026-05-29):**

| Replay | Duration | Terran userId | Terran player | Notes |
|--------|----------|---------------|---------------|-------|
| `Nothing_4720935.SC2Replay` | 18m48s | 1 (player 2) | RustyNikolaj (wins) | Best: long game, most Terran production |
| `Tyckles_4721034.SC2Replay` | 15m36s | 1 (player 2) | RustyNikolaj (wins) | Good: 15m data |
| `Starlight_4721165.SC2Replay` | 6m25s | 1 (player 2) | Siriusly (wins) | Short: early-game only |
| `ArgoBot_4721229.SC2Replay` | 9m58s | 0 (player 1) | Unknown bot | Bot replay: no Mask events |

**Note:** `playerId=2 → userId=1` (via `AbilityMapping.userId = playerId - 1`). Discovery output keys on userId; integration test uses playerId. These must not be conflated.

### Blocking fix first: AbilityMapping.onSelection removeMask gap

**Empirical finding (TerranDiscoveryTest on PvT replays):**

| Replay | ZeroIndices | Mask | None | SweepToEnd | OneIndice |
|--------|-------------|------|------|------------|-----------|
| Nothing_4720935 (human) | 88,004 | **1,787** | 1,613 | 0 | 0 |
| Tyckles_4721034 (human) | 109,360 | **1,998** | 1,834 | 0 | 0 |
| Starlight_4721165 (bot) | 20,913 | 1 | 9 | 0 | 0 |
| ArgoBot_4721229 (bot) | 56,221 | 0 | 13 | 0 | 0 |

`Mask` appears 1,787–1,998 times in human replays. Current `AbilityMapping.onSelection()` ignores `getRemoveMask()` and replaces the selection wholesale with `addUnitTags`. For `Mask` events, this would clear the removed units but skip the bit-removal step, producing incorrect building tags for any training command that follows.

**`ZeroIndices`** is the dominant binary-format variant (56K–109K events). It means wholesale selection replacement — equivalent to the user clicking to select a new group. Current wholesale-replace behaviour is CORRECT for `ZeroIndices`. This variant does not appear in IEM10 JSON format.

**Fix: update `AbilityMapping.onSelection()` algorithm:**

```
if removeMask is null or ZeroIndices:
    start from empty list (full replacement)
else:
    start from existing currentSelection
    apply Mask / SweepToEnd / OneIndice removal
    (None = no removal, just start from currentSelection)
then:
    append addUnitTags (deduplicate)
set currentSelection = result
```

This fix must land **before** adding Terran dispatch cases and the integration test — the integration test uses `Nothing_4720935` (human replay, 1,787 Mask events).

**Note:** The `getRemoveMask()` API on Scelight `Delta` returns `Pair<String, Object>` where `value1` is the variant name and `value2` is the variant payload (for Mask: an int bitmask). The fix must call `delta.getRemoveMask()` and branch on `value1`.

**Follow-up filed:** #162 — extract shared `SelectionState` type to unify binary and IEM10 JSON selection tracking. Not in scope for this branch.

### Layer 1 — Discovery diagnostic

`TerranDiscoveryTest` (temporary, deleted after #140): adds all four PvT replays, prints removeMask variant counts and top no-target Cmd events per replay. Run this to derive final Terran abilLink constants. **Delete before committing.**

Update `AbilityDiscoveryTest.replayFiles()` to include the three PvT replays above (the permanent record of which replays are discoverable).

Fix the stale javadoc on `AbilityDiscoveryTest`: it says "Covers Protoss (userId=0) and Zerg (userId=1) from PvZ aiarena replays." — this was only true for the original Nothing_4720936. Update to reflect that the file list now includes PvT replays with mixed userId assignments.

### Terran abilLink constants (from discovery run)

Training commands filtered: no target point, no target unit (hasTP=false, hasTU=false), selSize≥1.

Cross-referencing across Nothing_4720935 (18m Terran win), Tyckles_4721034 (15m Terran win), Starlight_4721165 (6m Terran win):

| abilLink | idx | Counts (Nothing/Tyckles/Starlight) | Confidence | Candidate unit |
|----------|-----|------------------------------------|------------|----------------|
| 155 | 0 | 92 / 68 / 22 | High | SCV (Command Center — single unit) |
| 158 | 0 | 177 / 21 / 2 | Medium | Unknown — dominant in long game |
| 159 | 0 | 57 / 55 / 40 | High | Likely Marine (early-game dominant) |
| 159 | 3 | 24 / 41 / 0 | Medium | Likely Marauder (mid-game) |
| 157 | 0 | 47+27 / 34 / 12 | Medium | Unknown — consistent |
| 161 | 4 | 21 / 31 / 0 | Medium | Specific idx — tech-lab unit |
| 165 | 0+1 | 5+2 / 0 / 2+2 | Low | Starport unit? |

**Implementation step:** run `TerranDiscoveryTest`, examine output, finalize which abilLinks map to which UnitType using game-context reasoning (unit frequency vs game length, early/late game patterns). Map only abilLinks with high confidence. Leave low-confidence entries unresolved and log them as unknown.

**Command Center pattern:** Discovery shows abilLink=155 appears ONLY at idx=0 across all replays. Simplified dispatch without index guard is correct:
```java
case ABIL_COMMAND_CENTER -> trainIntent(loop, UnitType.SCV);
```

**UnitType coverage note:** `SC2Data.trainTimeInTicks()` for SCV, Marine, Marauder, Medivac uses uncalibrated default (672 ticks). The `trainIntentsReferenceKnownUnitTypes` assertion (checks > 0) passes on the default path and does NOT verify Terran units are in SC2Data's calibrated table. This is expected — Terran unit train time calibration is out of scope. File a follow-up if discovery data surfaces calibrated values.

### Layer 2 — AbilityMapping constants and dispatch

Add Terran constants at the top of `AbilityMapping` (same pattern as Protoss/Zerg). Multi-unit buildings use `Map<Integer, UnitType>`. Single-unit buildings use inline dispatch.

One test per discovered Terran unit type in `AbilityMappingTest` using the existing `fakeCmdEvent()` helper. Every dispatch case must have a corresponding unit test.

### Layer 3 — TerranReplayCommandExtractorTest (integration)

New class `TerranReplayCommandExtractorTest` in `io.quarkmind.sc2.replay`. Uses `@TestInstance(PER_CLASS)` + `@BeforeAll` pattern from `ReplayCommandExtractorTest`.

```
replay: replays/aiarena_protoss/Nothing_4720935.SC2Replay
playerId: 2  (Terran — RustyNikolaj; maps to userId=1 via AbilityMapping.userId = playerId - 1)
```

Full assertion set (mirrors Protoss test, not a subset):
- `stream.intents()` is non-empty
- At least one SCV `TrainIntent` present
- At least one Marine `TrainIntent` present (or whatever high-confidence unit is confirmed in discovery)
- `intentsAreOrderedByLoop`
- `allIntentLoopsAreNonNegative`
- `movementOrdersAreOrderedByLoop`
- `allMovementOrderTagsMatchTrackerFormat` — `startsWith("r-")`
- `trainIntentsReferenceKnownUnitTypes` — `SC2Data.trainTimeInTicks(unitType) > 0`
- `listsAreUnmodifiable`

Note: `trainIntentsReferenceKnownUnitTypes` passes even with uncalibrated SC2Data defaults (672 > 0). It guards against null UnitType escaping dispatch — still valuable.

### CLAUDE.md update

Add both `TerranReplayCommandExtractorTest` and `IEM10CommandExtractorSelectionDeltaTest` to the unit test inventory.

---

## Delivery order within the branch

1. **#160:** visibility change + `IEM10CommandExtractorSelectionDeltaTest` + CLAUDE.md
2. **#161:** `isComplete()` test in `SimulatedGameTest`
3. **#140, step A:** fix `AbilityMapping.onSelection()` for Mask/None/SweepToEnd/OneIndice
4. **#140, step B:** run `TerranDiscoveryTest`, derive constants, add to `AbilityMapping`, add `AbilityDiscoveryTest` PvT entries, fix javadoc, delete `TerranDiscoveryTest`
5. **#140, step C:** `AbilityMappingTest` Terran unit tests
6. **#140, step D:** `TerranReplayCommandExtractorTest`
7. CLAUDE.md update
8. Commit (closes #160, #161, refs #140)

---

## Platform Coherence

All work is quarkmind-specific — SC2 domain logic, replay parsing, ability mapping. No foundation repo involvement. No PLATFORM.md updates required.

Protocols respected:
- `extractor-separate-from-simulated-game` — tests are in the extractor's own test class
- `replay-tag-prefix-per-source` — test fixtures use `j-` for IEM10; `AbilityMappingTest` uses `r-` for binary

Tracked follow-ups (not in scope):
- #162 — shared `SelectionState` type to unify binary and IEM10 JSON selection tracking

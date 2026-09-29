# Fix SelectionUnitLinkTracker State Accumulation (#327)

## Problem

`SelectionUnitLinkTracker` in `StrippedReplayFeatureExtractor` accumulates unitLinks
across selection events without proper deduplication. The tracker maintains a flat
`ArrayList<Integer>` of unitLinks and appends from `addSubgroups` after applying
`removeMask`. When `removeMask` is `None` or null (no removal), addSubgroups entries
stack on top of existing entries, causing unbounded growth.

Evidence from worst replay (`15a050...`):
- Loop 2982: unitLink=70 count = 2 (correct)
- Loop 11282: unitLink=70 count = 24 (impossible)
- Loop 18568: unitLink=70 count = 76 (wildly corrupted)

This inflates `countMatching()` results, which the multiplication logic uses as
the repeat factor for train commands. P2:Marine ends up at 178% of oracle
(oracle=1510, java=2695).

## Root Cause

The tracker uses `addSubgroups` (aggregated counts per unitLink type) for building
its selection state. Unlike `addUnitTags` (unique per-unit tag IDs), these counts
have no deduplication — appending the same subgroup data twice doubles the count.

`AbilityMapping` handles the same `SelectionDeltaEvent` events but uses `addUnitTags`
instead, which provides unique identity per unit and prevents accumulation.

## Design

### Rewrite tracker to use tag-based deduplication

Replace the flat `ArrayList<Integer>` with an ordered list of `(tag, unitLink)` pairs.
Each entry represents one unit in the selection, identified by its unique tag.

**Data structure:**

```java
record TaggedUnit(int tag, int unitLink) {}
private final ArrayList<TaggedUnit> units = new ArrayList<>();
```

**onSelection processing:**

1. **removeMask** — operates on indices into the `units` list (same semantics as now):
   - `ZeroIndices` + Integer[] → keep only units at listed indices
   - `OneIndices` + Integer[] → remove units at listed indices
   - `Mask` + BitArray → remove units where bit is set
   - Unknown variant → clear all
   - `None` / null → no removal (carry forward)

2. **addSubgroups + addUnitTags** — zip to create `TaggedUnit` entries:
   - Expand addSubgroups: for each subgroup, repeat its unitLink `count` times
   - Pair with addUnitTags positionally (they describe the same units)
   - Before adding each (tag, unitLink) pair, check if the tag already exists in `units`
   - If tag exists: skip (deduplication) — this is what prevents accumulation
   - If tag is new: append

3. **Fallback when addUnitTags is null or mismatched length:**
   - Use synthetic negative tags (e.g., `-(loop * 1000 + i)`) to maintain ordering
   - These synthetic tags won't match existing entries, so dedup won't fire
   - But they also won't match on subsequent events, so accumulation is bounded
     by the removeMask clearing on the next real selection event

**countMatching — unchanged semantics:**

```java
int countMatching(Set<Integer> validLinks) {
    int count = 0;
    for (TaggedUnit u : units) {
        if (validLinks.contains(u.unitLink())) count++;
    }
    return count;
}
```

### Existing safety caps remain

The `MAX_MULTIPLICATION = 8` cap in the multiplication logic stays as a defence-in-depth
measure. The `BUILDINGCAP_ABIL_LINKS` bypass for Larva/Nexus also stays — those
production types have known tracker unreliability even with tag-based tracking.

### Tag decoding

`addUnitTags` provides raw tag integers from the SC2 protocol. These are used as-is
for identity comparison — no need to decode to `GameEventStream.decodeTag()` format
since the tracker only needs equality checks within a single selection lifecycle.

## Testing

### Unit tests (no Quarkus)

- **Tag dedup prevents accumulation:** Construct a sequence of SelectionDeltaEvents
  where removeMask=None and addSubgroups repeats the same composition. Assert
  countMatching stays bounded (equals real unit count, not accumulated).

- **removeMask variants work correctly with tagged entries:** Test ZeroIndices,
  OneIndices, Mask, None — same coverage as existing diagnostic tests but asserting
  on the new data structure.

- **addSubgroups/addUnitTags zip alignment:** Test that unitLinks are correctly
  associated with their tags when multiple subgroups are present (e.g., 3 Marines +
  2 Tanks → 5 tagged entries with correct unitLinks).

- **Synthetic tag fallback:** Test behaviour when addUnitTags is null — tracker
  should still function (no crash) and produce reasonable counts.

### Diagnostic validation

- **MarineMultiplicationDiagnosticTest:** Run against oracle replays. P2:Marine
  should drop from 178% toward 80-120%.

- **TrackerCorruptionDiagnosticTest:** Run against worst replay. unitLink=70
  count should stay bounded (max ~12, never 76).

### Acceptance criteria from issue

- P2:Marine aggregate coverage between 80-120% (currently 178%)
- P1:Marine preserved at >= 90% (currently 97.6%)
- No single replay has unitLink=70 count > 20 in the tracker

## References

- `StrippedReplayFeatureExtractor.java:850-908` — current SelectionUnitLinkTracker
- `AbilityMapping.java:214-259` — tag-based selection handling (proven pattern)
- `Scelight Delta.java` — addSubgroups + addUnitTags API
- `Scelight Subgroup.java` — unitLink, count fields
- `TrackerCorruptionDiagnosticTest.java` — traces selection growth for worst replay
- `MarineMultiplicationDiagnosticTest.java` — per-replay multiplication analysis
- Issue #327 — root cause analysis and acceptance criteria
- Issue #318 — parent epic (StrippedReplayFeatureExtractor coverage)

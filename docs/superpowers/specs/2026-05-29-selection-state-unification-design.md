# Design: SelectionState Unification — #162

**Date:** 2026-05-29  
**Issue:** [#162](https://github.com/mdproctor/quarkmind/issues/162) — refactor: extract shared SelectionState

---

## Problem

`AbilityMapping.onSelection()` (binary `.SC2Replay` via Scelight) and `IEM10CommandExtractor.applySelectionDelta()` (IEM10 JSON via Jackson) both maintain an ordered list of currently selected unit/building tags and apply protocol-specific remove/add delta operations to it. The list manipulation logic is the same; only the format-specific decoding differs. These two implementations will diverge over time and require the same correctness work twice.

---

## Solution

Extract `SelectionState` — a pure list-manipulation type with no external dependencies — into `io.quarkmind.sc2`. Both callers decode their format-specific input and call `SelectionState` primitives. The result is one place to test and fix the core selection logic.

---

## SelectionState API

**Package:** `io.quarkmind.sc2`  
**Type:** `public final class SelectionState`  
**Backing store:** `ArrayList<String>` (ordered, mutable, insertion-preserving)

### Mutation methods

| Method | Semantics |
|--------|-----------|
| `removeAt(int index)` | Remove element at index; no-op if out of bounds |
| `removeIf(IntPredicate)` | Iterate descending; remove where predicate returns true for that index. Encapsulates the descending-loop invariant that prevents index-shift bugs during removal. |
| `truncateTo(int size)` | Keep first `size` elements; no-op if already shorter (SweepToEnd) |
| `retainOnly(int[] indices)` | Keep only elements at given retained indices, in indices-array order. Out-of-bounds indices are silently ignored. Empty array = clear all (ZeroIndices). |
| `clear()` | Remove all elements |
| `addTag(String tag)` | Add tag to end if not already present (O(n) contains check) |

### Static factory

`SelectionState.of(String... tags)` — creates an instance pre-populated with the given tags in order, respecting the no-duplicate invariant. Intended for test setup only.

### Accessor methods

| Method | Returns |
|--------|---------|
| `isEmpty()` | `boolean` |
| `size()` | `int` |
| `first()` | `String` — undefined behaviour if called on empty state; caller must guard with `isEmpty()` |
| `snapshot()` | `List<String>` via `List.copyOf()` |

`snapshot()` is preferred over exposing `Iterable<String>` or an unmodifiable live view. A live view creates aliasing risk (callers could retain a reference that sees subsequent mutations). An immutable copy eliminates that class of bug with negligible cost in the replay-parsing context. `snapshot()` is the correct return type here; the replay path is not hot.

`SelectionState` has no dependency on Scelight, Jackson, or any other framework. Callers translate format-specific inputs to these primitives.

---

## Variant-to-Operation Mapping

### Pre-dispatch (binary only)

`delta == null` (the `SelectionDeltaEvent.getDelta()` payload itself is null): `selection.clear()` then return. This is distinct from an absent removeMask — it clears the selection entirely.

### Dispatch table

| Variant | Source | SelectionState call |
|---------|--------|---------------------|
| removeMask absent (null) | binary | carry forward — no call |
| `None` | binary | carry forward — no call |
| `ZeroIndices` (Integer[]) | binary | `retainOnly(indices)` |
| `Mask` (BitArray) | binary | `removeIf(i -> i < bitArray.getCount() && bitArray.getBit(i))` |
| `OneIndices` (Integer[]) | binary | iterate desc: `removeAt(indices[i])` |
| removeMask node absent | JSON | carry forward — no call |
| `Mask` (int bitmask) | JSON | `removeIf(i -> (mask & (1 << i)) != 0)` |
| `SweepToEnd` (int from) | JSON | `truncateTo(from)` |
| `OneIndice` (int) | JSON | `removeAt(idx)` |

Unknown variants (binary): `selection.clear()` and log warn.

Both Mask variants — binary BitArray and JSON int bitmask — use `removeIf(IntPredicate)`. The descending-loop invariant (remove high indices first to avoid index shift) lives once in `SelectionState.removeIf`, not in both callers.

`OneIndices` (binary) remains caller-side iteration because the caller already holds the sorted Integer[] — expressing it as a set-membership predicate would require converting to `Set<Integer>` and is less readable than the straightforward descending loop with `removeAt`.

---

## AbilityMapping Changes

**Field:** `private List<String> currentSelection = List.of()` → `private final SelectionState selection = new SelectionState()`

**`onSelection(SelectionDeltaEvent)`:** Add pre-dispatch: `if (delta == null) { selection.clear(); return; }`. Then same variant switch using `SelectionState` calls. Mask variant: `selection.removeIf(i -> ...)`. Add-tags loop: `selection.addTag(GameEventStream.decodeTag(rawTag))`.

**`trainIntent(long, UnitType)`:** `currentSelection.get(0)` → `selection.first()`

**`moveOrders(CmdEvent, long)`:** `for (String tag : currentSelection)` → `for (String tag : selection.snapshot())`

**`reset()`:** `currentSelection = List.of()` → `selection.clear()`

**`setSelectionForTest(int, List<String>)`:** Signature unchanged — test call sites unaffected. Body: `selection.clear(); tags.forEach(selection::addTag)`. Note: `addTag` deduplicates; `List.copyOf` in the current implementation does not. Existing tests do not pass duplicate tags, so behaviour is identical in practice.

---

## IEM10CommandExtractor Changes

**`extract()` local variable:** `List<String> currentSelection = new ArrayList<>()` → `SelectionState currentSelection = new SelectionState()`

**`applySelectionDelta(JsonNode, List<String>)`** → **`applySelectionDelta(JsonNode, SelectionState)`** — package-private, visibility unchanged.

**Inside `applySelectionDelta`:** Replace direct list operations with `SelectionState` primitives. Mask variant: `selection.removeIf(i -> (mask & (1 << i)) != 0)`. `isEmpty()` call site unchanged. `get(0)` → `first()`.

---

## Tests

### New: `SelectionStateTest` — `io.quarkmind.sc2`

Unit tests for all operations:
- `removeAt`: in-bounds, out-of-bounds (no-op)
- `removeIf`: removes correct indices, skips non-matching, empty state (no-op)
- `truncateTo`: shorter-than-size (no-op), truncates correctly, zero (clear all)
- `retainOnly`: with ascending indices, with non-ascending indices (result follows indices-array order), empty array (clear all), OOB indices silently ignored
- `clear`
- `addTag`: new tag added, duplicate ignored
- `first()`: returns first element, caller-guard contract tested (non-empty state)
- `size()`: reflects current count after mutations
- `of(String...)`: factory produces correct initial state
- Combinations: remove then add, retain then add

### Updated: `IEM10CommandExtractorSelectionDeltaTest`

Replace `List<String> sel = new ArrayList<>(List.of("a","b","c"))` with `SelectionState sel = SelectionState.of("a","b","c")`. Replace `assertThat(sel)` with `assertThat(sel.snapshot())`. All 10 existing test cases retained — they cover the JSON-format dispatch path in `IEM10CommandExtractor`.

### Unchanged: `AbilityMappingTest`

`setSelectionForTest` keeps its `List<String>` signature. No test edits.

---

## What Does Not Change

- `GameEventStream.decodeTag()` — binary tag decoding stays in `AbilityMapping`
- IEM10 packed-tag decoding (`index << 18 | recycle`) stays in `IEM10CommandExtractor`
- `AbilityMapping.onSelection()` method signature — same Scelight type input
- `IEM10CommandExtractor.applySelectionDelta` visibility (package-private)
- Tag format prefixes (`r-` for binary, `j-` for JSON) — unchanged, format-specific

---

## Platform Coherence

- Application-tier SC2 logic. No platform-level doc update required.
- `SelectionState` stays in `io.quarkmind.sc2` — not in `domain/` (mutable stateful object, not a plain record). Consistent with the `extractor-separate-from-simulated-game.md` protocol: `SelectionState` is a utility for extractors, not added to `SimulatedGame`.
- No new CDI beans, no Quarkus annotations.

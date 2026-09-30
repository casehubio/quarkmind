# Decisions — Issue #328: Synthesize morph-based unit events

## D1: Full scope — all four secondary gaps included

**Choice:** Full scope — abilLink discovery + dispatch + morph multiplication + morph spending tracking + morph time calibration
**Alternatives:**
- Tight scope (discovery + dispatch only) — faster delivery but leaves handleMorph() emitting inaccurate events for a feature we're actively touching
- Include multiplication only — middle ground but arbitrary line-drawing on what counts as "related"
**Rationale:** Since we're touching the full morph pipeline end-to-end (discovery → dispatch → emission), it's more coherent to fix all the known gaps in one pass rather than leaving partially-accurate output that needs revisiting
**Trade-offs:** Larger scope increases risk of the issue growing beyond M. Morph time calibration requires SC2 data validation.
**Sources:** Issue #328 body, StrippedReplayFeatureExtractor.java:handleMorph(), SC2Data.java morph times
**Exploration:** quick
**Status:** captured

## D2: Adapt AbilityDiscoveryCalibrationTest for morph abilLink discovery

**Choice:** Add `discoverMorphAbilLinks()` method to AbilityDiscoveryCalibrationTest that correlates CmdEvents with UnitTypeChangeEvent tracker events (not UnitBorn)
**Alternatives:**
- New standalone diagnostic test — more targeted but duplicates the modal-matching infrastructure and oracle replay loading
- Manual from AbilityMappingCoverageDiagnosticTest output — quick but brittle, no reproducible validation
**Rationale:** The existing test already loads oracle+stripped replay pairs and has the correlation infrastructure. Morphs just need a different tracker event type (UnitTypeChangeEvent instead of UnitBorn) with otherwise identical logic.
**Trade-offs:** Couples more discovery logic into one test class. If UnitTypeChangeEvent is structurally different from UnitBorn, the correlation window may need different tuning.
**Sources:** AbilityDiscoveryCalibrationTest.java, AbilityMappingCoverageDiagnosticTest.java, SC2 replay protocol (UnitTypeChangeEvent)
**Exploration:** quick
**Depends on:** D1 (scope includes discovery)
**Status:** captured

## D3: Selection-based morph multiplication via SelectionUnitLinkTracker

**Choice:** Use the existing SelectionUnitLinkTracker (inner class in StrippedReplayFeatureExtractor) to count selected source units at morph command time. Morph count = number of matching source units in the current selection.
**Alternatives:**
- Oracle-calibrated flat cap — simple but loses per-command accuracy; a fixed multiplier can't distinguish "morph 1 Baneling" from "morph 8 Banelings"
- No multiplication — under-counts and contradicts the full-scope decision
**Rationale:** Mirrors the actual SC2 mechanic — morph applies to all selected units of the correct type. SelectionUnitLinkTracker already exists with tag-based dedup and `countMatching()`. This is the same approach the train pipeline is moving toward.
**Trade-offs:** Requires wiring SelectionUnitLinkTracker into the main extract() loop (currently unused there). Selection state must be maintained correctly across all events, not just morph-relevant ones. Need unitLink constants for morph source units (Zergling, Roach, Hydralisk, Corruptor, Overlord).
**Sources:** SelectionUnitLinkTracker (StrippedReplayFeatureExtractor.java:822-933), SelectionUnitLinkTrackerTest.java
**Exploration:** quick
**Depends on:** D1 (scope includes multiplication)
**Status:** captured

## D4: Timed UnitInit+UnitDone for standard unit morphs

**Choice:** Switch standard unit morphs from instant UnitBorn to timed UnitInit+UnitDone, consistent with building morphs and Archon. Source dies at commandLoop, target appears via UnitInit at commandLoop + UnitDone at commandLoop + morphTime.
**Alternatives:**
- Keep instant UnitBorn — simpler, acceptance criteria only measure coverage counts not timing, but produces temporally inaccurate events
- Instant UnitBorn + metadata — compromise that's neither accurate nor simple
**Rationale:** Consistency with the building morph and Archon paths (both already use UnitInit+UnitDone). Downstream consumers (feature extraction, strategy detection) benefit from accurate timing — a Baneling that "appears" 20 seconds before it actually morphs produces misleading state snapshots.
**Trade-offs:** Requires calibrated morph times for each unit. SC2Data.java currently has placeholder 672-loop values for all unit morphs. Need to calibrate from replays or SC2 wiki data.
**Sources:** StrippedReplayFeatureExtractor.java:handleMorph(), SC2Data.java morph times, sc2data-train-times-require-calibration protocol
**Exploration:** quick
**Depends on:** D1 (scope includes time calibration)
**Status:** captured

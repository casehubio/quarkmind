## D1: Tracker architecture — tag-based deduplication

**Choice:** Rewrite SelectionUnitLinkTracker to track individual unit tags alongside unitLinks, mirroring AbilityMapping's proven pattern
**Alternatives:**
- Fix removeMask + defensive bounds — simpler but breaks Shift+click semantics by treating None+addSubgroups as full replacement
- Defensive bounds only — safety-net cap without fixing the root cause; leaves accumulation bug in place
**Rationale:** The current tracker uses addSubgroups counts which accumulate when removeMask fails to clear. Tag-based tracking using addUnitTags provides unique identity per unit, making accumulation impossible. AbilityMapping already proves this pattern works correctly with the same Scelight API.
**Trade-offs:** Slightly more complex data structure (tag+unitLink pairs vs plain ints). Requires coordinating addSubgroups with addUnitTags on each SelectionDelta.
**Sources:** StrippedReplayFeatureExtractor.java:850-908 (current tracker), AbilityMapping.java:214-259 (tag-based pattern), Scelight Delta.java (addSubgroups + addUnitTags always co-present)
**Exploration:** quick
**Status:** captured

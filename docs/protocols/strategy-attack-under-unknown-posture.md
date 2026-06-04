---
id: PP-20260604-strategy-attack-unknown
title: "ATTACK fires under UNKNOWN posture — deliberate approximation of the old 'not /enemies' signal"
type: rule
scope: application
applies_to: "DroolsStrategyTask, StarCraftStrategy.drl — any future StrategyTask implementation"
severity: important
refs:
  - docs/superpowers/specs/2026-06-03-strategy-c2-scouting-intel-design.md
violation_hint: "StrategyTask attacks while enemy units are currently visible but their scouting events have evicted from the 3-minute window, causing a real strategic mismatch"
created: 2026-06-04
---

The strategy ATTACK rule fires when posture is neither `"ALL_IN"` nor under an active timing attack — including when posture is `"UNKNOWN"`. This is a deliberate design choice, not an oversight.

## Why UNKNOWN can mean two different things

`ENEMY_POSTURE = "UNKNOWN"` arises from:

1. **Pre-contact** — no enemy units have been scouted yet (`unitBuffer` in `ScoutingSessionManager` is empty because nothing has been seen)
2. **Post-eviction** — units were scouted but `evict()` removed events older than `UNIT_WINDOW_MS` (3 minutes). Additionally: `seenUnitTags` is a permanent `HashSet` — re-sighting the same tagged unit after eviction does not re-add an event, making post-eviction UNKNOWN stickier than pre-contact UNKNOWN.

## Why ATTACK under UNKNOWN is acceptable

The old strategy rule (before C2) fired ATTACK when `not /enemies` (no enemy units currently visible) and 4+ stalkers. Pre-contact and post-eviction UNKNOWN both correspond to "no current unit events in the 3-minute buffer" — which typically co-occurs with "no enemies currently visible." The ATTACK behavior is therefore equivalent to the old rule in the common case.

The C2 improvement is that `"ALL_IN"` posture triggers DEFEND even when no units are currently visible — a case the old rule could not handle.

## Known edge case where equivalence breaks

`seenUnitTags` stickiness can produce `"UNKNOWN"` posture while units remain visible in the current observation. If a previously-scouted unit reappears after its event has evicted, its presence does not re-populate `unitBuffer`. In that state the old rule would see enemies (DEFEND) but this rule sees UNKNOWN (ATTACK with 4+ stalkers). This is accepted as a known limitation of the `seenUnitTags` de-duplication architecture.

## The assumption this rule rests on

`UNIT_WINDOW_MS` (currently 3 minutes in `ScoutingSessionManager`) must be long enough relative to the time required to accumulate 4+ Stalkers that post-eviction UNKNOWN typically represents genuinely stale intel rather than active-contact loss.

**Revisit if:**
- `UNIT_WINDOW_MS` is reduced below ~2 minutes — the window may expire while a viable threat still exists
- The `seenUnitTags` architecture is changed to allow re-adding evicted units — the post-eviction stickiness edge case would change
- New scouting implementations write `"UNKNOWN"` with different semantics than "no current unit events"

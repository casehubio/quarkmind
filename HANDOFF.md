# Handover — 2026-05-11

**Head commit:** `640bfae` — project health cleanup

## What Changed This Session

**Decision — Phase 5 before Phase 6:**
Confirmed: finish all Phase 5 gaps before opening Phase 6 epic. Rationale: Phase 6 validates EmulatedGame accuracy against real replays; an incomplete Phase 5 makes divergence noise-dominated.

**Tier-4 health check — all findings fixed (#126, closed):**
- `GameStateTick` moved from `domain/` to `plugin/flow/` — upward layer violation (domain/ → agent/) resolved
- `docs/running.md`: emulated mode section added; mock auto-start contradiction fixed; `%emulated` added to profiles table
- `README.md`: plugin table updated (PassThrough stubs → real implementations)
- `DESIGN.md`: test count 629, pixi.min.js ref removed, "E1–E6 complete", stale scouting calibration removed
- `pom.xml`: scelight path comment corrected, mockito version pin removed (Quarkus BOM manages it at 5.21.0)
- `.gitignore`: `electron-app/package-lock.json` added

**Garden entries:**
- `GE-20260511-ce1c9d` — Java package move breaks wildcard + same-package imports
- `GE-20260511-0b3fa2` — Quarkus BOM 3.34.2 manages mockito-junit-jupiter at 5.21.0

## Immediate Next Step

**Open Phase 5 completion epic.** Five items:
1. Parallel training queues + supply reservation at queue time (`EmulatedGame.java:264`)
2. Friendly auto-engage (units fight without explicit `AttackIntent`)
3. Realistic mineral saturation (diminishing returns above 16 workers)
4. Deferred visualizer work (probe overlap, HTML mineral display, geyser sprite, time-based UI tests)

## Open Issues

| # | What | Status |
|---|------|--------|
| #13 | Live SC2 smoke test | Blocked on SC2 |
| #14 | GraalVM native image | Blocked on #13 |
| #74 | YAML unit definitions | Parked |

## References

| Context | Where |
|---------|-------|
| Blog entry (this session) | `~/claude/public/quarkmind/blog/2026-05-11-mdp01-accurate-not-just-working.md` |
| Phase 5 gaps | `docs/roadmap-sc2-engine.md` — Phase 5 "Not yet implemented" |
| Prior handover | `git show HEAD~1:HANDOFF.md` |

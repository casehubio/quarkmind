---
id: PP-20260603-049dd0
title: "NEAREST_THREAT must be written only when enemies are visible — never unconditionally"
type: rule
scope: application
applies_to: "All ScoutingTask implementations — DroolsScoutingTask, BasicScoutingTask, and any future scouting implementation"
severity: important
refs:
  - docs/superpowers/specs/2026-06-03-adaptive-plugin-selection-design.md
violation_hint: "TacticsTask activates every tick even when no enemies are visible — the gate fails to suppress tactics execution during the macro phase."
created: 2026-06-03
---

The TacticsTask adaptive gate (`entryCriteria = {READY, STRATEGY, NEAREST_THREAT}`) depends on `NEAREST_THREAT` being absent from the CaseFile when no enemies are visible. The CaseEngine gate mechanism uses key presence, not key value — writing `NEAREST_THREAT` unconditionally (even as `null`, `Optional.empty()`, or a sentinel) breaks the gate and causes TacticsTask to activate every tick. All scouting implementations must write `NEAREST_THREAT` only inside an `!enemies.isEmpty()` guard, never outside it.

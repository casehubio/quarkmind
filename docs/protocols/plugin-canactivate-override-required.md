---
id: PP-20260603-cefed9
title: "Override canActivate() on every plugin implementation that declares non-trivial entryCriteria()"
type: rule
scope: application
applies_to: "All TaskDefinition implementations in plugin/ — DroolsStrategyTask, DroolsTacticsTask, FlowEconomicsTask, DroolsScoutingTask, and any future plugin seam implementation"
severity: critical
refs:
  - docs/protocols/emulated-plugin-seam-visibility.md
  - docs/superpowers/specs/2026-06-03-adaptive-plugin-selection-design.md
violation_hint: "Plugin fires every tick regardless of entryCriteria() keys. No error, no warning — behavior is silently wrong."
created: 2026-06-03
---

`TaskDefinition.canActivate(CaseFile)` in the installed casehub-core snapshot unconditionally returns `true` — it never evaluates `entryCriteria()`. Any plugin that declares entry criteria beyond `{READY}` without overriding `canActivate()` will activate on every tick regardless of game state, silently defeating the adaptive binding conditions. Override with `entryCriteria().stream().allMatch(caseFile::contains)` on every affected implementation, and document the override with a comment referencing this defect so the overrides can be identified and removed when the foundation corrects the default.

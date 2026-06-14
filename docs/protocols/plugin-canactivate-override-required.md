---
id: PP-20260603-cefed9
title: "Plugin activation: requires() declares keys; activateIf() declares extra gates — do not duplicate between them"
type: rule
scope: application
applies_to: "All io.quarkmind.agent.TaskDefinition implementations in plugin/"
severity: critical
refs:
  - docs/protocols/emulated-plugin-seam-visibility.md
  - docs/superpowers/specs/2026-06-03-adaptive-plugin-selection-design.md
  - docs/superpowers/specs/2026-06-13-casehub-engine-migration-design.md
violation_hint: "activateIf() re-checks keys already in requires(), or requires() is empty when it should declare keys."
created: 2026-06-03
updated: 2026-06-14
---

## Phase 1 pattern (current — casehub-core + QuarkMind TaskDefinition)

`io.quarkmind.agent.TaskDefinition` splits plugin activation into two orthogonal contracts:

- **`requires()`** — declares keys that must be present in context before the plugin activates. Evaluated first; never include CDI-injected state.
- **`activateIf()`** — additional gate beyond key presence. For CDI-injected state (StrategySelector, broker) only. Must NOT re-check keys already in `requires()` — that is redundant and misrepresents the Phase 2 semantics where the engine evaluates `requires()` independently.

The Phase 1 bridge (`canActivate(CaseFile)`) delegates to `TaskDefinition.testActivation(ctx)`, which combines both checks: `requires().stream().allMatch(ctx::contains) && activateIf().test(ctx)`. This mirrors Phase 2 SequenceWorker semantics exactly.

**Correct pattern — extra gate (selector + broker):**
```java
@Override
public Set<String> requires() {
    return Set.of(QuarkMindCaseFile.READY, QuarkMindCaseFile.ENEMY_ARMY_SIZE);
}

@Override
public Predicate<CaseContext> activateIf() {
    // requires() already gates on READY and ENEMY_ARMY_SIZE — only CDI-injected state here
    return ctx -> strategySelector.isSelected(getId())
        && broker.current(ScoutingIntelType.POSTURE).isPresent();
}
```

**Correct pattern — no extra gate (requires() is sufficient):**
```java
@Override
public Set<String> requires() { return Set.of(QuarkMindCaseFile.READY); }

// activateIf() NOT overridden — default ctx -> true is correct; requires() gates on READY
```

**Wrong — activateIf() duplicates requires():**
```java
@Override
public Predicate<CaseContext> activateIf() {
    return ctx -> ctx.contains(QuarkMindCaseFile.READY);  // WRONG: already in requires()
}
```

## Historical context

Before Phase 1 migration (casehub-core only), `TaskDefinition.canActivate(CaseFile)` unconditionally returned `true` — ignoring `entryCriteria()`. Plugins overrode it with `entryCriteria().stream().allMatch(caseFile::contains)`. That pattern is replaced by `requires()` + `activateIf()` + the `testActivation()` bridge.

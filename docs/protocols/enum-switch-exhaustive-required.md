---
id: PP-20260913-4d73d1
title: "Enum switch expressions must be exhaustive — no default arm"
type: rule
scope: repo
applies_to: "All switch expressions over project enums (GameMomentType, ScoutingIntelType, CoachingUrgencyTier, and any future enums)"
severity: important
refs:
  - quarkmind-sc2/src/main/java/io/quarkmind/plugin/coaching/CoachingTriggerBuilder.java
  - quarkmind-sc2/src/main/java/io/quarkmind/agent/AdvisoryTriggerBuilder.java
  - quarkmind-sc2/src/main/java/io/quarkmind/agent/plugin/ScoutingIntelPreferences.java
violation_hint: "switch expression uses `default ->` on a project enum — new enum values will be silently swallowed at runtime with no compile error"
created: 2026-09-13
---

Switch expressions over project enums must enumerate all cases explicitly. Never use `default ->` as a catch-all. When a `default` arm is present, adding a new enum value produces no compile error — the new value silently falls into the default at runtime, causing incorrect behaviour with no diagnostic.

```java
// Wrong — silently swallows new values
return switch (type) {
    case A, B -> CRISIS;
    case C, D -> STRATEGIC;
    default -> null;
};

// Correct — new enum values produce a compile error
return switch (type) {
    case A, B -> CRISIS;
    case C, D -> STRATEGIC;
    case E, F, G -> null;
};
```

This was discovered in #260 when `STRATEGY_TRANSITION` was added to `GameMomentType`. Both `CoachingTriggerBuilder.mapMomentToTier()` and `AdvisoryTriggerBuilder.mapMomentTypeToTrigger()` used `default -> null`, and the new value was silently dropped with no compiler warning. The fix converted both switches to exhaustive form.

The rule applies to switch expressions only (which require exhaustiveness). Switch statements do not — but switch statements returning values via assignment should be converted to switch expressions where possible to gain compiler enforcement.

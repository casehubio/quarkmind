---
id: PP-20260606-506f33
title: "Use Unit.tag() for identity comparison — never Unit record equality"
type: rule
scope: repo
applies_to: "Any code that compares SC2 unit identity across game ticks (transition detection, target tracking, enemy set hashing)"
severity: important
refs:
  - src/main/java/io/quarkmind/domain/Unit.java
  - src/main/java/io/quarkmind/plugin/DroolsTacticsTask.java
  - src/main/java/io/quarkmind/plugin/scouting/DroolsScoutingTask.java
violation_hint: "Using Unit.equals(), Objects.equals(unitA, unitB), or a collection's hashCode() containing Units — will fire transition events on every tick for any moving unit"
created: 2026-06-06
---

`Unit` is a Java record: `record Unit(String tag, UnitType type, Point2d position, ...)`.
Java records implement `equals()` and `hashCode()` from all components, so `position` is
included. Because unit positions change every game tick, `unit.equals(other)` and
`collection.hashCode()` on any `List<Unit>` or `Set<Unit>` produce different values every
tick for any unit that moved — making them useless for detecting meaningful transitions.

For transition detection (did the nearest threat change? did the enemy set change?), always
compare by `unit.tag()` — a stable string identifier that persists for a unit's lifetime:

```java
// WRONG — fires every tick for moving units
if (!Objects.equals(prevUnit, currentUnit)) { ... }

// WRONG — hash changes every tick as positions update
int hash = enemies.hashCode();

// CORRECT — tag-only comparison
String prevTag = prevUnit != null ? prevUnit.tag() : null;
if (!Objects.equals(prevTag, currentUnit.tag())) { ... }

// CORRECT — tag-sorted hash for set transition detection
int hash = enemies.stream()
    .map(Unit::tag)
    .sorted()
    .collect(Collectors.joining())
    .hashCode();
```

---
id: PP-20261011-75f1dd
title: "Unit morphs emit UnitBorn; building morphs emit UnitInit+UnitDone"
type: principle
scope: repo
applies_to: "StrippedReplayFeatureExtractor.handleMorph"
severity: guidance
refs:
  - quarkmind-sc2/src/main/java/io/quarkmind/sc2/replay/StrippedReplayFeatureExtractor.java
violation_hint: "A unit morph (Baneling, Ravager, BroodLord, Lurker, Overseer, Archon) emitting UnitInit instead of UnitBorn"
created: 2026-10-11
---

SC2 tracker events represent unit morphs as `UnitBorn` (the new unit is born
when the morph completes) and building morphs as `UnitInit` + `UnitDone`.
`StrippedReplayFeatureExtractor.handleMorph` must match this convention.

`BUILDING_MORPH_TARGETS` (Lair, Hive, GreaterSpire, WarpGate, OrbitalCommand,
PlanetaryFortress) → `UnitInit` at command loop + `UnitDone` at completion.

All other morph targets → `UnitBorn` at completion loop.

Discovered in #404: emitting UnitInit for Banelings caused the accuracy test
(which compares UnitBorn counts) to report 0 Banelings despite correct
abilLink routing. The morph pipeline was working; the event type was wrong.

---
id: PP-20261027-sc2attr
title: "SC2Data.unitAttributes must be calibrated from game data — programmatic definitions drift from actual SC2 values"
type: rule
scope: repo
applies_to: "SC2Data.unitAttributes(), any code that classifies units by LIGHT/ARMORED/BIOLOGICAL/etc."
severity: important
refs:
  - quarkmind-sc2/src/main/java/io/quarkmind/domain/SC2Data.java
  - quarkmind-sc2/src/main/java/io/quarkmind/sc2/detector/LiveScoutingObservationExtractor.java
violation_hint: "army_composition observation produces wrong state because SC2Data.unitAttributes() is missing an attribute for a unit type — validated by CPT mismatch at classification time"
created: 2026-10-27
---

`SC2Data.unitAttributes()` is hand-maintained and incomplete. Zerglings are missing LIGHT (discovered in #459). Other unit types likely have similar gaps.

When any code path relies on `SC2Data.unitAttributes()` for classification:
1. Cross-check against replay training data — the replay extractor's vocabulary is ground truth
2. If a mismatch exists, fix `SC2Data` — do not silently work around with hardcoded sets
3. File an issue for the attribute gap (see #467)

The same principle as `sc2data-train-times-require-calibration.md` applies: calibrate from data, not from manual specification.

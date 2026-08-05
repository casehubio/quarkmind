---
id: PP-20260805-837016
title: "SC2 spatial constants must be calibrated from replay ground truth, not estimated from formulae"
type: rule
scope: repo
applies_to: "ExpansionLocation.CLUSTER_RADIUS and any downstream constant that specifies a spatial distance, clustering threshold, or map-derived measurement"
severity: important
refs:
  - src/main/java/io/quarkmind/domain/ExpansionLocation.java
  - src/test/java/io/quarkmind/domain/ExpansionLocationCalibrationTest.java
violation_hint: "A spatial constant derived from a formula or community wiki value without replay calibration — SC2 map coordinates do not follow a predictable scale"
created: 2026-08-05
---

SC2 map coordinates use an internal coordinate system where distances between landmarks (expansions, ramps, start locations) are map-specific and do not follow a universal scale or formula. Spatial constants — expansion clustering radius, ramp detection thresholds, natural expansion distance ranges — must be measured from replay data, not derived from formulae or community approximations.

**Expansion clustering:** When adding or updating `CLUSTER_RADIUS` in `ExpansionLocation`, run `ExpansionLocationCalibrationTest` (benchmark profile) against both IEM10 and AI Arena replay datasets. The test asserts each map produces 4–20 expansion locations. The current value (12.0 map units) was calibrated across 59 replays.

**New spatial constants:** When introducing any new spatial threshold (e.g. ramp width, natural distance, army engagement range), follow the same ground-truth methodology:
1. Measure the value from replay data across multiple maps
2. Write a calibration test that validates the constant against the replay dataset
3. Document the calibration source and sample size in a code comment at the constant declaration

Uncalibrated spatial values cause incorrect expansion detection, wrong army positioning, and invalid distance-based heuristics that vary silently across maps.

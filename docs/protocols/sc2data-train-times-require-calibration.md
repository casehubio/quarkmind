---
id: PP-20260522-572156
title: "SC2Data timing constants must be calibrated from replay ground truth, not derived from seconds × 22.4"
type: rule
scope: repo
applies_to: "SC2Data.trainTimeInLoops(UnitType), SC2Data.buildTimeInLoops(BuildingType), and any downstream constant that specifies a unit training or building construction duration"
severity: important
refs:
  - src/test/java/io/quarkmind/sc2/mock/SC2TrainTimeCalibrationTest.java
  - src/test/java/io/quarkmind/sc2/mock/SC2BuildTimeCalibrationTest.java
  - src/main/java/io/quarkmind/domain/SC2Data.java
violation_hint: "A float literal derived from N * 22.4 or a value in ticks × LOOPS_PER_TICK used without calibration — SC2 stores timing constants as integer game loops that do not follow the seconds × 22.4 formula"
created: 2026-05-22
updated: 2026-05-25
---

SC2 stores unit training times and building construction times as integer game loops internally. The community formula `seconds × 22.4` produces float values (e.g. Probe: 268.8) that differ from actual SC2 values (e.g. Probe: 272) in ways that cannot be predicted by rounding rules — the integers must be measured from replay data. Similarly, `ticks × LOOPS_PER_TICK` estimates for building times are frequently wrong (e.g. NEXUS estimate = 40 ticks = 880 loops; actual = 1600 loops).

**Unit training:** When adding or updating a value in `trainTimeInLoops`, run `SC2TrainTimeCalibrationTest` and use its modal output as the source of truth. Calibration pairs GAME_EVENTS abilLink commands with tracker UnitBorn events across 29 AI Arena replays using a range-bounded modal approach.

**Building construction:** When adding or updating a value in `buildTimeInLoops`, run `SC2BuildTimeCalibrationTest` and use its modal output as the source of truth. Calibration diffs UnitInit and UnitDone tracker events per building tag (no GAME_EVENTS matching needed). Note: `Sc2ReplayShared.toBuildingType` maps add-on names (FactoryTechLab, BarracksTechLab, etc.) to parent types — `SC2BuildTimeCalibrationTest` filters these to prevent contamination, but verify modal counts are large enough to be reliable.

Uncalibrated values cause systematic off-by-one tick completions in `ReplayValidationHarness` for specific loop offsets.

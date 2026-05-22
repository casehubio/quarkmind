---
id: PP-20260522-572156
title: "SC2Data train times must be calibrated from replay ground truth, not derived from seconds × 22.4"
type: rule
scope: repo
applies_to: "SC2Data.trainTimeInLoops(UnitType) and any downstream constant that specifies a unit training duration"
severity: important
refs:
  - src/test/java/io/quarkmind/sc2/mock/SC2TrainTimeCalibrationTest.java
  - src/main/java/io/quarkmind/domain/SC2Data.java
violation_hint: "A float literal derived from N * 22.4 (e.g. 268.8) in trainTimeInLoops — SC2 stores training times as integers that do not follow this formula"
created: 2026-05-22
---

SC2 stores unit training times as integer game loops internally. The community formula `seconds × 22.4` produces float values (e.g. Probe: 268.8) that differ from actual SC2 values (e.g. Probe: 272) in ways that cannot be predicted by rounding rules — the integers must be measured from replay data. When adding or updating a value in `trainTimeInLoops`, run `SC2TrainTimeCalibrationTest` and use its modal output as the source of truth. Uncalibrated values cause systematic 1-tick-early completions in `ReplayValidationHarness` for specific loop offsets.

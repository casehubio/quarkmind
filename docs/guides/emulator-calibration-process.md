# Emulator Calibration Process

Repeatable divergence → diagnose → fix → validate cycle for EmulatedGame accuracy.

## Overview

The tracker-driven accuracy baseline (`TrackerDrivenAccuracyBaselineTest`) measures how accurately EmulatedGame reproduces real SC2 unit production when given perfect tracker-event inputs. Divergences indicate EmulatedGame physics bugs — the unit WAS produced in the real game but EmulatedGame failed to produce it (or produced extra units).

## Tools

### ReplayDivergenceScanner (`@Tag("report")`)

Scans all oracle replays, reports per-replay per-unit-type deltas at 1/3/5/7min checkpoints. Identifies which replays have the worst divergences for each unit type.

```bash
mvn test -pl quarkmind-sc2 -Preport -Dtest=ReplayDivergenceScannerTest
```

**Output:** Ranked table of divergence entries (replay hash, player, unit type, checkpoint, GT vs Em count, delta). Summary by unit type at 5min.

**Key insight:** Positive deltas (Em > GT) usually indicate combat deaths not modeled by EmulatedGame. Negative deltas (Em < GT) indicate production physics bugs.

### ReplayDivergenceTracer (`@Tag("diagnostic")`)

Takes the worst-divergence replay for a configured unit type, re-runs it with an `IntentExecutionListener` to produce a per-tick causal timeline.

```bash
mvn test -pl quarkmind-sc2 -Pdiagnostic -Dtest=ReplayDivergenceTracerTest
```

Configure `TRACE_UNIT_TYPE` in the test to target different unit types. The tracer finds the worst underproduction replay automatically.

**Output:** Intent execution timeline (tick, building tag, success/fail, rejection reason), per-minute GT vs Em comparison, train diagnostics, first divergence tick with surrounding events.

### IntentExecutionListener

Callback interface on EmulatedGame that fires when a TrainIntent is processed or rejected. Captures the resolved building tag and rejection reason. Null-safe — no overhead when no listener is registered.

Rejection reasons: `building_not_found`, `wrong_building_type`, `production_blocked`, `insufficient_resources`, `queue_full`.

## The Cycle

1. **Run the scanner** to identify which unit types and replays have divergences
2. **Configure the tracer** with the target unit type, run it
3. **Read the causal timeline** — find the first divergence tick and the surrounding intent events
4. **Diagnose the root cause** from the rejection reason and building state
5. **Fix the emulator physics** (or TrackerIntentExtractor if the issue is building tag resolution)
6. **Run TrackerDrivenAccuracyBaselineTest** to verify improvement
7. **Run DualRunCalibrationTest and SC2PlaybookCalibrationTest** for regression checks

## Known Patterns

### wrong_building_type (354 rejections across 236 player runs)

TrackerIntentExtractor's `lookupBuildingTag()` sometimes resolves intents to the wrong building. The `wrong_building_type` check rejects these — the intent is dropped, causing underproduction. Naive rerouting (auto-resolving to the correct building type) causes cascading regressions because it floods production queues with previously-filtered intents. The root fix is improving `lookupBuildingTag()` in TrackerIntentExtractor.

### Overproduction from combat deaths

EmulatedGame doesn't model unit deaths. Players lose units to combat, so GT count drops while Em count stays high. This is expected behaviour, not a physics bug. The `min(em, gt)` accuracy metric handles this by capping at GT.

## Protocol

Per `calibration-milestones-are-waypoints`: accuracy targets are waypoints, not destinations. When a target is reached (or diminishing returns are hit), assess what the data revealed and replan — don't declare victory.

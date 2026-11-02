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

### wrong_building_type — RESOLVED (#485)

TrackerIntentExtractor's `lookupBuildingTag()` used to resolve intents to the wrong building type. Fixed in #485 via type-aware lookup (filters selection tags by building type map from tracker events) and `trackerDriven` flag on TrainIntent (skips economy gates for tracker-proven production). Wrong_building_type rejections now defer instead of dropping.

### insufficient_resources on tracker-driven intents — RESOLVED (#485)

Tracker-driven intents that correctly matched a building via `lookupBuildingTag()` were not marked as tracker-driven, causing them to hit economy gates. Fixed by adding `trackerDriven` field to `TrainIntent` record — set by TrackerIntentExtractor, read by EmulatedGame to skip economy checks.

### Overproduction from combat deaths

EmulatedGame doesn't model unit deaths. Players lose units to combat, so GT count drops while Em count stays high. This is expected behaviour, not a physics bug. The `min(em, gt)` accuracy metric handles this by capping at GT.

### insufficient_resources on tracker-driven research — RESOLVED (#490)

Same pattern as #485 but for `ResearchIntent`. Tracker-derived upgrades hit economy gates in `handleResearch()`. Fixed by adding `trackerDriven` field to `ResearchIntent` — skips resource checks for tracker-proven research. Upgrade accuracy improved from 75% to 100% at 3min.

### Uncalibrated train times — RESOLVED (#487)

22 formula-derived train times (N×22.4) replaced with empirical values from 118 oracle replays (v4.9.3). Oracle calibration uses abilLink-based modal approach across all races. AI Arena calibration test remains as cross-source comparison but no longer asserts SC2Data values — oracle is authoritative. Marauder accuracy improved to 100% at 5min (+1 unit).

## Accuracy Floor — 99.9%

Established in #489. The tracker-driven pipeline achieves 99.9% unit accuracy at the 5-min checkpoint (4 missing out of 6807 across 118 oracle replays). This is a **structural floor**, not a tick-quantization artifact:

- Running 2 extra ticks past the 5-min checkpoint recovers **zero** additional units
- The 4 missing units (Phoenix ×1, Medivac ×1, Reaper ×1, Hellion ×1) are each from different replays
- Root cause: building queue ordering — EmulatedGame's `shortestQueue` strategy distributes production differently than real SC2, causing some units to complete later

Closing the gap would require per-building intent routing matching real SC2's exact queue assignment, which is beyond the scope of the tracker-driven pipeline. 99.9% is the practical ceiling.

## Protocol

Per `calibration-milestones-are-waypoints`: accuracy targets are waypoints, not destinations. When a target is reached (or diminishing returns are hit), assess what the data revealed and replan — don't declare victory.

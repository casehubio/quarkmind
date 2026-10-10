# Terran SCV Production Throughput — Design Spec

**Issue:** #398 — Terran SCV count below real SC2 target  
**Branch:** issue-398-terran-scv-production-throughput  
**Target:** 48-50 SCVs at tick 305 (currently ~40)

## Problem

The Terran economy playbook produces ~40 SCVs at tick 305, vs 48-50 in real SC2 at the 5-minute mark. MULE income is validated (517 minerals surplus) but doesn't translate to more SCVs because the bottleneck is production throughput, not minerals.

### Root causes

1. **Supply depot timing** — Depots trigger at the supply cap (14, 20, 28...), causing supply blocks of ~9-21 ticks per boundary while the depot builds. Real SC2 players pre-build depots ~2 supply early.

2. **OC morph production blackout** — When the CC morphs to OC at 22 supply, new training is blocked for 25 ticks. CC-2 (ordered at 20 supply) takes 72 ticks to build, creating up to a ~48-tick window where no new SCVs can start training.

3. **No 2nd OC** — Standard Terran play morphs both CCs to OCs for double MULE income. The extra income funds depots to keep supply ahead of production.

4. **`drainBuildingQueues()` fidelity bug** — Queued units start training on a morphing building (doesn't check `isComplete`). In real SC2, the production queue pauses during morphs. This currently produces phantom SCVs during morphs — the wrong direction for fidelity even though it helps the count.

### What's correct

- In-progress SCV training completes normally during OC morph (`fireCompletions` fires on tick count, not `isComplete`) — matches real SC2.
- `handleTrain()` correctly blocks new external train requests on incomplete buildings.
- Queue depth limit of 5 is correct.
- `shortestQueue()` routing to multiple buildings works correctly for throughput.
- SCV train time (275 loops), CC build time (1600 loops), OC morph time (550 loops) are all calibrated.

## Changes

### 1. Playbook: economy-only-terran.yaml

Rewrite the Terran economy playbook build order using published SC2 macro build orders as reference. Key changes:

- **Pre-build depots** ~2 supply ahead of cap to eliminate supply blocks
- **Earlier CC-2** to reduce the production blackout during OC morph
- **2nd OC morph** on CC-2 completion for double MULE income
- **Continuous MULE calldowns** from both OCs
- **Updated assertions** at tick 305: SCV >= 48, minerals >= 400

The exact supply thresholds and ordering will be derived from published build orders (Spawning Tool, TerranCraft) and validated via the calibration test. The playbook is an economy-only macro build — no military units — so it won't match a specific competitive build exactly, but the production building timing, depot cadence, and morph order should follow standard macro Terran patterns.

### 2. EmulatedGame: drainBuildingQueues fidelity fix

Add an `isComplete` check in `drainBuildingQueues()` so queued units don't start training on a morphing (or building-under-construction) building.

```java
// In drainBuildingQueues(), before popping from queue:
// Find the building by tag and check isComplete
Building building = state.buildings().stream()
    .filter(b -> b.tag().equals(buildingTag) && b.isComplete())
    .findFirst().orElse(null);
if (building == null) continue;
```

This is a one-line guard. `handleTrain()` already has this check for new external requests — `drainBuildingQueues` is the only path that bypasses it.

### 3. Test coverage: morph-during-train scenarios

New test class or methods in `TerranEmulatedGameTest` covering:

- **In-progress SCV completes during OC morph** — start SCV training, morph CC to OC mid-training, assert SCV spawns on schedule
- **Queued SCVs pause during OC morph** — queue 2-3 SCVs, morph CC, assert no new training starts until morph completes, then queue resumes
- **2nd OC morph** — CC-2 completes, morphs to OC-2, assert MULE calldown works from both OCs
- **Continuous training from 2 OCs** — both OCs train SCVs simultaneously via queue routing

### 4. Calibration test assertion update

Update `SC2PlaybookCalibrationTest` (via the YAML assertions) to expect the new SCV target. The intermediate assertions (tick 61, tick 183) may also need updating to reflect the earlier CC-2 and better depot timing.

## Scope boundaries

**In scope:**
- economy-only-terran.yaml playbook rewrite
- `drainBuildingQueues()` isComplete guard
- Morph-during-train test coverage
- Calibration assertion updates

**Out of scope:**
- Military unit production (Barracks, etc.)
- Reactor/Tech Lab add-ons
- Other race playbooks (Protoss, Zerg)
- SimulatedGame changes (only EmulatedGame)
- Changes to `handleTrain()`, `handleMorph()`, or `fireCompletions()` (all verified correct)

## Testing plan

1. **Unit test:** Morph-during-train scenarios (new, plain JUnit)
2. **Calibration test:** `SC2PlaybookCalibrationTest` with updated Terran YAML — assert SCV >= 48 at tick 305
3. **Regression:** Run `mvn test -pl quarkmind-sc2` to verify no regressions from the `drainBuildingQueues` change
4. **Replay validation:** Run `mvn test -pl quarkmind-sc2 -Preport` to check divergence report isn't worsened by the fidelity fix

## References

- EmulatedGame.java:469-484 — `drainBuildingQueues()` (no isComplete check)
- EmulatedGame.java:350-431 — `handleTrain()` (has isComplete check)
- EmulatedGame.java:802-833 — `handleMorph()` (sets isComplete=false)
- PhysicsState.java:39-45 — `fireCompletions()` (tick-based, correct)
- economy-only-terran.yaml — current Terran playbook
- SC2Data.java — SCV train time 275, CC build 1600, OC morph 550
- SC2PlaybookCalibrationTest.java — calibration test
- Issue #398 — problem analysis and potential fixes

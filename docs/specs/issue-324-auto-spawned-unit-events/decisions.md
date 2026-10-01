## D1: Which auto-spawned unit types to synthesize

**Choice:** Implement three types: Larva (9,690 missing), MULE (623), Interceptor (322). Broodling+BroodlingEscort (1,726) moved to permanent gaps. Accept remaining types (Broodling, AdeptPhaseShift, LocustMP, InfestedTerransEgg, KD8Charge, Changeling variants) as permanent gaps.
**Alternatives:**
- All four high-volume types including Broodling — Broodling proven infeasible (no attack events in stripped replays)
- MULE only (MVP) — smallest increment but leaves Larva (largest gap) unaddressed
**Rationale:** MULE is command-based (economic signal). Larva is the largest volume gap. Interceptor is timer-based with an over-counting caveat (no death tracking) but provides composition signal. Broodling is infeasible — BroodLord attacks are auto-attacks with no CmdEvent in stripped replays, and there's no spatial data to approximate combat timing.
**Trade-offs:** Interceptor counts will be ceiling estimates (over-count) because stripped replays lack death events. Larva timer constants need replay calibration. Broodling gap accepted.
**Sources:** Issue #324 body (unit count table), StrippedReplayFeatureExtractor.java (UNIT_PYTHON_NAMES map), decision review R1-02 (Broodling infeasibility), R1-03 (Interceptor over-counting)
**Exploration:** quick
**Status:** revised

## D2: Synthesis architecture — split by mechanism

**Choice:** MULE wired into `AbilityMapping.dispatchHuman()` (command-based, follows established abilLink dispatch pattern). Larva and Interceptor synthesized as inline post-loop methods in `extract()`, following the `emitWarpGateAutoMorph()` precedent. No separate synthesizer class.
**Alternatives:**
- All types in a second-pass AutoSpawnSynthesizer class — can't access private SyntheticEvent/PlayerState types, creates coupling for Larva consumption tracking
- All types integrated into the main loop — unnecessary for timer-based types that only need post-loop parent counting
**Rationale:** MULE is fundamentally a command (CmdEvent with abilLink), matching the exact pattern of WarpGate warp-in, Archon merge, and all morph commands in dispatchHuman(). Timer-based types (Larva, Interceptor) follow the emitWarpGateAutoMorph() pattern — inline post-loop methods with full access to PlayerState and SyntheticEvent (both private). A separate class would require exposing private types or duplicating data structures.
**Trade-offs:** Larva synthesis needs Larva consumption counts from the main loop (train commands consume Larva). PlayerState must track per-base Larva consumption during the main loop to enforce the 3-per-base auto-spawn cap.
**Sources:** AbilityMapping.dispatchHuman() (abilLink dispatch), StrippedReplayFeatureExtractor.emitWarpGateAutoMorph() (post-loop precedent), decision review R1-04, R1-06, R1-08
**Depends on:** D1 (scope determines which mechanisms are needed)
**Exploration:** quick
**Status:** revised

## D3: Validation — separate calibration test + extend validation report

**Choice:** Two-tier validation: (1) A dedicated `AutoSpawnCalibrationTest` for discovering and validating timer constants (Larva spawn interval, Interceptor build time) from oracle replays, following the SC2TrainTimeCalibrationTest pattern. (2) Extend StrippedReplayValidationTest's divergence report to include auto-spawn types (they already appear on the oracle side), adding per-type threshold assertions for Larva (within 20%).
**Alternatives:**
- Only extend validation test — conflates timer constant calibration with pipeline accuracy measurement
**Rationale:** Calibration (discovering constants) and validation (measuring pipeline accuracy) are different concerns with established separate patterns in the codebase. SC2TrainTimeCalibrationTest discovers train time constants; StrippedReplayValidationTest measures overall extraction accuracy. Auto-spawn follows the same split.
**Trade-offs:** Two test classes instead of one. The calibration test is a diagnostic (@Tag("diagnostic")), the validation test remains a report (@Tag("report")).
**Sources:** SC2TrainTimeCalibrationTest.java, StrippedReplayValidationTest.java, decision review R1-07
**Exploration:** quick
**Status:** revised

## D4: WITHDRAWN — AutoSpawnSynthesizer class

**Status:** withdrawn (superseded by D2 revision — inline methods replace separate class)

## D5: MULE abilLink discovery — diagnostic test first

**Choice:** Write a diagnostic test to discover the MULE calldown abilLink from oracle replays before implementing MULE synthesis. Follow the established AbilityDiscoveryCalibrationTest pattern. The discovered abilLink becomes a new `ABIL_MULE_CALLDOWN` constant in AbilityMapping, wired into `dispatchHuman()`.
**Alternatives:**
- Use community-sourced constant and validate later — faster to start but riskier if human replay abilLinks differ
**Rationale:** Human replay abilLinks can differ from bot/emulated values (observed for other abilities). The calibration pattern (correlating CmdEvents with tracker UnitBorn events) provides ground truth.
**Trade-offs:** Adds a diagnostic test step before implementation. Small cost for high confidence.
**Sources:** AbilityDiscoveryCalibrationTest.java, PP-20260522-572156 (train-times-require-calibration protocol), AbilityMapping.java (ABIL constants)
**Depends on:** D2 (wiring target is AbilityMapping.dispatchHuman)
**Exploration:** quick
**Status:** captured

## D6: Timer constant calibration approach

**Choice:** Calibrate Larva spawn interval and Interceptor build time from oracle replays via a new `AutoSpawnCalibrationTest` (@Tag("diagnostic")). For Larva: correlate oracle UnitBorn("Larva") events with Hatchery/Lair/Hive completion times, using modal analysis across replays (same approach as SC2TrainTimeCalibrationTest). Must distinguish auto-spawned Larva from Inject Larva (Queen ability produces 3 additional Larva). For Interceptor: correlate oracle UnitBorn("Interceptor") events with Carrier birth times, extract inter-birth intervals.
**Alternatives:**
- Use community values (Larva ~11s, Interceptor ~9s) without replay calibration — violates protocol PP-20260522-572156
**Rationale:** Standing protocol requires replay-calibrated timing constants. The community formula (seconds × 22.4) produces wrong values for training times; likely wrong for auto-spawn intervals too.
**Trade-offs:** Calibration test requires careful filtering to separate auto-spawn from ability-triggered spawns (Inject Larva vs auto-Larva).
**Sources:** PP-20260522-572156 (timing calibration protocol), SC2TrainTimeCalibrationTest.java (calibration pattern)
**Depends on:** D3 (calibration test is part of the two-tier validation approach)
**Exploration:** quick
**Status:** captured

## D7: Event types for auto-spawned units

**Choice:** Match oracle event types: Larva → UnitBorn (instant appearance), Interceptor → UnitBorn (instant appearance after build timer), MULE → UnitBorn (calldown lands instantly). Verify these against oracle data in the calibration test.
**Alternatives:**
- UnitInit+UnitDone for all (timed construction) — doesn't match oracle behaviour for auto-spawned units
**Rationale:** Oracle replays emit UnitBorn for Larva and Interceptor (they "appear" when the timer completes). MULE appears as UnitBorn in the tracker. The validation test compares UnitBorn and UnitInit counts separately, so using the wrong event type would show as divergence.
**Trade-offs:** Must verify oracle behaviour in the calibration test — if oracle uses UnitInit for any of these, adjust accordingly.
**Sources:** StrippedReplayValidationTest.java (separate UnitBorn/UnitInit tracking), decision review R1-12
**Depends on:** D6 (calibration test verifies oracle event types)
**Exploration:** quick
**Status:** captured

## D8: Add auto-spawn types to classifier pipeline

**Choice:** Add MULE, Larva, and Interceptor to the Python classifier's UNITS list (sc2egset_extractor.py) and Java FeatureIndexMaps.N_UNITS in the same branch. Without this, synthesized events have no downstream consumer.
**Alternatives:**
- Separate issue for classifier integration — events sit unused until follow-up
- Coverage measurement only — no classifier integration, value is in the validation report
**Rationale:** Synthesizing events that no consumer reads is waste. Adding types to the UNITS list is a small change (3 entries in Python, dimension increment in Java) that closes the loop.
**Trade-offs:** Increases N_UNITS dimension, which means existing trained models are incompatible. Acceptable for pre-release stage.
**Sources:** quarkmind-classifier/src/sc2egset_extractor.py (UNITS list), FeatureIndexMaps.java (N_UNITS), decision review R1-01
**Depends on:** D1 (scope determines which types to add)
**Exploration:** quick
**Status:** captured

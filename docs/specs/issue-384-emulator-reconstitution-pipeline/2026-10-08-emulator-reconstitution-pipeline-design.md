# Emulator-Driven Reconstitution Pipeline — Design Spec

**Epic:** #384
**Date:** 2026-10-08
**Scope:** Calibrate EmulatedGame physics from tournament replays, reconstitute 151K stripped Blizzard ladder replays at higher fidelity, train the ONNX strategy classifier on expanded data.
**Decisions:** See `decisions.md` in this directory for D1–D7.

---

## 1. Problem Statement

The ONNX strategy classifier currently trains on ~48K samples per matchup from tournament data only. 151K Blizzard ladder replays are available but stripped (no tracker events, ~43% of CmdEvents). The existing reconstitution path (`StrippedReplayFeatureExtractor`) extracts features directly from CmdEvents without physics simulation, producing ~88% unit accuracy and ~79% building accuracy against oracle replays.

A separate path exists: `EmulatedGame` simulates SC2 physics (movement, combat, economy, production) from replay intents. If its accuracy can be improved, it can reconstitute stripped replays at higher fidelity than command-event parsing alone — particularly for economy features where saturation curves matter.

Current EmulatedGame accuracy against 118 oracle replays (from `emulated-game-accuracy-baseline.md`):
- Units: 36.4% at 5-min, 27.1% at 7-min (Terran/Zerg produce 0 emulated units)
- Buildings: 100% — but this is trivially achieved because the validation harness injects buildings from ground truth; EmulatedGame does not produce buildings itself
- Upgrades: 0% (no ResearchIntents applied)
- Economy MAPE: ~200% (flat mining rate, no technology spending)

The root cause triage identifies 8 independent failure modes across 3 categories: units (3: race-specific seeding, probe over-count, adept/phoenix under-count), upgrades (1: no ResearchIntents), economy (4: flat mining rate, no technology spending, race model mismatch, economy spending under-count).

## 2. Goals and Success Criteria

| Metric | Current | Target | Priority |
|--------|---------|--------|----------|
| Unit accuracy at 5-min | 36.4% (harness-synced buildings) | ≥ 80% | Primary |
| Building accuracy (emulated) | not measured (harness injects from GT) | ≥ 79% (match StrippedReplay baseline) | Primary |
| Upgrade accuracy | 0% | > 0% (any improvement) | Secondary |
| Economy MAPE | ~200% | ≤ 50% | Secondary (don't gate reconstitution) |
| ONNX classifier improvement | baseline | measurable improvement from expanded data | Primary |

Note: the existing `ReplayValidationHarness` syncs buildings, supply, and vespene from ground truth. For stripped-replay reconstitution (Phase 3), EmulatedGame must produce buildings from extracted commands — a fundamentally different operating mode. See §6.

## 3. Architecture Overview

Four phases, with explicit validation gates between them:

```
Phase 1: Baseline                Phase 2: Calibrate
┌─────────────────┐             ┌──────────────────────────────┐
│ Run accuracy     │             │ Extract ALL command types    │
│ baseline on      │──(gate)──▶ │ (Build, Upgrade, Morph,      │
│ available 631    │             │  Train, Cancel)              │
│ tournament       │             │ Add MorphIntent, BuildIntent │
│ replays          │             │ Fix economy physics          │
└─────────────────┘             │ Re-run baseline after each   │
                                └──────────┬───────────────────┘
                                           │
                                      (gate: ≥80% units)
                                           │
Phase 3: Reconstitute                      ▼
┌──────────────────────────────────────────────────────┐
│ StandaloneEmulatedReconstitutionTest                 │
│ - Read stripped .SC2Replay from ladder               │
│ - Extract ALL commands via expanded                  │
│   ReplayCommandExtractor (Build, Train, Upgrade,     │
│   Morph, Cancel)                                     │
│ - Run EmulatedGame standalone (NO harness — no GT    │
│   building sync, no supply sync, no vespene sync)    │
│ - EmulatedGame builds its own buildings, manages     │
│   its own supply and economy from extracted commands │
│ - Convert tick-by-tick state to synthetic-event JSON │
│ - Write to data/reconstituted_emulated/              │
│ - Tag with extractionMethod=emulator                 │
└──────────────────────┬───────────────────────────────┘
                       │
                  (gate: compare vs oracle)
                       │
Phase 4: Train         ▼
┌──────────────────────────────────────────────┐
│ Characterise expanded dataset                │
│ - Sample counts, class distributions         │
│ - Feature quality per extractionMethod       │
│ Update reconstitution_pipeline.py            │
│ - Read from reconstituted_emulated/          │
│ - Quality-aware training                     │
│ Train per-matchup ONNX models                │
│ - Architecture decision deferred to here     │
│ Compare against current tournament-only model│
└──────────────────────────────────────────────┘
```

## 4. Phase 1 — Accuracy Baseline on Tournament Data

### 4.1 Objective

Extend the existing per-race accuracy baseline to all available tournament replays. The existing `EmulatedGameAccuracyBaselineTest` already runs against 118 oracle replays, iterates both players, and resolves per-race models correctly. Phase 1 extends this to the remaining tournament datasets.

### 4.2 Approach

Create a `TournamentAccuracyBaselineTest` (or extend the existing test) to run against all tournament datasets with tracker events:
- Oracle (118, v4.9.3)
- AI Arena (29)
- HSC XXVII (61)
- IEM PyeongChang (62)
- ASUS ROG (107)
- DreamHack Dallas (64)
- Esports World Cup (113)
- FEL Cracow (77)

Total: 631 replays. Report accuracy per race and per category at 1/3/5/7 min checkpoints. Write results to `docs/benchmarks/`.

### 4.3 Deliverables

- Per-race, per-dataset accuracy baseline document
- Triage of which failure modes are race-specific vs. universal vs. patch-specific

## 5. Phase 2 — Calibrate Emulator Physics

### 5.1 Fix Ordering

Follow the dependency order from the existing root cause triage. Two parallel streams: extraction completeness (Batches 1–2) and physics accuracy (Batch 3).

**Batch 1 — Extraction completeness (blocks unit accuracy):**

The existing `EmulatedGameAccuracyBaselineTest` iterates both players and `ReplayValidationHarness` correctly resolves per-race models. The root cause of 0% Terran/Zerg unit accuracy is in the extraction or intent application layer.

`ReplayCommandExtractor.extract()` currently discards 4 of 6 `ReplayCommand` types:
- `BuildCommand` → discarded (buildings only come from harness GT sync)
- `UpgradeCommand` → discarded
- `MorphCommand` → discarded
- `CancelCommand` → discarded
- `IntentCommand` → kept (wraps `TrainIntent`)

`AbilityMapping` already parses all command types from game events — the information exists but `ReplayCommandExtractor` throws it away. Fixes needed:

1. **Extract all command types:** Modify `ReplayCommandExtractor` to emit `BuildCommand`, `UpgradeCommand`, and `MorphCommand` alongside `TrainIntent`.
2. **Add `MorphIntent`:** The `Intent` sealed interface currently permits only: `BuildIntent`, `TrainIntent`, `AttackIntent`, `MoveIntent`, `BlinkIntent`, `MuleCalldownIntent`, `ResearchIntent`. Add `MorphIntent` for race-critical morphs:
   - Zerg: Baneling, Ravager, Lurker, Overseer, Brood Lord, Lair/Hive/Greater Spire
   - Terran: Orbital Command, Planetary Fortress, Hellbat
   - Protoss: Archon
3. **Apply all command types in EmulatedGame:** Extend `EmulatedGame.applyIntent()` to handle `BuildIntent`, `ResearchIntent`, `MorphIntent`.

The existing investigation tag from the building tag mismatch hypothesis should also be checked: `TrainIntent`s carry building tags from the live selection state (`selection.first()`), which may not match harness-injected replay tags.

**Batch 2 — Upgrade mechanics (independent):**
- Extract `UpgradeCommand` as `ResearchIntent` (part of Batch 1 extraction work)
- Apply `ResearchIntent` in `EmulatedGame` — track upgrade completion and costs

**Batch 3 — Economy physics (independent of Batches 1–2):**
- Implement SC2 worker saturation curves (replace flat `mineralIncomePerTick`)
- Track technology and upgrade spending in `EconomyTracker`
- Track economy spending for harness-injected buildings

### 5.2 Validation Loop

After each batch:
1. Re-run `EmulatedGameAccuracyBaselineTest` against oracle replays
2. Update `docs/benchmarks/emulated-game-accuracy-baseline.md`
3. Run `DivergenceRegressionTest` to ensure no regression

### 5.3 Gate: Phase 2 → Phase 3

Unit accuracy ≥ 80% at 5-min checkpoint on oracle replays. Economy MAPE improvement is desired but not a gate.

### 5.4 Protocols

- SC2Data timing constants must be calibrated from replay ground truth, not derived from formulae (PP-20260522-572156)
- SC2 spatial constants must be calibrated from replay ground truth (PP-20260805-837016)
- Command extraction logic lives in dedicated extractor classes, not SimulatedGame subclasses (PP-20260528-612dee)

## 6. Phase 3 — Reconstitute Ladder Replays

### 6.1 Architectural Distinction: Standalone vs. Harness Mode

The existing `ReplayValidationHarness` cannot run on stripped replays. It requires tracker events for:
- Building injection (`syncBuildings()` from ground-truth `GameState`)
- Supply cap sync (`setSupplyCapForHarness()` from post-tick GT)
- Vespene sync (`setVespeneForHarness()` from pre-tick GT)
- Building tag matching (harness-injected tags must match `TrainIntent` building tags)

Without tracker events, `ReplaySimulatedGame` throws `IllegalArgumentException` at parse time.

Phase 3 requires a **standalone EmulatedGame mode** that does not depend on ground truth:
- Buildings come from extracted `BuildCommand`s (Phase 2 Batch 1 enables this)
- Supply comes from building completion (Pylons, Supply Depots, Overlords via `RaceModel`)
- Vespene and minerals come from EmulatedGame's own economy simulation
- Building tags are generated by EmulatedGame, not matched from replay

This is a fundamentally different operating mode from the validation harness, which uses GT-synced buildings to isolate unit production accuracy. The standalone mode tests the full EmulatedGame stack: building construction + resource management + unit production + economy.

### 6.2 New Test: StandaloneEmulatedReconstitutionTest

A `@Tag("report")` test that:
1. Iterates all `.SC2Replay` files in `blizzard_ladder/4.9.3/` and `blizzard_ladder/4.10.1/`
2. For each replay, for each player:
   - Detects player race via replay details
   - Extracts all commands via expanded `ReplayCommandExtractor` (Build, Train, Upgrade, Morph, Cancel)
   - Runs EmulatedGame in standalone mode (no GT sync)
   - Records tick-by-tick state changes
3. Converts state changes to synthetic-event JSON (see §6.4)
4. Writes JSON to `data/reconstituted_emulated/<dataset>/`
5. Adds `reconstitution.extractionMethod = "emulator"` to metadata

### 6.3 Output Directory

`quarkmind-classifier/data/reconstituted_emulated/` — parallel to existing `data/reconstituted/`. Both preserved for A/B comparison.

### 6.4 Output Format Bridge

The existing reconstituted JSON uses a synthetic-event format: timestamped `UnitBornEvent`, `UnitDoneEvent`, `UnitDiedEvent`, `UpgradeEvent`, `PlayerStatsEvent` records. This is what `reconstitution_pipeline.py` → `sc2egset_extractor.extract_replay()` expects.

`EmulatedGame.snapshot()` returns a `GameState` with live counts, not event timelines. Two approaches:

**Preferred:** Emit synthetic events from EmulatedGame's tick-by-tick state changes — diff each tick's `GameState` against the previous to produce `UnitBornEvent` (new unit appeared), `UnitDiedEvent` (unit disappeared), `UnitDoneEvent` (building completed), etc. This produces the same event-timeline format the Python pipeline expects, with no Python changes needed.

**Fallback:** If event-diffing is too noisy, emit checkpoint-based snapshots at 1/3/5/7 min and modify `reconstitution_pipeline.py` to accept the snapshot format alongside the event format.

### 6.5 Validation

Compare emulator-reconstituted output against oracle replays (which have ground truth via `TrackerEventFeatureExtractor`). Create `TrackerVsEmulatedComparisonTest` analogous to `TrackerVsStrippedComparisonTest`.

## 7. Phase 4 — Train Classifier

### 7.1 Data Characterisation (before architecture decisions)

Before changing the model, characterise the expanded dataset:
- Total sample count per matchup
- Class distribution per archetype
- Feature quality comparison: tracker vs. command-based vs. emulator-based
- Identify whether data volume or data quality is the binding constraint

### 7.2 Architecture Decision

Deferred until data characterisation is complete. The current `StrategyClassifier` already has Conv1d temporal encoding, sinusoidal positional encoding, multi-head self-attention (4 heads), dual-stream player/opponent architecture, and hierarchical classification. Architecture changes should be shaped by actual data properties, not projections.

Candidates identified for evaluation:
- Deeper attention (2-3 stacked blocks with residual connections)
- Cross-attention between player and opponent streams
- Dilated convolutions for multi-scale temporal patterns
- Hyperparameter tuning of existing architecture (may be sufficient)

### 7.3 Quality-Aware Training

The training pipeline should distinguish samples by `extractionMethod` (added to reconstitution metadata alongside the existing `datasetSource` field which tracks dataset name):
- `tracker` — ground truth, ~100% accuracy
- `stripped` — command-event based, ~88% unit accuracy
- `emulator` — physics-based, accuracy TBD after Phase 2

Options: sample weighting, extractionMethod as a feature, curriculum learning (high-quality first, then fine-tune on noisy data).

### 7.4 Evaluation

Train per-matchup models and compare against current tournament-only models:
- Cross-validation accuracy
- Per-archetype precision/recall
- Calibration curves (confidence vs. accuracy)

## 8. Data Downloads

Issue-driven, non-blocking, prioritised by accessibility:

1. **SC2ReplayStats** — REST API, 4.4M replays, most accessible
2. **Spawning Tool packs** — HSC XXVIII/XXIX (Google Drive access issues to resolve)
3. **AI Arena Data API** — token-based, straightforward
4. **Blizzard Ladder API** — highest value but requires Developer Portal credentials (start credential setup in parallel; auth risk flagged)

All downloads go to existing `data/replay_packs/` or `data/sc2egset/` directories. Never delete existing data (per retention policy and feedback memory).

## 9. Risk Register

| Risk | Impact | Mitigation |
|------|--------|------------|
| Terran/Zerg 0% is a deep extraction issue, not a simple mapping gap | Phase 2 scope grows | Triage-guided approach identifies scope before committing |
| Standalone EmulatedGame mode requires significant new infrastructure | Phase 3 scope larger than expected | Phase 2 extraction work (Build/Morph/Upgrade commands) is prerequisite; standalone mode is incremental on top |
| Blizzard API credentials unavailable or rate-limited | Less ladder data | SC2ReplayStats and AI Arena provide alternative volume |
| EmulatedGame accuracy plateaus below 80% | Phase 3 produces noisy data | Quality-aware training limits noise injection; fall back to command-based reconstitution |
| Expanded data doesn't improve classifier | Wasted reconstitution effort | Cross-validation comparison before replacing production models |
| Disk space for second reconstituted dataset | ~10-15GB | Acceptable; never delete originals |
| Output format bridge (GameState → synthetic events) may be lossy | Training data quality | Validate against oracle replays before full reconstitution run |

## References

- `docs/benchmarks/emulated-game-accuracy-baseline.md` — current accuracy baseline and root cause triage
- `docs/benchmarks/oracle-accuracy-baseline.md` — StrippedReplayFeatureExtractor accuracy
- `docs/benchmarks/restoration-coverage.md` — per-dataset tracker vs stripped coverage
- `quarkmind-sc2/.../ReplayValidationHarness.java` — harness architecture (GT-synced mode)
- `quarkmind-sc2/.../EmulatedGame.java` — emulator physics
- `quarkmind-sc2/.../RaceModel.java` — race model plugin seam
- `quarkmind-sc2/.../ReplayCommandExtractor.java` — intent extraction from replays
- `quarkmind-sc2/.../AbilityMapping.java` — command type dispatch (already handles Build, Upgrade, Morph)
- `quarkmind-sc2/.../StrippedReplayFeatureExtractor.java` — existing command-based reconstitution (handles morphs)
- `quarkmind-classifier/src/model.py` — current StrategyClassifier architecture
- `quarkmind-classifier/src/reconstitution_pipeline.py` — reconstituted JSON → training data
- `quarkmind-classifier/data/DATA_SOURCES.md` — data inventory and retention policy
- PP-20260522-572156 — SC2Data timing calibration protocol
- PP-20260805-837016 — SC2 spatial calibration protocol
- PP-20260528-612dee — extractor separation protocol
- `decisions.md` — design decisions D1–D7

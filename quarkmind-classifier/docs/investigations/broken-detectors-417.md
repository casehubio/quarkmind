# Investigation: Broken Binary Detectors (#417)

## Summary

Two binary detectors regressed/stayed broken after expanding training data from 148K to 1.57M replays:

| Detector | Accuracy | Precision | FPR | Temperature |
|----------|----------|-----------|-----|-------------|
| `mid_ground_heavy_vs_terran` (MECH_PUSH) | 20.0% | 11.1% | 80.0% | -3.81 |
| `late_ground_army_vs_protoss` (COLOSSUS_PUSH) | 16.1% | 10.3% | 85.7% | -4.58 |

Both have **negative temperatures** — the model learned inverted predictions.

## Root Cause: Temporal Label Leakage

The labelling pipeline assigns labels based on the **full-game** build order (all events through game end), but the model sees features from a **specific time window** (minutes 3-4 for mid, minute 5 for late). When the full-game label describes a strategy that isn't yet visible in the features, the model can't learn the correct signal.

### mid_ground_heavy_vs_terran (MECH_PUSH at minutes 3-4) — CONFIRMED

Truncation analysis of 14,240 MECH_PUSH-labelled Terran replays:

| Truncation | MECH_PUSH | MACRO_ECONOMY | BIO_TIMING | TECH_RUSH | Other |
|------------|-----------|---------------|------------|-----------|-------|
| 3 min | **9.1%** | 42.0% | 22.6% | 25.3% | 1.0% |
| 4 min | **33.9%** | 21.4% | 33.1% | 7.8% | 3.7% |
| 5 min | **58.6%** | 4.2% | 32.4% | 1.9% | 2.7% |
| 7 min | **79.8%** | 0.7% | 17.3% | 0.6% | 1.6% |
| 10 min | **94.6%** | 0.1% | 4.2% | 0.1% | 1.0% |

**At minutes 3-4, 66-91% of MECH_PUSH replays are feature-indistinguishable from other archetypes.** The model is trained to predict "MECH_PUSH" for samples that look identical to MACRO_ECONOMY, BIO_TIMING, and TECH_RUSH — which are all in the negative class. This poisons learning because the positive and negative classes overlap in feature space.

**Why the model inverts:** At minutes 3-4, mech players have invested in Factory infrastructure, resulting in fewer Barracks and fewer bio units than bio players. The feature pattern for "future mech" looks LESS aggressive than average, causing the model to predict "negative" for actual positives. Temperature calibration tries to correct by inverting (T=-3.81), which makes everything worse.

**Control comparison:** BIO_TIMING at minutes 3-4 has 66.5% label stability (minute 4) — worse than MECH_PUSH at late phase but enough for the model to learn. The critical difference: BIO_TIMING's misclassified fraction mostly becomes MACRO_ECONOMY (different feature signature), while MECH_PUSH's misclassified fraction becomes BIO_TIMING (similar early features).

### late_ground_army_vs_protoss (COLOSSUS_PUSH at minute 5) — PARTIALLY CONFIRMED

Truncation analysis of 2,416 COLOSSUS_PUSH-labelled Protoss replays:

| Truncation | COLOSSUS_PUSH | BLINK_STALKER | NONE | Other |
|------------|---------------|---------------|------|-------|
| 3 min | 22.4% | 43.6% | 15.9% | 18.1% |
| 4 min | 67.3% | 22.9% | 4.7% | 5.1% |
| 5 min | **84.3%** | 12.7% | 1.2% | 1.7% |
| 7 min | 94.7% | 3.2% | 0.4% | 1.7% |

**84% label stability at minute 5 should be sufficient** — the mid detector works at 80% with only 67% stability. The root cause is a combination of:

1. **Negative class composition shift at minute 5:** Aggressive strategies (PROXY=37.8%, CANNON_RUSH=20.5% of vs_protoss) produce short games that often end before minute 5. This depletes the easy negatives from the minute-5 pool, enriching it with BLINK_STALKER, AIR_SUPERIORITY, and MACRO_ECONOMY — strategies that share more Protoss infrastructure features with COLOSSUS_PUSH.

2. **Small positive class:** COLOSSUS_PUSH is only 2.7% of vs_protoss labels. At minute 5, with only 8,320 training positives, the model has limited signal to learn from, especially when remaining negatives are harder to distinguish.

3. **Feature-space confusion at minute 5:** By minute 5, all non-cheese Protoss builds have converged on similar infrastructure (Nexus, Gateway, CyberneticsCore, tech choice). The distinguishing feature (RoboticsBay) may not appear until minute 5-7 in many COLOSSUS_PUSH games.

## Counterpart Analysis — Why These Fail While Others Work

| Detector | Target | Accuracy | Why it works/fails |
|----------|--------|----------|-------------------|
| mid_ground_heavy_vs_terran | MECH_PUSH min 3-4 | 20% | 66-91% label mismatch |
| late_ground_army_vs_terran | MECH_PUSH min 5 | 85% | 59% label match — enough signal |
| mid_ground_heavy_vs_protoss | COLOSSUS_PUSH min 3-4 | 80% | 67% match + easy negatives (cheese) |
| late_ground_army_vs_protoss | COLOSSUS_PUSH min 5 | 16% | 84% match but hard negatives |
| mid_ground_heavy_vs_zerg | HYDRA_PUSH min 3-4 | 75% | Distinctive tech path (HydraliskDen) |
| late_ground_army_vs_zerg | HYDRA_PUSH min 5 | 90% | Distinctive + easy negatives |

## Recommendations

### Option A (Recommended): Time-aware labelling

Change the labelling pipeline to assign labels based on the build order truncated to the detector's time window. A replay is only labelled MECH_PUSH at minute 4 if the mech indicators are present by minute 4.

**Implementation:** Add a `cutoff_min` parameter to `rule_based_label()` (or wrap it), passed through from the binary detector's time window during training data preparation. Labels become time-specific rather than full-game.

**Trade-off:** This changes the detector's semantics — it would detect "mech is happening NOW" rather than "this game WILL be mech." For game AI, the former is more useful: you can't react to what you can't observe.

### Option B: Drop these specific (detector, matchup) combinations

Remove `mid_ground_heavy_vs_terran` and `late_ground_army_vs_protoss` from the catalog. MECH_PUSH detection at minutes 3-4 is fundamentally unreliable — mech transitions aren't observable that early. COLOSSUS_PUSH at minute 5 works better (84% label stability) but the feature-space confusion makes it fragile.

**Trade-off:** Loses coverage for two strategically important detection points.

### Option C: Restructure into detection windows

Instead of forcing all detectors into fixed phase windows, let each (archetype, matchup) pair specify the EARLIEST minute where detection is reliable. Based on the truncation analysis:

| Archetype | vs_terran | vs_protoss | vs_zerg |
|-----------|-----------|------------|---------|
| MECH_PUSH | min 5+ (59% stable) | - | - |
| COLOSSUS_PUSH | - | min 4+ (67% stable) | - |
| BIO_TIMING | min 4+ (67% stable) | - | - |

**Trade-off:** More detectors, each at the right time point, but more complex catalog structure.

## Implementation

**Option A implemented:** Time-aware labelling in both data preparation pipelines.

### Changes

- `src/prepare_replay_pack.py` — `_extract_from_game_json()` now truncates build order to each minute before calling `label_replay()`. Each sample carries its own minute-specific label.
- `src/prepare_real_data.py` — `process_single_zip()` same change for the SC2EGSet ZIP pipeline.
- `tests/test_time_aware_labelling.py` — 6 tests: 3 verifying truncation semantics, 3 verifying per-minute labels in the extraction pipeline.

### Validation Results (4.9.3 dataset, 148K replays)

Regenerated blizzard_ladder_4.9.3 from reconstituted JSON using time-aware pipeline,
then retrained all 23 binary detectors.

#### Label distribution shift (vs_terran MECH_PUSH)

| Minute | Old count | New count | Delta |
|--------|----------|----------|-------|
| 2 | 10,125 | 351 | -96.5% |
| 3 | 10,115 | 1,856 | -81.7% |
| 4 | 9,983 | 6,363 | -36.3% |
| 5 | 9,605 | 12,135 | +26.3% |

#### Broken detector results

| Detector | Before Acc | Before Prec | After Acc | After Prec | Status |
|----------|-----------|------------|----------|-----------|--------|
| `mid_ground_heavy_vs_terran` | 20.0% | 11.1% | **84.8%** | **74.4%** | **FIXED** |
| `late_ground_army_vs_protoss` | 16.1% | 10.3% | 66.7% | 0.0% | Insufficient data (514 pos) |

**`mid_ground_heavy_vs_terran` validated:** +64.8pp accuracy improvement. The model now has positive
temperature and learns correct class boundaries. Time-aware labelling eliminates the feature-space
confusion that caused inverted predictions.

**`late_ground_army_vs_protoss` inconclusive:** Only 514 COLOSSUS_PUSH positives at minute 5 in the
4.9.3 dataset (vs 8,320 in the full 1.57M dataset). Predicted all-negative — needs full dataset
regeneration to validate.

#### Healthy detector comparison

| Detector | Old (full data) | New (4.9.3 only) | Notes |
|----------|----------------|-------------------|-------|
| `late_ground_army_vs_terran` | 84.9% | **90.7%** | Improved +5.8pp |
| `mid_ground_heavy_vs_protoss` | 80.1% | 63.8% | Lower — less data |

### Full dataset retrain (2026-10-20)

Regenerated all 1.57M ladder replays (4.9.1 + 4.9.2) with time-aware labelling pipeline,
then retrained all 23 binary detectors on the full 13-chunk dataset.

#### All three broken detectors fixed

| Detector | Before | After | Fix |
|----------|--------|-------|-----|
| `mid_ground_heavy_vs_terran` | 20.0% | **92.2%** | Time-aware labelling |
| `late_ground_army_vs_protoss` | 16.1% | **84.2%** | Time-aware labelling (needed full dataset — 8,320 positives vs 514 in 4.9.3 alone) |
| `early_rush_vs_terran` | — | **79.2%** | Per-detector RNG (new regression from shared RNG, not a labelling issue) |

#### early_rush_vs_terran regression — root cause

Shared `rng` in `load_all_detectors_from_chunks`: all detectors in a batch consume from the
same random generator during negative downsampling. Which negatives a detector trains on
depends on batch composition and processing order. `early_rush_vs_terran` is borderline
(24.7% RUSH at minute 2 — Terran rushes are hard to distinguish from standard openings at
that point), making it sensitive to the specific negatives selected.

Trained in isolation: 75-79% accuracy, positive temperature. In the batched run with the
old shared RNG: model hit a bad local minimum → negative temperature → inverted predictions.

**Fixes applied:**
1. Per-detector RNG — `det_rngs[key] = np.random.default_rng(rng.integers(2**63))` at batch
   start. Each detector's downsampling is deterministic and independent of batch composition.
2. Temperature clamp — `if t <= 0: t = 1.0` in `find_optimal_temperature`. Safety net so
   inverted calibration is caught rather than deployed.

#### Full comparison (all 23 detectors)

| Detector | Pre-timeaware | Post-timeaware |
|----------|--------------|----------------|
| early_cheese_vs_protoss | skipped | 84.2% |
| early_greedy_vs_protoss | skipped | 74.2% |
| early_greedy_vs_terran | skipped | 65.9% |
| early_greedy_vs_zerg | skipped | 83.5% |
| early_rush_vs_protoss | skipped | 76.8% |
| early_rush_vs_terran | skipped | 79.2% |
| early_rush_vs_zerg | skipped | 84.3% |
| late_air_superiority_vs_protoss | skipped | 87.8% |
| late_air_superiority_vs_terran | skipped | 66.7% |
| late_ground_army_vs_protoss | 16.1% | **84.2%** |
| late_ground_army_vs_terran | 84.9% | **95.2%** |
| late_ground_army_vs_zerg | skipped | 94.6% |
| mid_air_tech_vs_protoss | skipped | 91.4% |
| mid_air_tech_vs_terran | skipped | 93.0% |
| mid_air_tech_vs_zerg | skipped | 88.0% |
| mid_ground_heavy_vs_protoss | 80.1% | 82.7% |
| mid_ground_heavy_vs_terran | 20.0% | **92.2%** |
| mid_ground_heavy_vs_zerg | skipped | 82.2% |
| mid_hidden_tech_vs_protoss | skipped | 89.7% |
| mid_hidden_tech_vs_terran | skipped | 97.1% |
| mid_timing_attack_vs_protoss | skipped | 82.2% |
| mid_timing_attack_vs_terran | skipped | 97.4% |
| mid_timing_attack_vs_zerg | skipped | 98.3% |

All 23 detectors have positive temperatures. No inversions.

### Remaining work

1. Deploy updated ONNX models to `src/test/resources/models/strategy/`

## Data

Investigation scripts: `/tmp/temporal_label_analysis.py`, `/tmp/investigate_colossus.py`
Detector manifest: `quarkmind-classifier/output/detectors/detector_manifest.json`
Pre-time-aware baseline: `quarkmind-classifier/output/detectors/detector_manifest_pre_timeaware.json`

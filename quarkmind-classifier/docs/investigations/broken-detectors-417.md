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

### Next steps

1. Regenerate training data with the updated pipeline
2. Retrain broken detectors to verify accuracy improvement
3. Compare label distributions before/after to quantify the change

## Data

Investigation scripts: `/tmp/temporal_label_analysis.py`, `/tmp/investigate_colossus.py`
Detector manifest: `quarkmind-classifier/output/detectors/detector_manifest.json`

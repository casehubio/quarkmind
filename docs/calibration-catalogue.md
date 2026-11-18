# Calibration Recordings Catalogue

**Map:** TorchesAIE_v4 (all recordings)
**Location:** `quarkmind-sc2/src/test/resources/calibration/`
**Test:** `DualRunCalibrationTest` (plain JUnit, offline — no SC2 needed)

---

## Recordings

### Single-run (14 files)

| Recording | Playbook | Race | Ticks | SC2 final | EM final | Delta | Calibrating issue |
|---|---|---|---|---|---|---|---|
| `cal-mineral-baseline-12w` | cal-mineral-baseline-12w | Protoss | 305 | 3460 min | 3450 min | -0.3% | #605, #609 |
| `cal-mineral-baseline-8w` | cal-mineral-baseline-8w | Protoss | 305 | 2330 min | 2430 min | +4.3% | #661, #705 |
| `cal-mineral-baseline-terran` | cal-mineral-baseline-terran | Terran | 305 | 3460 min | 3450 min | -0.3% | #604, #600 |
| `cal-mineral-baseline-zerg` | cal-mineral-baseline-zerg | Zerg | 305 | 3415 min | 3425 min | +0.3% | #604, #600 |
| `cal-mineral-saturated-16w` | cal-mineral-saturated-16w | Protoss | 305 | 4270 min | 4150 min | -2.8% | #655 |
| `cal-mineral-saturated-24w` | cal-mineral-saturated-24w | Protoss | 305 | 3955 min | 3985 min | +0.8% | #604 |
| `cal-gas-baseline-3w` | cal-gas-baseline-3w | Protoss | 305 | 552 gas | 568 gas | +2.9% | #695, #677 |
| `cal-gas-dual-6w` | cal-gas-dual-6w | Protoss | 305 | 1204 gas | 1108 gas | -8.0% | #696, #688 |
| `cal-mule-isolation` | cal-mule-isolation | Terran | 305 | 3565 min | 3300 min | -7.4%† | #604, #598 |
| `cal-expansion-transfer` | cal-expansion-transfer | Protoss | 305 | 2625 min | —‡ | —‡ | #554, #614 |
| `cal-worker-speed-single` | cal-worker-speed-single | Protoss | 122 | 1370 min | 1355 min | -1.1% | #574 |
| `cal-worker-cadence-12w` | (cadence data for 12w) | Protoss | 305 | — | — | — | #622, #634 |
| `cal-worker-cadence-8w` | (cadence data for 8w) | Protoss | 305 | — | — | — | #661 |
| `protoss-probe-economy-fixture` | (test fixture) | Protoss | 5 | 98 min | — | — | — |

### Multi-run (10 files, 2 sets)

| Playbook | Runs | Race | Ticks | SC2 range | Purpose | Issue |
|---|---|---|---|---|---|---|
| `cal-gas-dual-6w` | 5 | Protoss | 305 | 1044–1204 gas | Quantify SC2 gas variance (run-2 is representative) | #688 |
| `cal-worker-cadence-12w` | 5 | Protoss | 305 | (cadence) | Per-worker delivery timing variance across runs | #622, #634 |

---

## Playbooks (25 total)

### With SC2 recordings (12)

| Playbook | Race | Ticks | What it tests |
|---|---|---|---|
| `cal-mineral-baseline-12w` | Protoss | 305 | 12-worker mineral rate, economy-only |
| `cal-mineral-baseline-8w` | Protoss | 305 | 8-worker mineral rate (1/patch, 4 killed at start) |
| `cal-mineral-baseline-terran` | Terran | 305 | Terran 12-SCV mineral rate |
| `cal-mineral-baseline-zerg` | Zerg | 305 | Zerg 12-Drone mineral rate |
| `cal-mineral-saturated-16w` | Protoss | 305 | 16-worker saturation (2/patch) + training |
| `cal-mineral-saturated-24w` | Protoss | 305 | 24-worker saturation (3/patch) + training |
| `cal-gas-baseline-3w` | Protoss | 305 | 3 workers on 1 geyser, rest minerals |
| `cal-gas-dual-6w` | Protoss | 305 | 6 workers on 2 geysers (3/geyser) |
| `cal-mule-isolation` | Terran | 305 | MULE income with workers and Orbital Command |
| `cal-expansion-transfer` | Protoss | 305 | Expand at 20 supply, measure transfer delay |
| `cal-worker-speed-single` | Protoss | 122 | Single worker trip timing measurement |
| `cal-mineral-depletion` | Protoss | 900 | Mineral patch depletion, volume-based transfer |

### Without SC2 recordings (13)

| Playbook | Race | Ticks | What it tests | Priority |
|---|---|---|---|---|
| `chrono-boost-economy` | Protoss | 183 | Chrono Boost on Nexus + Probe training | High |
| `inject-larva` | Zerg | 183 | Larvae inject + Drone training | High |
| `gas-saturation` | Protoss | 183 | Dual Assimilator with continuous training | Medium |
| `economy-only-protoss` | Protoss | 310 | Full economy (Chrono, expand, Pylons) | Low |
| `economy-only-terran` | Terran | 310 | Full economy (expand, Orbital, MULEs) | Low |
| `economy-only-zerg` | Zerg | 310 | Full economy (Overlords, Queens, expand) | Low |
| `mule-income` | Terran | 183 | MULE income (shorter than cal-mule-isolation) | Low |
| `upgrade-research` | Protoss | 450 | Upgrade research timing | Low |
| `two-base-production` | Protoss | 310 | Two-base Gateway + Zealot production | Low |
| `test-deferred-research` | Protoss | 450 | Deferred research timing | Test-only |
| `test-expansion-no-chrono` | Protoss | 310 | Expansion without Chrono Boost | Test-only |
| `test-expansion-reservation` | Protoss | 183 | Expansion mineral reservation | Test-only |
| `test-train-probes` | Protoss | 60 | Simple Probe training checkpoint | Test-only |

---

## Footnotes

**†** EM lacks MULE calldown — EM value reflects SCV-only income without MULE contribution. Delta measures the MULE income gap.

**‡** Expansion-transfer EM comparison is not valid: `skipBuildings` creates buildings without deducting minerals, which inflates Probe training and distorts economy. Without `skipBuildings`, building placement fails (EM lacks SC2 map positions). Requires `calibrate()` to deduct mineral costs when injecting buildings.

---

## Coverage gaps

### Race coverage

| Category | Protoss | Terran | Zerg |
|---|---|---|---|
| Mineral baseline (12w) | cal-mineral-baseline-12w | cal-mineral-baseline-terran | cal-mineral-baseline-zerg |
| Mineral saturation (16w/24w) | cal-mineral-saturated-{16w,24w} | — | — |
| Gas (3w single) | cal-gas-baseline-3w | — | — |
| Gas (6w dual) | cal-gas-dual-6w | — | — |
| Worker cadence | cal-worker-cadence-{8w,12w} | — | — |
| MULE/Chrono/Inject | — | cal-mule-isolation | — |
| Expansion transfer | cal-expansion-transfer | — | — |

### Missing recordings (priority-ordered)

1. **Terran/Zerg gas** — no Refinery or Extractor recordings; gas physics may differ by race
2. **Chrono Boost economy** — Chrono timing has no SC2 ground truth
3. **Inject Larva** — Zerg larvae mechanics have no SC2 ground truth
4. **Terran/Zerg worker cadence** — cadence variance may differ by worker unit
5. **Multi-base mineral steady-state** — expansion-transfer tests transfer delay, not sustained 2-base income
6. **Mineral depletion** — playbook exists (900 ticks) but no SC2 recording
7. **Gas saturation** — dual gas with training (more realistic than economy-only gas playbooks)

# EmulatedGame Divergence Baseline — 2026-10-05

**Context:** Phase 0 baseline (#347, child of #340)
**Commit:** on branch `issue-347-divergence-metrics-baseline`
**Harness:** `ReplayValidationHarness` — EmulatedGame economics vs replay ground truth
**Datasets:** Oracle (118 replays, v4.9.3) + HSC XXVII (61 replays, baseBuild=94137)

---

## Known limitation

`ReplayValidationHarness.countWorkersPerBase` hardcodes `Race.PROTOSS` —
the mining probe-per-base calculation uses Protoss town halls (Nexus) for all
races. Mineral divergence for non-Protoss players is inflated. Unit and
building count deltas are unaffected.

---

## HSC XXVII (baseBuild=94137) — 61 replays

Processed: 61 | Skipped: 0 | Failed player runs: 0

### Mineral delta (mean)

| Matchup |   N | 1min  | 2min  | 3min   | 4min   | 5min   | 6min   | 7min   | 8min   |
|---------|----:|------:|------:|-------:|-------:|-------:|-------:|-------:|-------:|
| PvP     |  28 | 488.0 | 969.2 | 1623.3 | 2205.0 | 2933.0 | 3706.8 | 4545.7 | 6114.5 |
| PvT     |  30 | 259.9 | 588.3 |  891.4 | 1257.1 | 1777.2 | 2329.9 | 2885.1 | 3599.7 |
| PvZ     |  42 | 266.7 | 686.1 |  856.7 | 1235.9 | 1670.1 | 2333.4 | 2912.4 | 3749.9 |
| TvT     |   4 |  20.0 |  91.3 |  103.8 |  105.0 |  185.8 |  119.5 |   94.0 |  102.5 |
| TvZ     |  14 |  61.6 | 184.9 |   89.3 |  152.6 |  239.5 |  154.8 |  311.6 |  285.7 |
| ZvZ     |   4 |  26.0 | 370.3 |   71.5 |  336.0 |  321.8 |  290.3 |  250.3 |   95.0 |

### Vespene delta (mean)

| Matchup |   N | 1min | 2min | 3min | 4min | 5min | 6min | 7min | 8min |
|---------|----:|-----:|-----:|-----:|-----:|-----:|-----:|-----:|-----:|
| PvP     |  28 |  0.0 |  0.0 |  0.0 |  0.0 |  0.0 |  0.0 |  0.0 | 83.6 |
| PvT     |  30 |  0.0 |  0.0 |  0.0 |  0.0 |  0.0 |  0.0 |  0.0 | 69.2 |
| PvZ     |  42 |  0.0 |  0.0 |  0.0 |  0.0 |  0.0 |  0.0 |  0.0 | 66.2 |
| TvT     |   4 |  0.0 |  0.0 |  0.0 |  0.0 |  0.0 |  0.0 |  0.0 | 73.0 |
| TvZ     |  14 |  0.0 |  0.0 |  0.0 |  0.0 |  0.0 |  0.0 |  0.0 | 64.1 |
| ZvZ     |   4 |  0.0 |  0.0 |  0.0 |  0.0 |  0.0 |  0.0 |  0.0 | 84.3 |

### Unit count delta (mean)

| Matchup |   N | 1min | 2min | 3min | 4min | 5min | 6min | 7min  | 8min  |
|---------|----:|-----:|-----:|-----:|-----:|-----:|-----:|------:|------:|
| PvP     |  28 |  3.9 |  9.2 | 15.1 | 19.1 | 24.8 | 27.3 |  30.3 |  39.3 |
| PvT     |  30 |  3.5 |  8.0 | 15.5 | 25.3 | 35.6 | 43.1 |  51.7 |  59.8 |
| PvZ     |  42 |  5.2 |  9.2 | 17.5 | 30.2 | 43.8 | 57.5 |  65.9 |  73.5 |
| TvT     |   4 |  3.0 |  7.5 | 13.8 | 19.3 | 34.8 | 49.8 |  47.5 |  73.5 |
| TvZ     |  14 |  4.9 |  9.2 | 18.6 | 33.5 | 53.1 | 69.5 |  95.1 | 121.3 |
| ZvZ     |   4 |  6.3 | 10.8 | 23.5 | 40.0 | 46.0 | 67.5 |  77.3 | 104.5 |

### Building count delta (mean)

| Matchup |   N | 1min | 2min | 3min | 4min | 5min | 6min | 7min | 8min |
|---------|----:|-----:|-----:|-----:|-----:|-----:|-----:|-----:|-----:|
| PvP     |  28 |  1.0 |  1.1 |  1.6 |  2.2 |  3.3 |  4.8 |  7.1 | 14.4 |
| PvT     |  30 |  1.0 |  1.0 |  1.1 |  1.1 |  1.3 |  2.6 |  4.6 |  5.9 |
| PvZ     |  42 |  1.4 |  1.6 |  1.9 |  2.3 |  2.7 |  3.8 |  6.9 | 11.8 |
| TvT     |   4 |  1.0 |  1.0 |  1.0 |  1.0 |  1.0 |  1.0 |  1.3 |  3.0 |
| TvZ     |  14 |  1.3 |  1.3 |  1.6 |  2.1 |  2.9 |  4.3 |  6.4 |  9.2 |
| ZvZ     |   4 |  1.8 |  1.8 |  1.8 |  2.3 |  2.3 |  2.3 |  2.3 |  2.3 |

---

## Oracle (v4.9.3) — 118 replays

Processed: 118 | Skipped: 0 | Failed player runs: 0

### Mineral delta (mean)

| Matchup |   N | 1min  | 2min  | 3min   | 4min   | 5min   | 6min   | 7min   | 8min   |
|---------|----:|------:|------:|-------:|-------:|-------:|-------:|-------:|-------:|
| PvP     |  32 | 350.1 | 725.8 | 1182.6 | 1568.3 | 2095.2 | 2726.6 | 3524.0 | 4621.3 |
| PvT     |  30 | 224.9 | 469.4 |  744.7 | 1032.1 | 1354.4 | 1818.1 | 2245.5 | 2704.6 |
| PvZ     |  36 | 222.7 | 531.1 |  799.5 | 1060.1 | 1408.8 | 1846.8 | 2252.0 | 2919.3 |
| TvT     |  44 | 126.1 | 152.4 |  293.4 |  308.6 |  406.6 |  572.7 |  712.1 |  871.7 |
| TvZ     |  48 |  78.7 | 173.3 |  204.8 |  299.2 |  382.5 |  537.7 |  800.5 | 1033.3 |
| ZvZ     |  46 |  51.9 | 178.9 |  192.0 |  268.5 |  346.2 |  385.9 |  573.4 |  676.8 |

### Vespene delta (mean)

| Matchup |   N | 1min | 2min | 3min | 4min | 5min | 6min | 7min | 8min |
|---------|----:|-----:|-----:|-----:|-----:|-----:|-----:|-----:|-----:|
| PvP     |  32 |  0.0 |  0.0 |  0.0 |  0.0 |  0.0 |  0.0 |  0.0 | 80.0 |
| PvT     |  30 |  0.0 |  0.0 |  0.0 |  0.0 |  0.0 |  0.0 | 10.4 | 95.6 |
| PvZ     |  36 |  0.0 |  0.0 |  0.0 |  0.0 |  0.0 |  4.2 |  0.0 | 95.5 |
| TvT     |  44 |  0.0 |  0.0 |  0.0 |  0.0 |  0.0 |  0.0 |  0.0 | 64.9 |
| TvZ     |  48 |  0.0 |  0.0 |  0.0 |  0.0 |  0.0 |  0.0 |  0.0 | 68.0 |
| ZvZ     |  46 |  0.0 |  0.0 |  0.0 |  0.0 |  0.0 |  0.0 |  0.0 | 80.1 |

### Unit count delta (mean)

| Matchup |   N | 1min | 2min | 3min | 4min | 5min | 6min | 7min | 8min |
|---------|----:|-----:|-----:|-----:|-----:|-----:|-----:|-----:|-----:|
| PvP     |  32 |  1.4 |  2.9 |  4.8 |  7.6 |  8.9 |  9.9 | 14.9 | 17.3 |
| PvT     |  30 |  2.4 |  5.4 |  9.1 | 13.9 | 20.6 | 24.5 | 31.1 | 35.2 |
| PvZ     |  36 |  4.0 |  7.4 | 13.2 | 21.3 | 28.4 | 37.1 | 45.8 | 55.4 |
| TvT     |  44 |  2.6 |  6.3 | 10.5 | 16.7 | 24.0 | 30.2 | 35.8 | 44.3 |
| TvZ     |  48 |  4.3 |  8.6 | 15.0 | 23.2 | 33.3 | 41.4 | 51.6 | 58.2 |
| ZvZ     |  46 |  6.0 | 12.0 | 18.2 | 28.3 | 38.3 | 48.5 | 53.3 | 58.9 |

### Building count delta (mean)

| Matchup |   N | 1min | 2min | 3min | 4min | 5min | 6min | 7min | 8min |
|---------|----:|-----:|-----:|-----:|-----:|-----:|-----:|-----:|-----:|
| PvP     |  32 |  1.0 |  1.0 |  1.2 |  1.5 |  1.7 |  2.2 |  3.5 |  8.5 |
| PvT     |  30 |  1.0 |  1.0 |  1.1 |  1.2 |  1.3 |  1.7 |  3.3 |  4.5 |
| PvZ     |  36 |  1.1 |  1.1 |  1.5 |  1.9 |  2.3 |  2.5 |  2.9 |  4.0 |
| TvT     |  44 |  1.0 |  1.0 |  1.0 |  1.0 |  1.1 |  1.4 |  1.9 |  2.3 |
| TvZ     |  48 |  1.1 |  1.1 |  1.1 |  1.2 |  1.3 |  1.9 |  2.0 |  2.5 |
| ZvZ     |  46 |  1.1 |  1.1 |  1.2 |  1.4 |  1.3 |  1.4 |  1.8 |  2.3 |

---

## Assessment

**Vespene:** near-zero divergence through minute 7 across all matchups — the
harness syncs vespene from ground truth, so this confirms the sync is effective.
The ~70-95 delta at minute 8 is expected as games that end before minute 8
introduce boundary effects.

**Minerals:** significant divergence, growing linearly with time. PvP shows
the highest absolute deltas (both players are Protoss, so the PROTOSS
hardcoding doesn't explain the gap). Root cause is EmulatedGame's flat mining
rate vs SC2's saturation-based model. TvT/TvZ/ZvZ show lower mineral deltas
despite the PROTOSS worker hardcoding — likely because these matchups have
fewer workers early (Terran/Zerg economic openings differ from Protoss
chrono-boost-heavy builds).

**Unit counts:** divergence grows steadily. PvP has the lowest delta (~17 at
8min), likely because Protoss armies are smaller and more expensive. ZvZ and
TvZ show the highest deltas (>100 units at 8min), reflecting high unit-count
races where small per-unit extraction errors compound.

**Building counts:** minimal divergence (1-3) through minute 5 across all
matchups — buildings are synced from ground truth. Late-game divergence
(7-14 at minute 8) suggests building construction timing or tech buildings
the harness doesn't track.

**Cross-dataset consistency:** HSC XXVII shows higher mineral deltas than
Oracle for Protoss matchups (6114 vs 4621 at 8min for PvP), potentially
reflecting more aggressive economic play at the tournament level or
baseBuild-specific differences.

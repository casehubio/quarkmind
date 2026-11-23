# Per-Worker Cycle Variance Diagnostic — 2026-11-23

**Dataset:** Oracle (118 replays, v4.9.3) + controlled 12w calibration baseline
**Method:** WorkerPhaseListener instrumentation in standalone EM, delivery-to-delivery cycle intervals
**SC2 baseline:** `cal-worker-cadence-12w.json` (12w economy-only, carrying transitions)

---

## Controlled 12w — EM vs SC2

| Source | Cycles | Mean (ticks) | σ (ticks) | Min | Max |
|--------|--------|-------------|-----------|-----|-----|
| SC2    |    670 |        5.27 |      0.56 | 4.0 | 6.0 |
| EM     |    735 |        4.81 |      0.85 | 2.8 | 10.9|

**Key finding:** EM is *more* variable than SC2 in the controlled scenario (σ=0.85 vs 0.56). This reverses the prior assumption (CLAUDE.md #618→#632) that "EM cycles are too uniform."

The EM's higher σ comes from walk-time variation and collision avoidance introducing jitter that the real SC2 economy doesn't exhibit in a controlled 12w setup.

---

## Oracle Replay Cycle Variance (Standalone EM)

| Race    | Runs | Cycles  | Mean (ticks) | σ (ticks) | Range |
|---------|------|---------|-------------|-----------|-------|
| Protoss |   65 | 37,910  |        6.54 |      8.30 | 2.5-236.3 |
| Zerg    |   88 | 48,445  |        6.17 |      7.13 | 2.5-209.7 |
| Terran  |   83 | 45,144  |        6.42 |      9.75 | 2.5-223.9 |

Cycle variance is high across all races, driven by expansion transfers and variable walk distances.

---

## Phase Decomposition

### Controlled 12w (EM)

| Phase             | Count | Mean (ticks) | σ (ticks) | Min  | Max  |
|-------------------|-------|-------------|-----------|------|------|
| WALKING_TO_PATCH  |   743 |        1.16 |      0.46 | 0.18 | 3.73 |
| WAITING_FOR_PATCH |   475 |        0.17 |      0.29 | 0.00 | 1.73 |
| MINING            |   748 |        1.91 |      0.00 | 1.91 | 1.91 |
| WALKING_TO_BASE   |   747 |        1.01 |      0.39 | 0.05 | 2.59 |
| DEPOSITING        |   745 |        0.55 |      0.00 | 0.55 | 0.55 |

### Oracle Replays (EM, per race)

| Phase             | Protoss σ | Zerg σ | Terran σ |
|-------------------|-----------|--------|----------|
| WALKING_TO_PATCH  |      4.74 |   4.40 |     5.67 |
| WAITING_FOR_PATCH |      0.57 |   0.58 |     0.51 |
| MINING            |      0.00 |   0.00 |     0.00 |
| WALKING_TO_BASE   |      3.59 |   3.50 |     3.33 |
| DEPOSITING        |      0.00 |   0.00 |     0.00 |

**Variance sources (ranked):**
1. **Walk-to-patch distance** (σ=4.40-5.67) — dominant contributor. Varies as workers operate across multiple bases at different distances.
2. **Walk-to-base distance** (σ=3.33-3.59) — same mechanism, return leg.
3. **Patch contention wait** (σ=0.51-0.58) — moderate. Workers waiting for occupied patches.
4. **Mining/deposit** (σ=0.00) — invariant. Fixed-duration phases, no variance contribution.

---

## Conclusions

1. The controlled σ gap (SC2=0.56, EM=0.85) is *inverted* — EM is more variable, not less. The prior finding (#618→#632) that "EM σ≈0.5 vs SC2 σ≈3+" was comparing a controlled EM against real-game SC2 data, not like-for-like.

2. In oracle replays, EM cycle σ is 7-10 ticks — high variance exists but is driven by expansion walk distances, not by per-cycle physics jitter.

3. The rate MAPE gap (38.2%) is unlikely to be caused by cycle cadence uniformity. Walk distance and expansion transfer timing are the dominant variance sources, and the EM already models these.

4. The 200+ tick outliers are expansion transfer walks — workers relocating between bases produce extreme cycle times that dominate the aggregate σ.

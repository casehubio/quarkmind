# Mineral Patch Geometry: Replay vs EM Seeded Distances — 2026-11-22

**Dataset:** Oracle (118 replays), 236 player runs, 236 bases
**Seeded constants:** CLOSE_PATCH_DISTANCE=6.7, FAR_PATCH_DISTANCE=7.5

---

## Center-to-Center Distances

| Category | Min | Avg | Max | σ | Seeded |
|---|---|---|---|---|---|
| Close (first 4) | 6.00 | 6.61 | 7.81 | 0.53 | 6.70 |
| Far (remaining) | 7.00 | 7.55 | 8.54 | 0.45 | 7.50 |

## Edge-to-Edge Walk Distances

| Category | Min | Avg | Max | Seeded |
|---|---|---|---|---|
| Close (first 4) | 2.25 | 2.86 | 4.06 | 2.95 |
| Far (remaining) | 3.25 | 3.80 | 4.79 | 3.75 |

**Patches per base:** min=8, avg=8.0, max=8

---

## Economy MAPE: Replay Positions vs Seeded Positions

### 1-minute checkpoint

| Field | Replay MAPE | Seeded MAPE | Delta |
|---|---|---|---|
| mineralsCollectionRate | 35.6% | 23.9% | -11.6 pp |
| vespeneCollectionRate | 90.8% | 87.6% | -3.2 pp |
| mineralsCurrent | 95.8% | 87.6% | -8.3 pp |
| vespeneCurrent | 89.7% | 96.6% | +6.9 pp |
| workersActiveCount | 3.3% | 3.3% | +0.0 pp |

### 3-minute checkpoint

| Field | Replay MAPE | Seeded MAPE | Delta |
|---|---|---|---|
| mineralsCollectionRate | 24.8% | 14.7% | -10.1 pp |
| vespeneCollectionRate | 29.5% | 30.4% | +1.0 pp |
| mineralsCurrent | 86.2% | 70.2% | -16.0 pp |
| vespeneCurrent | 41.6% | 45.1% | +3.5 pp |
| workersActiveCount | 1.4% | 1.4% | +0.0 pp |

### 5-minute checkpoint

| Field | Replay MAPE | Seeded MAPE | Delta |
|---|---|---|---|
| mineralsCollectionRate | 29.3% | 21.3% | -8.0 pp |
| vespeneCollectionRate | 22.2% | 21.9% | -0.3 pp |
| mineralsCurrent | 86.6% | 80.9% | -5.7 pp |
| vespeneCurrent | 50.0% | 49.0% | -1.0 pp |
| workersActiveCount | 3.1% | 3.1% | +0.0 pp |

### 7-minute checkpoint

| Field | Replay MAPE | Seeded MAPE | Delta |
|---|---|---|---|
| mineralsCollectionRate | 38.6% | 33.4% | -5.2 pp |
| vespeneCollectionRate | 20.5% | 20.0% | -0.4 pp |
| mineralsCurrent | 84.9% | 79.0% | -5.9 pp |
| vespeneCurrent | 61.1% | 60.5% | -0.5 pp |
| workersActiveCount | 2.5% | 2.5% | +0.0 pp |

---

## Analysis

**Replay positions are already wired.** `ReplayValidationHarness` and
`StandaloneEmulatedGameBaselineTest` both call `emulated.setMineralPatchResources(groundTruth.getMineralPatches())`,
passing real mineral patch coordinates from replay tracker events into WorkerSimulation.

**Seeded positions are more accurate.** Counterintuitively, seeded positions (uniform 6.7/7.5)
produce 5–12pp better mineral rate MAPE than replay positions with real map geometry.

**Why:** The EM's mining physics were calibrated against uniform distances in dual-run
calibration tests. The seeded constants (6.7/7.5) match the calibration environment. When
real map distances are introduced (σ=0.53 for close patches, range 6.0–7.8), the
heterogeneous cycle times expose physics that don't generalise to variable distances.

**Implication:** The MAPE gap from geometry is not about missing real positions (they're
already present). It's about the EM's mining physics producing incorrect cycle times when
distances vary from the calibration baseline. Fixing this requires the physics to handle
arbitrary distances correctly — the rate computation algorithm (#676) and worker routing
are the root causes, not the geometry input.

# SC2 Physics Constants — Worker Economy

Consolidated reference for all physics constants used in EmulatedGame worker simulation.
Each entry documents the value, source, validation method, and calibration status.

**Game speed:** Faster (22.4 game loops/second, 16 Hz normal × 1.4)
**Tick size:** 22 loops per tick (~0.98 seconds)

---

## Worker Movement

| Constant | Value | Unit | Source | Validated |
|----------|-------|------|--------|-----------|
| Worker max speed | 3.94 | u/s | Liquipedia / SC2Data | Yes — already ×1.4 scaled |
| Acceleration | 3.5 | u/s² | SC2Mapster (2.5) × 1.4 | #578 — halved mineralsCollectionRate MAPE |
| Deceleration | 3.5 | u/s² | SC2Mapster (defaults to accel) × 1.4 | #578 |
| Arrival threshold | 0.5 | units | Engineering choice | N/A |

**Scaling rule:** SC2 data editor stores values at normal game speed (16 Hz). Unit speeds are pre-scaled ×1.4 in community sources. Acceleration/deceleration/turn rate are NOT — must multiply by 1.4 manually. See #585.

**Movement profile at 6-unit distance:** Triangular (accelerate 3 units, decelerate 3 units). Peak speed: 3.87 u/s. Trip takes 47 game loops (2.10 seconds). Workers never reach max speed at typical patch distances.

---

## Mineral Mining

| Constant | Code Value | Real SC2 | Source | Validated |
|----------|-----------|----------|--------|-----------|
| Mining animation | 12 loops (0.54s) | ~5 loops (0.22s) | MAPE sweep #583 / SC2 API measurement | Yes — both approaches |
| Mineral cargo | 5 | 5 | Liquipedia | Community consensus |
| Rich mineral cargo | 7 | 7 | Liquipedia | Community consensus |
| Max miners per patch | 1 | 1 (active) | SC2 mechanics | Observation |
| Patches per base | 8 | 8 | SC2 map standard | Universal |
| Close patches | 4 | 4 | SC2 map standard | Universal |

**Why mining=12 when real animation is ~5:** The constant absorbs unmodeled per-cycle overhead measured via SC2 API:
- Deposit time at town hall: ~2-3 loops stationary
- Approach/docking delay at mineral patch: ~2-3 loops
- Path contention between workers: ~1-2 loops average
- True mining (~5) + overhead (~7) = ~12 effective loops

**Config:** `starcraft.worker.mining-duration-loops=12.0` (no recompile needed)

---

## Gas Harvesting

| Constant | Code Value | Source | Validated |
|----------|-----------|--------|-----------|
| Gas harvest duration | 31.696 loops (1.415s) | Liquipedia | #571 — oracle MAPE |
| Gas cargo | 4 | Liquipedia | Community consensus |
| Max workers per geyser | 3 | SC2 mechanics | Universal |
| Gas geyser distance | Per-map GT positions | Oracle replay data | #556 |

**Config:** `starcraft.worker.gas-harvest-duration-loops=31.696`

---

## Mineral Patch Geometry

| Constant | Value | Source | Validated |
|----------|-------|--------|-----------|
| Close patch distance | avg 6.6 units | Oracle replay mineral positions | #567 |
| Far patch distance | avg 7.6 units | Oracle replay mineral positions | #567 |
| Unit test patch distance | 6.0 units (uniform) | Fixed for test stability | N/A |
| Close patch minerals | 1500 | SC2 standard | Universal |
| Far patch minerals | 750 | SC2 standard | Universal |

---

## Mining Efficiency Factors

Per-race mineral mining efficiency (empirically calibrated):

| Race | Factor | Source | Validated |
|------|--------|--------|-----------|
| Protoss | 0.92 | MiningEfficiencyCalibrationTest sweep | #566 |
| Zerg | 0.95 | MiningEfficiencyCalibrationTest sweep | #566 |
| Terran | 1.0 | MiningEfficiencyCalibrationTest sweep | #566 |

---

## Economy Baseline (Standalone, 7-min, 118 oracle replays)

| Field | MAPE | Status |
|-------|------|--------|
| mineralsCollectionRate | 22.3% | Active calibration |
| vespeneCollectionRate | 37.1% | Active calibration |
| mineralsCurrent | 67.6% | Cumulative drift |
| vespeneCurrent | 77.4% | Cumulative drift |
| foodMade | 3.1% | At floor |
| foodUsed | 3.7% | At floor |
| workersActiveCount | 2.5% | At floor |

---

## Key Calibration Tests

| Test | What it measures | Run with |
|------|-----------------|----------|
| MiningDurationCalibrationTest | Saturation curves + oracle MAPE sweep | `-Preport` |
| StandaloneEmulatedGameBaselineTest | Full economy MAPE, all fields | `-Preport` |
| DivergenceRegressionTest | Regression thresholds (units/buildings/economy) | `-Preport` |
| MiningAnimationMeasurementTest | Real SC2 mining animation via API | `-Dtest=MiningAnimationMeasurementTest` |
| SC2PlaybookCalibrationTest | Playbook economy validation | default suite |
| MiningEfficiencyCalibrationTest | Per-race mining factor sweep | `-Pdiagnostic` |

---

## Quarkus Config Properties

All worker physics constants can be overridden without recompiling:

```properties
# application.properties or -D flags
starcraft.worker.mining-duration-loops=12.0
starcraft.worker.gas-harvest-duration-loops=31.696
starcraft.worker.acceleration=3.5
starcraft.worker.deceleration=3.5
```

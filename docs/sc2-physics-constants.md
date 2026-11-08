# SC2 Simulation Physics Constants

Living reference for every physics constant in QuarkMind's EmulatedGame simulation.
Each entry documents value, source, and confidence level.

**Confidence levels:**
- **Calibrated** — validated against oracle replays, real SC2 API measurement, or dual-run comparison
- **Published** — from authoritative community source (Liquipedia, LotV balance data) but not independently validated
- **Estimated** — approximation from community values, needs calibration
- **Placeholder** — stub value, known incomplete or wrong

---

## 1. Game Timing

| Constant | Value | Source | Confidence |
|----------|-------|--------|------------|
| Game loops per second (Faster) | 22.4 | 16 Hz normal × 1.4 | **Published** — SC2 engine spec |
| Loops per tick | 22 | Engineering choice | N/A |
| Tick duration | 0.982s | 22 / 22.4 | Derived |
| Normal-to-Faster multiplier | 1.4× | SC2 engine spec | **Published** |

**Scaling rule:** SC2 data editor stores values at normal game speed (16 Hz). Unit speeds in community sources are pre-scaled ×1.4. Acceleration, turn rate, and other per-second² constants are NOT — must multiply by 1.4 manually. See #585 audit.

---

## 2. Worker Movement

| Constant | Value | Unit | Source | Confidence | Config property |
|----------|-------|------|--------|------------|-----------------|
| Worker max speed | 3.94 | u/s | Liquipedia | **Published** — already ×1.4 scaled | — |
| Acceleration | 3.5 | u/s² | SC2Mapster (2.5) × 1.4 | **Calibrated** — #578 | `starcraft.worker.acceleration` |
| Deceleration | 3.5 | u/s² | SC2Mapster (defaults to accel) × 1.4 | **Calibrated** — #578 | `starcraft.worker.deceleration` |
| Arrival threshold | 0.5 | units | Engineering choice | N/A | — |

**Movement profile at 6-unit distance:** Triangular (accelerate 3 units, decelerate 3 units). Peak speed: 3.87 u/s. Trip takes 47 game loops (2.10 seconds). Workers never reach max speed at typical patch distances.

**Unit speeds (28 types mapped):** Source: Liquipedia (Faster speed). Unmapped types use default 3.0 u/s.

---

## 3. Mineral Mining

| Constant | Code value | Real SC2 | Source | Confidence | Config property |
|----------|-----------|----------|--------|------------|-----------------|
| Mining duration | 12 loops (0.54s) | ~5 loops (0.22s) | Oracle MAPE sweep + SC2 API measurement | **Calibrated** — #583 | `starcraft.worker.mining-duration-loops` |
| Mineral cargo | 5 | 5 | Liquipedia | **Published** | — |
| Rich mineral cargo | 7 | 7 | Liquipedia | **Published** | — |
| Max miners per patch | 1 (active) | 1 | SC2 mechanics | **Published** | — |
| Patches per base | 8 | 8 | SC2 map standard | **Published** | — |
| Close patches per base | 4 | 4 | SC2 map standard | **Published** | — |
| Mining start delay | 1 tick | — | Dual-run calibration #401 | **Calibrated** | — |

**Why mining=12 when real animation is ~5:** Absorbs unmodeled per-cycle overhead:
- Deposit time at town hall: ~2-3 loops (measured via SC2 API)
- Approach/docking delay: ~2-3 loops
- Path contention: ~1-2 loops average

---

## 4. Gas Harvesting

| Constant | Code value | Source | Confidence | Config property |
|----------|-----------|--------|------------|-----------------|
| Gas harvest duration | 31.696 loops (1.415s) | Liquipedia | **Calibrated** — #571 | `starcraft.worker.gas-harvest-duration-loops` |
| Gas cargo | 4 | Liquipedia | **Published** | — |
| Max workers per geyser | 3 | SC2 mechanics | **Published** | — |
| Gas geyser distance | Per-map GT positions | Oracle replay data | **Calibrated** — #556 | — |

---

## 5. Mineral Patch Geometry

| Constant | Value | Source | Confidence |
|----------|-------|--------|------------|
| Close patch distance | avg 6.6 units | Oracle replay mineral positions | **Calibrated** — #567 |
| Far patch distance | avg 7.6 units | Oracle replay mineral positions | **Calibrated** — #567 |
| Unit test patch distance | 6.0 units (uniform) | Fixed for test stability | N/A |
| Close patch minerals | 1500 | SC2 standard | **Published** |
| Far patch minerals | 750 | SC2 standard | **Published** |

---

## 6. Mining Efficiency Factors

Per-race mineral mining efficiency (MAPE-minimising factor at 5/7-min checkpoints):

| Race | Factor | Source | Confidence |
|------|--------|--------|------------|
| Protoss | 0.92 | MiningEfficiencyCalibrationTest sweep | **Calibrated** — #566 |
| Zerg | 0.95 | MiningEfficiencyCalibrationTest sweep | **Calibrated** — #566 |
| Terran | 1.0 | MiningEfficiencyCalibrationTest sweep | **Calibrated** — #566 |

---

## 7. Race Mechanics

### Protoss — Chrono Boost

| Constant | Value | Source | Confidence |
|----------|-------|--------|------------|
| Energy cost | 50 | SC2 mechanics | **Published** |
| Duration | 448 loops (20s) | SC2 mechanics | **Published** |
| Speed multiplier | 0.5 (50% faster) | SC2 mechanics | **Published** |
| Nexus starting energy | 50 | SC2 mechanics | **Published** |
| Max caster energy | 200 | SC2 mechanics | **Published** |
| Energy regen | 0.5625/s ÷ 22.4 per loop | SC2 mechanics | **Published** |
| Gateway warp-in | 80 loops (3.6s) | SC2 mechanics | **Published** |

### Terran — MULE & Orbital Command

| Constant | Value | Source | Confidence |
|----------|-------|--------|------------|
| MULE lifetime | 1434 loops (64s) | Community | **Estimated** — pending #140 |
| MULE income | 277.8 minerals/min | Oracle replays (115 obs) | **Calibrated** |
| MULE calldown energy | 50 | SC2 mechanics | **Published** |
| OC starting energy | 50 | SC2 mechanics | **Published** |
| Addon mineral cost | 50 | SC2 mechanics | **Published** |
| Reactor gas cost | 50 | SC2 mechanics | **Published** |
| Tech Lab gas cost | 25 | SC2 mechanics | **Published** |

### Zerg — Larva & Inject

| Constant | Value | Source | Confidence |
|----------|-------|--------|------------|
| Larva spawn interval | 246 loops (~11s) | Community | **Estimated** — pending AutoSpawnCalibrationTest |
| Larva regen loops | 245 | ZergRaceModel | **Estimated** |
| Max larva per hatchery | 3 | SC2 mechanics | **Published** |
| Max inject larva | 19 | SC2 mechanics | **Published** |
| Inject larva count | 4 | SC2 mechanics | **Published** |
| Inject energy cost | 25 | SC2 mechanics | **Published** |
| Interceptor build time | 202 loops (~9s) | Community | **Estimated** — pending AutoSpawnCalibrationTest |

---

## 8. Initial State

| Constant | Protoss | Terran | Zerg | Source | Confidence |
|----------|---------|--------|------|--------|------------|
| Starting minerals | 50 | 50 | 50 | SC2 standard | **Published** |
| Starting vespene | 0 | 0 | 0 | SC2 standard | **Published** |
| Starting supply | 15 | 15 | 14 | SC2 standard | **Published** |
| Starting supply used | 12 | 12 | 12 | SC2 standard | **Published** |
| Starting workers | 12 | 12 | 12 | SC2 standard | **Published** |
| Starting base | Nexus | CC | Hatchery | SC2 standard | **Published** |

---

## 9. Production Timing

### Train Times (67 unit types)

| Status | Count | Source | Test |
|--------|-------|--------|------|
| **Calibrated** | 25 | Oracle replays (SC2TrainTimeCalibrationTest, MorphTimeCalibrationTest) | SC2TrainTimeCalibrationTest |
| Published | 1 | Liquipedia | — |
| Estimated | 6 | Community (seconds × 22.4) | — |
| Uncalibrated | 31 | Default 672 loops (30s) | — |
| Same-as / auto | 4 | Derived | — |

Calibrated Protoss: Probe (272, 756 obs), Zealot (608, 42 obs), Stalker (676, 5 obs — low confidence), Immortal (880, 26 obs), Observer (480, 19 obs), Phoenix (560, 12 obs).
Calibrated Terran: Marine (554, 180 obs), Marauder (781), SCV (272, 1443 obs), Siege Tank (720, 188 obs), and 13 more.
Calibrated Zerg morphs: Baneling (322, 166 obs), Ravager (271, 62 obs), Brood Lord (542, 6 obs), Overseer (267, 13 obs).

### Build Times (53 building types)

| Status | Count | Source | Test |
|--------|-------|--------|------|
| **Empirical** | 34 | AI Arena replays | SC2BuildTimeCalibrationTest |
| **Calibrated** | 3 | Oracle replays | — |
| Estimated | 15 | Community (ticks × 22) | — |
| Same-as | 1 | Derived | — |

### Upgrade Times (82 upgrades)

All from LotV balance data (game_seconds × 22.4). Confidence: **Published**. Fallback: 100s × 22.4 = 2240 loops.

### Upgrade Costs (82 upgrades)

All from LotV balance data. Confidence: **Published**.

---

## 10. Supply

| Constant | Value | Source | Confidence |
|----------|-------|--------|------------|
| Nexus / CC / OC / PF supply | 15 | SC2 standard | **Published** |
| Pylon / Supply Depot supply | 8 | SC2 standard | **Published** |
| Hatchery / Lair / Hive supply | 6 | SC2 standard | **Published** |
| Overlord / Overseer supply | 8 | SC2 standard | **Published** |
| Max supply | 200 | SC2 standard | **Published** |

Unit supply costs: all 67 unit types mapped. Source: LotV balance data. Confidence: **Published**.

---

## 11. Unit Stats

### Hit Points / Shields / Armour (68 unit types)

| Status | Count | Notes |
|--------|-------|-------|
| **Calibrated** | 18 | Real HP/Shield/Armour values (Probe, Zealot, Stalker, Immortal, Zergling, Roach, Hydralisk, Queen, Marine, Marauder, SCV, Drone, Overlord, Observer, Void Ray, Mule, Egg, Larva) |
| **Placeholder** | 50 | Default 100/0/0 — known incomplete |

Building health: all 53 types mapped with real values. Confidence: **Published** (SC2 balance data).

### Unit Attributes — LIGHT / ARMORED / BIOLOGICAL / etc. (68 unit types)

All from patch 5.0.16 ground truth (starcraft2.ai). Confidence: **Calibrated** — validated by SC2DataAttributeCalibrationTest against 69 unit types.

---

## 12. Combat

### Combat Stats (71 unit types)

| Status | Count | Notes |
|--------|-------|-------|
| **Calibrated** | 13 | Real damage/cooldown/range (Marine 6/1/5.0, Marauder 10/2/5.0+10 vs Armored, Stalker 13/1/5.0+4 vs Armored, Zealot 8/2/0.5, Immortal 20/2/5.5+3 vs Armored, Queen 20/2/5.0, Zergling 5/1/0.5, Roach 9/2/4.0, Hydralisk 12/1/5.0, Probe 5/2/3.0, SCV 5/2/0.5, Drone 5/2/3.0) |
| **Placeholder** | 55 | Default 5/2/3.0 — stub values |
| Non-combatant | 3 | 0/MAX/0 (Mule, Egg, Larva) |

Combat cooldowns are in simulation ticks (not SC2 data editor values) — no game-speed scaling needed.

### Blink (Stalker only)

| Constant | Value | Source | Confidence |
|----------|-------|--------|------------|
| Blink range | 8.0 tiles | SC2 mechanics | **Published** |
| Blink cooldown | 21 ticks (~10.5s) | SC2 mechanics | **Published** |
| Shield restore on blink | 40 | SC2 mechanics | **Published** |

### Sight Ranges (68 unit types + all buildings)

Most defaulted to 9. Probe specifically set to 8. Confidence: **Estimated** — not individually validated.

---

## 13. Building Footprints

All building types mapped to collision radii for physics. Categories:

| Footprint | Radius | Examples |
|-----------|--------|----------|
| 5×5 (Large) | 2.5 | Nexus, CC, Hatchery |
| 4×4 | 2.0 | Ultralisk Cavern |
| 3×3 (Medium) | 1.5 | Gateway, Barracks, Spawning Pool |
| 2×2 (Small) | 1.0 | Pylon, Supply Depot, Photon Cannon |
| Tiny | 0.5 | Creep Tumor |

Confidence: **Published** (SC2 map editor).

---

## 14. Economy Baseline

Standalone EmulatedGame accuracy at 7-min checkpoint (118 oracle replays, all GT syncs disabled):

| Field | MAPE | Confidence | Status |
|-------|------|------------|--------|
| mineralsCollectionRate | 22.3% | — | Active calibration |
| vespeneCollectionRate | 37.1% | — | Active calibration |
| mineralsCurrent | 67.6% | — | Cumulative drift |
| vespeneCurrent | 77.4% | — | Cumulative drift |
| foodMade | 3.1% | — | At floor |
| foodUsed | 3.7% | — | At floor |
| workersActiveCount | 2.5% | — | At floor |
| mineralsUsedCurrentArmy | 7.1% | — | At floor |
| mineralsUsedCurrentEconomy | 5.8% | — | At floor |
| mineralsUsedCurrentTechnology | 17.0% | — | Near floor |
| vespeneUsedCurrentArmy | 7.4% | — | At floor |
| vespeneUsedCurrentTechnology | 42.0% | — | Structural — InProgress/Current timing |

---

## 15. Calibration Coverage Summary

| Category | Total | Calibrated | Published | Estimated | Placeholder |
|----------|-------|------------|-----------|-----------|-------------|
| Worker physics | 4 | 4 | — | — | — |
| Mining constants | 7 | 3 | 4 | — | — |
| Gas constants | 4 | 2 | 2 | — | — |
| Race mechanics | 17 | 1 | 12 | 4 | — |
| Train times | 67 | 25 | 1 | 6 | 35 |
| Build times | 53 | 37 | — | 15 | 1 |
| Upgrade times | 82 | — | 82 | — | — |
| Unit HP/shields | 68 | 18 | — | — | 50 |
| Unit attributes | 68 | 68 | — | — | — |
| Combat stats | 71 | 13 | — | — | 55+3 |
| Unit speeds | 28+ | — | 28 | — | — |
| Building health | 53 | — | 53 | — | — |

**Overall: 171 calibrated, 182 published, 25 estimated, 144 placeholder.**

---

## 16. Key Calibration Tests

| Test | What it measures | Run with |
|------|-----------------|----------|
| MiningDurationCalibrationTest | Saturation curves + oracle MAPE sweep | `-Preport` |
| MiningAnimationMeasurementTest | Real SC2 mining animation via API | `-Dtest=MiningAnimationMeasurementTest` |
| StandaloneEmulatedGameBaselineTest | Full economy MAPE, all fields | `-Preport` |
| DivergenceRegressionTest | Regression thresholds (units/buildings/economy) | `-Preport` |
| SC2TrainTimeCalibrationTest | Unit train times vs oracle replays | `-Preport` |
| SC2BuildTimeCalibrationTest | Building build times vs AI Arena replays | `-Preport` |
| SC2DataAttributeCalibrationTest | Unit attributes vs patch 5.0.16 ground truth | `-Preport` |
| SC2PlaybookCalibrationTest | Playbook economy validation | default suite |
| DualRunCalibrationTest | Tick-by-tick EmulatedGame vs SC2 baseline | default suite |
| MiningEfficiencyCalibrationTest | Per-race mining factor sweep | `-Pdiagnostic` |
| AutoSpawnCalibrationTest | Larva spawn interval, interceptor build time | `-Pdiagnostic` |

---

## 17. Quarkus Config Properties

Worker physics constants — override without recompiling:

```properties
# application.properties or -D flags
starcraft.worker.mining-duration-loops=12.0
starcraft.worker.gas-harvest-duration-loops=31.696
starcraft.worker.acceleration=3.5
starcraft.worker.deceleration=3.5
```

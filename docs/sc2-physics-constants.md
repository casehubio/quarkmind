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

**Movement profile:** Workers reach max speed (3.94 u/s) after 2.22 units of acceleration. At all real mineral patch distances (6.0–8.5 units) the velocity profile is trapezoidal: accelerate for 2.22 units, cruise at max speed, decelerate for 2.22 units. Below 4.4 units (rare) the profile is triangular and the worker never reaches max speed. Trip at 6.0 units: 47 loops (2.10s). At 6.6 units: 50 loops (2.23s). At 7.6 units: 57 loops (2.54s).

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
| Patch docking overhead | 34 loops | — | SC2 dual-run sweep (23w saturation) | **Calibrated** — #599 | `starcraft.worker.patch-docking-overhead-loops` |

**Why mining=12 when real animation is ~5:** Absorbs unmodeled per-cycle overhead:
- Deposit time at town hall: ~2-3 loops (measured via SC2 API)
- Approach/docking delay: ~2-3 loops
- Path contention: ~1-2 loops average

**Patch docking overhead:** After a miner departs a mineral patch, the patch remains locked for 34 loops to model the real SC2 docking animation. A waiting worker consumes this lock time within the same tick (sub-tick resolution). Calibrated from dual-run sweep against SC2 23-worker saturation baseline — matches SC2's 84% efficiency at 2.9 workers/patch.

---

## 4. Gas Harvesting

| Constant | Code value | Source | Confidence | Config property |
|----------|-----------|--------|------------|-----------------|
| Gas harvest duration | 26.0 loops (1.16s) | Dual-run sweep (3w/6w ±5% target) | **Calibrated** — #606 | `starcraft.worker.gas-harvest-duration-loops` |
| Gas cargo | 4 | Liquipedia | **Published** | — |
| Max workers per geyser | 3 | SC2 mechanics | **Published** | — |
| Gas geyser distance (seeded) | ~7.6u from town hall | SC2 dual-run (TorchesAIE_v4) | **Calibrated** — #597 | — |
| Gas geyser distance (SC2 API) | 5.7u and 8.5u | SC2 observation API | **Calibrated** — #602 | — |

**Seeded geyser positions (all races):** (15,11) and (11,15) relative to town hall at (8,8) = 7.6u symmetric. **SC2 API positions (#602):** real geysers on TorchesAIE_v4 are asymmetric — 5.7u and 8.5u from Nexus center (avg 7.1u). All 10 calibration baselines now include 32 geyser positions (full map); `DualRunCalibrationTest` filters to the 2 within 12u of the town hall.

---

## 5. Mineral Patch Geometry

| Constant | Value | Source | Confidence |
|----------|-------|--------|------------|
| Close patch distance | avg 6.6 units | Oracle replay mineral positions | **Calibrated** — #567 |
| Far patch distance | avg 7.6 units | Oracle replay mineral positions | **Calibrated** — #567 |
| Default patch distance | 7.0 units (uniform) | SC2 dual-run calibration sweep | **Calibrated** — #599 | `starcraft.worker.patch-distance` |
| SC2 API distances (TorchesAIE_v4) | 3.0–5.9u (center-to-center) | SC2 observation API | **Calibrated** — #605 |
| Close patch minerals | 1500 | SC2 standard | **Published** |
| Far patch minerals | 750 | SC2 standard | **Published** |

**SC2 API patch positions (#605):** All 10 calibration baselines now include exact mineral patch positions from the SC2 observation API (154 patches per map). The DualRunCalibrationTest injects these via `setMineralPatchResources()` before `reset()`, so the EM uses real distances instead of the default 7.0u uniform distance. TorchesAIE_v4 starting base patches are 3.0–5.9u from Nexus center (close=4.0u avg, far=4.6u avg) — much shorter than the oracle replay averages because the API returns center-to-center distances while replay distances include model offsets.

---

## 6. Mining Efficiency Factors

**Per-race factors calibrated via SC2 dual-run sweep (#600).** Protoss 1.0 (within 0.3% of SC2). Terran and Zerg produce 1.3% fewer minerals than Protoss in real SC2 — likely race-specific worker animation differences. Factor 0.984 matches both SC2 baselines exactly (3415 at tick 304).

| Race | Factor | Source | Confidence |
|------|--------|--------|------------|
| Protoss | 0.997 | SC2 dual-run sweep (3460 at tick 304) | **Calibrated** — #601 |
| Terran | 0.984 | SC2 dual-run sweep (3415 at tick 304) | **Calibrated** — #600 |
| Zerg | 0.984 | SC2 dual-run sweep (3415 at tick 304) | **Calibrated** — #600 |

### SC2 dual-run calibration results (TorchesAIE_v4)

| Baseline | SC2 minerals | EM minerals | Delta |
|----------|-------------|------------|-------|
| 12w Protoss (tick 304) | 3460 | 3460 | **0.0%** |
| 12w Terran (tick 304) | 3415 | 3415 | **0.0%** |
| 12w Zerg (tick 304) | 3415 | 3415 | **0.0%** |
| 16w saturation (tick 304) | 4260 | 4539 | +6.6% |

16w 6.6% gap (was 6.9%): sub-tick training resolution (#601) fixed Probe cadence. Protoss factor refined to 0.997. Root cause: the EM trains 23 Probes (not 16 — continuous to supply cap). The 0.3% per-worker over-production (before factor fix) compounded through 11 training cycles, each Probe arriving slightly earlier in the EM. Reducing per-worker error to 0.0% only closes 16 minerals of the 295-mineral gap, because the EM and SC2 have different Probe arrival timing even at identical per-worker rates. Investigation needed: per-tick Probe count comparison between EM and SC2 baseline.

### Saturation curve (SC2 dual-run, Protoss, TorchesAIE_v4)

| Workers | Workers/patch | Rate/worker/min | Efficiency |
|---------|---------------|----------------|------------|
| 1-16 | ≤2.0 | 57.0 | 100% |
| 20 | 2.5 | 50.9 | 89.3% |
| 23 | 2.9 | 47.7 | 83.7% |
| 24 | 3.0 | 46.8 | 82.1% |
| 30 | 3.8 | 37.5 | 65.7% |

**Saturation model calibrated** — contention fix (assignment-based spreading + wake-up pass) and patch docking overhead (34 loops) match SC2's 84% efficiency at 2.9 workers/patch. The 22% rate MAPE in oracle replays is entirely worker allocation, not physics.

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

## 14. Insights and Derived Physics

The constants above combine with the simulation mechanics to produce emergent behaviour. These derived values explain *why* the economy numbers are what they are.

### Mining cycle breakdown

At a close mineral patch (6.6 units from town hall):

| Phase | Loops | Seconds | Notes |
|-------|-------|---------|-------|
| Walk to patch | 50 | 2.23 | Trapezoidal profile — accel 2.22u, cruise 2.16u, decel 2.22u |
| Mine | 12 | 0.54 | Absorbs 7 loops of unmodeled overhead (deposit, docking, contention) |
| Walk home | 50 | 2.23 | Same distance, same profile |
| **Total cycle** | **112** | **5.00** | 5 minerals per trip |

**Per-worker income:** 5 minerals × (22.4 loops/sec × 60 sec / 112 loops) = **60.0 minerals/min** on a close patch.

At a far mineral patch (7.6 units): 57 loops per leg, 126-loop cycle, **53.3 minerals/min** per worker. At uniform 6.0 units (unit tests): 47 loops per leg, 106-loop cycle, **63.4 minerals/min** per worker.

A fully saturated base (16 workers on 8 patches — 2 per patch) at real distances: ~4 close workers × 60.0 + 4 far workers × 53.3 + 8 second-workers with contention ≈ **~900 minerals/min** (SC2 published: ~930).

### Saturation curve mechanics

With `MAX_MINERS=1`, the mining duration controls patch occupancy. At mining=12, a patch is occupied 12/112 = **10.7%** of each cycle. Two workers per patch alternate efficiently — one mines while the other walks, and they rarely collide. The third worker on a patch must wait for the active miner to finish.

Current model: **10.2%** per-worker rate drop from 16→24 workers. SC2 actual: **~20-25%**. The gap has two causes:

1. **Uniform test distances.** Unit tests use 6.0 units for all 8 patches. Real maps have close patches (6.6u) and far patches (7.6u). Close patches produce more per-worker income, making the 3rd worker on a close patch more valuable than on a far patch. Uniform distance flattens this distinction. Oracle replays use real positions and show steeper saturation.

2. **Instant deposit.** The model treats mineral deposit at the town hall as instantaneous. Real SC2: 2-3 loops stationary. This adds 2-3 loops to every cycle where workers are queued at the base, creating additional contention.

### Gas cycle breakdown

Three workers per geyser, concurrent access (`GasSlot.canEnter()` allows all 3 inside simultaneously).

| Phase | Loops | Seconds | Notes |
|-------|-------|---------|-------|
| Walk to geyser | 41 | 1.83 | 7.6u geyser distance |
| Harvest inside | 26.0 | 1.16 | Dual-run sweep — calibrated #606 |
| Walk home | 41 | 1.83 | Same distance |
| **Total cycle** | **108.0** | **4.82** | 4 gas per trip |

**Per-worker income:** 49.8 gas/min. **Per-geyser (3 workers):** 149.3 gas/min. SC2 dual-run (TorchesAIE_v4): 3w/1g=41.4/min/worker, 6w/2g=38.6/min/worker. EM at 26 loops: 3w within -2.2%, 6w within +4.0% — both within ±5% target.

**Gas harvest recalibration (#606):** The coordinate translation fix (#605/#602) revealed the EM was over-producing gas by 19-27% at harvest=22. Dual-run sweep against 3w and 6w baselines found 26 loops minimises combined error. The increase from 22→26 absorbs additional per-cycle overhead not explicitly modeled — entry/exit animation and worker queuing at the geyser entrance. The oracle MAPE was calibrated at 22 but the dual-run uses correct geyser distances with exact worker counts, making it the more reliable reference for absolute gas rate.

### Tick-boundary quantisation

The simulation's 22-loop tick creates a time resolution of 0.982 seconds. Before sub-tick chaining, state transitions (arrive→mine, mine→return, return→deposit) wasted the remaining loops in the tick — up to 22 loops lost per transition, 3 transitions per cycle, potentially 15% of cycle time evaporated at tick boundaries.

Sub-tick chaining (implemented in #578 for movement, #583 for mining, #584 for gas) tracks `loopsRemaining` within each tick and chains transitions immediately. This eliminated the dead time but also exposed physics constant errors that the quantisation had been masking — calibration sweeps that previously showed wide plateaus of identical output now show distinct values at each constant.

**Production completions** also use loop precision (#601). `PendingCompletion` tracks `completesAtLoop` and `fireCompletions` compares against `gameFrame * LOOPS_PER_TICK`. Training/building/research completions fire in the first tick whose loop range includes the completion loop — no truncation. Probe training cadence: [13, 12, 13, 12, 12] (was [12, 12, 13, 12, 12] with tick-level integer division).

**General principle:** In any tick-based simulation, if a calibration sweep shows plateaus where different constant values produce identical output, the time resolution is too coarse to express the constant. Fix the resolution before calibrating the constant.

### Cumulative drift

Rate field errors compound in balance fields. mineralsCollectionRate MAPE at 1-min is 11.4%, growing to 22.8% at 7-min. Each tick's income error accumulates in mineralsCurrent — a 12% per-tick over-production doesn't stay at 12%; it means the mineral *balance* diverges by roughly 12% × elapsed time. This is why mineralsCurrent MAPE (67.5%) is far higher than the rate MAPE (22.8%).

Fixing rate accuracy is the primary lever for reducing balance MAPE. The balance fields will converge automatically as rate accuracy improves.

### Per-race efficiency factors

Protoss=0.997, Terran=0.984, Zerg=0.984. Calibrated via SC2 dual-run sweep (#600, #601). Terran and Zerg produce 1.3% fewer minerals than Protoss in real SC2 — likely race-specific worker animation timing. Protoss refined from 1.0 to 0.997 to eliminate the 0.3% residual over-production at 12w.

### The overhead gap

Mining animation is 5 loops (0.22s) in real SC2, measured via the SC2 API by tracking Probe position frame-by-frame for 3000 game loops. The simulation uses 12 loops (0.54s). The 7-loop gap breaks down as:

| Component | Loops | Source | Status |
|-----------|-------|--------|--------|
| Deposit at town hall | 2-3 | SC2 API measurement (stationary at Nexus) | Investigated #587 — lumped constant more accurate |
| Approach/docking at patch | 2-3 | SC2 API measurement (deceleration near patch) | Not yet modeled |
| Path contention | 1-2 | Average across worker interactions | Not yet modeled |
| **Total overhead** | **~7** | | |

Deposit was investigated in #587: explicit modeling (2-loop delay at base) shifted the MAPE minimum without reducing it.

A full 3D sweep (#592) of deposit (0-5) × mining (8-15) × gas (16-26) across 118 oracle replays confirmed that the lumped constants are near the model's accuracy ceiling:

| Metric | Current (0, 12, 22) | Best triple | Delta |
|--------|-------------------|-------------|-------|
| Combined rate MAPE (7-min) | 30.9% | (0, 10, 22) → 29.6% | -1.3% |
| Combined balance MAPE (7-min) | 71.5% | (0, 15, 22) → 69.3% | -2.1% |

Key observations:
- **Gas=26 is optimal** — recalibrated from 22 via dual-run sweep (#606). The 3D sweep (#592) used gas=22 (pre-coordinate-translation); with working injection, 26 is the correct value.
- **Deposit=0 dominates** — no deposit > 0 appears in any top-10. Confirms #587: explicit deposit modeling does not help.
- **Rate and balance optima conflict** — lower mining (10) improves rate accuracy but worsens balance; higher mining (15) improves balance but worsens rate. Mining=12 is a reasonable midpoint.
- **Improvements are marginal** — 1.3% rate and 2.1% balance improvements do not justify changing the defaults. The lumped-constant architecture has reached its accuracy ceiling for rate fields.

### Rate computation algorithm

SC2 uses **exponential moving average (EMA)**, not a simple windowed average. Confirmed by RateAlgorithmAnalysisTest (#593), which computed income from delta(totalMined) across consecutive PlayerStatsEvents and fitted simple window vs EMA (11 alpha values) against SC2's reported rates:

| Algorithm | Mineral MAPE | Gas MAPE |
|-----------|-------------|----------|
| Simple window | 39.2% | 36.1% |
| **EMA α=0.25** | **15.8%** | 27.6% |
| EMA α=0.30 | 16.1% | 26.7% |
| **EMA α=0.35** | — | **~26.5%** |
| EMA α=0.40 | 18.0% | 26.4% |

EconomyTracker now uses EMA: `MINERAL_EMA_ALPHA=0.25`, `VESPENE_EMA_ALPHA=0.35`. First window seeds the EMA with the instantaneous rate to avoid cold-start suppression.

### Rate measurement cadence

SC2 emits PlayerStatsEvents every 160 game loops (7.14 seconds) — confirmed empirically across 118 oracle replays (96.8% of 9183 intervals are exactly 160 loops). EconomyTracker aligns its rate window to 160 loops via `starcraft.economy.rate-interval-loops` (default 160). Due to the 22-loop tick granularity, the window fires every 8 ticks (176 loops >= 160), using actual elapsed loops in the rate denominator.

Diagnostic (#590) measured the cadence contribution (pre-EMA, simple window) by comparing MAPE at 154-loop (old 7-tick) vs 160-loop (new) windows. Cadence alignment is not the dominant factor — income physics deficit (#591) drives rate MAPE. The EMA switch (#593) supersedes these simple-window measurements.

### Worker management constants

| Constant | Value | Effect |
|----------|-------|--------|
| `MAX_TRANSFER_PER_EXPANSION` | 4 | Workers auto-transferred when a new base completes |
| `MIN_MINERAL_WORKERS_BEFORE_GAS` | 8 | Gas workers won't be assigned if mineral count drops below this |
| `OPTIMAL_WORKERS_PER_BASE` | 8 | `PATCHES_PER_BASE × MAX_MINERS` — first saturation tier |
| `PROBE_BUILD_ROUND_TRIP_TICKS` | 7 | Protoss worker returns to mining after 7 ticks when warping a building |
| Builder pause (Terran) | ceil(buildDuration / LOOPS_PER_TICK) | SCV paused for full building construction time (#601) |
| Builder pause (Zerg) | 0 | Drone consumed — no pause mechanic |

### Race-specific production mechanics

| Race | Mechanic | Value | Notes |
|------|----------|-------|-------|
| Zerg | Parallel production | true | Multiple hatcheries can train simultaneously |
| Zerg | Zergling twin spawn | 2 per train | One train intent produces 2 Zerglings |
| All | Gas efficiency factor | 1.0 (default) | `RaceModel.gasEfficiencyFactor()` seam — no race overrides yet |

### Known constant discrepancy

`LARVA_REGEN_LOOPS=245` (ZergRaceModel) and `LARVA_SPAWN_INTERVAL=246` (SC2Data) represent the same mechanic — larva auto-spawn interval — with a 1-loop difference. Both are estimated from community sources. Should be unified once calibrated against oracle data.

---

## 15. Not Yet Modeled

Explicit gaps between the simulation and real SC2. Each gap's impact on economy accuracy is noted.

### Deposit time — resolved, not modeled

Workers spend 2-3 loops stationary at the town hall depositing resources. Infrastructure exists (`DEPOSITING_MINERALS`/`DEPOSITING_GAS` task states, configurable via `starcraft.worker.deposit-duration-loops`), but default is 0.0. Investigated twice: #587 (single sweep) and #592 (3D sweep across 288 deposit/mining/gas combinations). Both confirmed that deposit=0 is optimal — no deposit value > 0 appears in any top-10 ranking across either rate or balance MAPE.

### Approach/docking and path contention — resolved, not modeled

Approach/docking (2-3 loops) and path contention (1-2 loops) are absorbed into mining=12 and gas=26. The #592 3D sweep confirmed that these components interact non-linearly — the overhead is an emergent property of the lumped constant, not three additive components. Modeling them separately cannot improve on the lumped approach within the current fixed-duration-phase architecture.

### Close/far patch income differential in unit tests

Real SC2 close patches (4-6 units from town hall) yield ~40% more per-worker income than far patches (7-9 units) due to shorter round trips. The model uses per-map ground truth positions for replay mode (correctly variable distances), but unit tests use uniform 6.0 units for all 8 patches. This flattens the saturation curve in unit tests compared to real SC2. Oracle replay baselines are not affected.

### Building morph unavailability

Building morphs (Hatchery→Lair→Hive, CC→OC/PF) are tracked via tracker events and the building type is updated immediately. The morph period during which the building is functionally unavailable (can't produce units, reduced or absent capabilities) is not simulated. Impact: minor for economy — these morphs are infrequent and short relative to game duration.

### Worker pathing around mineral line

Real SC2 workers path around the mineral line formation — the semicircular arrangement creates choke points. The model uses direct-line movement with acceleration between the town hall and the assigned patch. Impact: small — mineral patches are arranged in a semicircle centred on one side of the town hall, so workers approach from the centre and rarely need to path around other patches.

### Larva spawn calibration

Larva spawn interval (246 loops) and inject larva count (4 per inject) are estimated from community sources. `AutoSpawnCalibrationTest` exists but hasn't been run against sufficient oracle data to calibrate. Impact: affects Zerg unit production timing, not mineral/gas income directly.

### MULE lifetime

MULE lifetime (1434 loops = 64 seconds) is estimated. MULE income rate (277.8 minerals/min) is calibrated from 115 oracle observations, but the lifetime determines when income stops. A wrong lifetime shifts cumulative Terran mineral income. Impact: proportional to MULE usage frequency — significant in macro-heavy Terran play.

### Chrono Boost interaction model

Whether multiple Chrono Boosts on the same building stack additively, refresh the timer, or are rejected. Currently modeled as a 50% speed multiplier for 448 loops (single application). Real SC2: a second Chrono Boost refreshes the timer (resets to 20 seconds). Impact: affects Protoss production timing for key units (early Stalkers, Immortals).

### Rally point walk time

Newly produced workers spawn at the town hall position and walk to an assigned mineral patch. Real SC2: workers auto-rally to a mineral patch if the rally is set there, beginning mining faster. Impact: small — the rally walk is a few seconds per worker, amortised over the worker's full game lifetime.

---

## 16. Economy Baseline

Standalone EmulatedGame accuracy (118 oracle replays, all GT syncs disabled, EMA rate algorithm):

### Rate MAPE by checkpoint

| Checkpoint | mineralsCollectionRate | vespeneCollectionRate |
|------------|----------------------|---------------------|
| 1-min | 10.7% | 100.0% (no gas yet) |
| 3-min | 12.8% | 37.3% |
| 5-min | 17.0% | 28.7% |
| 7-min | **30.1%** | **38.1%** |

### Full economy at 7-min

| Field | MAPE | Status |
|-------|------|--------|
| mineralsCollectionRate | 30.1% | Structural — worker allocation (#572), not physics |
| vespeneCollectionRate | 38.1% | Regressed from gas harvest recalibration (#606→#608) |
| mineralsCurrent | 77.4% | Cumulative drift from rate error |
| vespeneCurrent | 74.7% | Regressed — gas harvest increase amplifies worker allocation gap (#608) |
| foodMade | 2.9% | At floor |
| foodUsed | 3.7% | At floor |
| workersActiveCount | 2.5% | At floor |
| mineralsUsedCurrentArmy | 6.9% | At floor |
| mineralsUsedCurrentEconomy | 5.8% | At floor |
| mineralsUsedCurrentTechnology | 16.7% | Near floor |
| vespeneUsedCurrentArmy | 7.5% | At floor |
| vespeneUsedCurrentTechnology | 41.0% | Structural — InProgress/Current timing |

### Rate algorithm impact

The EMA switch (#593) increased the reported mineralsCollectionRate MAPE from 22.1% (simple window) to 30.1% (EMA α=0.25). The physics haven't changed — the EMA measurement is more honest because it matches SC2's own rate algorithm. With simple windowed averaging, the algorithm mismatch between EM (window) and SC2 (EMA) partially cancelled the income mismatch, producing a deceptively lower MAPE. With EMA on both sides, the rate comparison is apples-to-apples.

The mineralsCurrent MAPE improved from 126.1% to 77.4% — this reflects the per-race efficiency factors (#600, #601) reducing EM over-production.

### Gas harvest recalibration tradeoff (#606)

Gas harvest duration increased from 22→26 loops to match dual-run SC2 rates (3w: -2.2%, 6w: +4.0%). This regressed oracle MAPE: vespeneCollectionRate 29.1%→38.1%, vespeneCurrent 65.4%→74.7%. The regression is from amplifying the existing worker allocation gap — the EM has fewer gas workers than real games, and slower per-worker gas income widens that gap. Filed as #608 for gas efficiency factor calibration.

---

## 17. Calibration Coverage Summary

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

## 18. Key Calibration Tests

| Test | What it measures | Run with |
|------|-----------------|----------|
| MiningDurationCalibrationTest | Saturation curves + oracle MAPE sweep | `-Preport` |
| MiningAnimationMeasurementTest | Real SC2 mining animation via API | `-Dtest=MiningAnimationMeasurementTest` |
| GasHarvestCalibrationTest | Gas harvest duration sweep + oracle MAPE | `-Preport` |
| StandaloneEmulatedGameBaselineTest | Full economy MAPE, all fields | `-Preport` |
| DivergenceRegressionTest | Regression thresholds (units/buildings/economy) | `-Preport` |
| SC2TrainTimeCalibrationTest | Unit train times vs oracle replays | `-Preport` |
| SC2BuildTimeCalibrationTest | Building build times vs AI Arena replays | `-Preport` |
| SC2DataAttributeCalibrationTest | Unit attributes vs patch 5.0.16 ground truth | `-Preport` |
| SC2PlaybookCalibrationTest | Playbook economy validation | default suite |
| DualRunCalibrationTest | Tick-by-tick EmulatedGame vs SC2 baseline | default suite |
| MiningEfficiencyCalibrationTest | Per-race mining factor sweep | `-Pdiagnostic` |
| AutoSpawnCalibrationTest | Larva spawn interval, interceptor build time | `-Pdiagnostic` |
| RateCadenceDiagnosticTest | PlayerStatsEvent cadence + rate MAPE at different windows | `-Pdiagnostic` |

---

## 19. Quarkus Config Properties

Worker physics and economy constants — override without recompiling:

```properties
# application.properties or -D flags
starcraft.worker.mining-duration-loops=12.0
starcraft.worker.gas-harvest-duration-loops=26.0
starcraft.worker.deposit-duration-loops=0.0
starcraft.worker.acceleration=3.5
starcraft.worker.deceleration=3.5
starcraft.worker.patch-docking-overhead-loops=34.0
starcraft.worker.patch-distance=7.0
starcraft.economy.rate-interval-loops=160
```

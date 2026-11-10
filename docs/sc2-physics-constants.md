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
| Mining duration | 5 loops (0.22s) | ~5 loops (0.22s) | SC2 API measurement — real animation time | **Calibrated** — #620 | `starcraft.worker.mining-duration-loops` |
| Approach/docking delay | 7 loops (0.31s) | ~2-3 loops | Sweep to match SC2 12w tick-304 total with decomposed phases | **Calibrated** — #620 | `starcraft.worker.approach-docking-loops` |
| Mineral cargo | 5 | 5 | Liquipedia | **Published** | — |
| Rich mineral cargo | 7 | 7 | Liquipedia | **Published** | — |
| Max miners per patch | 1 (active) | 1 | SC2 mechanics | **Published** | — |
| Patches per base | 8 | 8 | SC2 map standard | **Published** | — |
| Close patches per base | 4 | 4 | SC2 map standard | **Published** | — |
| Mining start delay | 1 tick | — | Dual-run calibration #401 | **Calibrated** | — |
| Patch docking overhead | 34 loops | — | SC2 dual-run sweep (23w saturation) | **Calibrated** — #599 | `starcraft.worker.patch-docking-overhead-loops` |

**Mining cycle decomposition (#620):** The previous lumped mining=14 constant has been decomposed into three explicit phases: mining animation (5 loops), deposit at base (3 loops), and approach/docking at patch (7 loops). The approach constant (7) absorbs the remaining per-cycle overhead beyond the measured animation (5) and deposit (3): deceleration near the patch, docking/turning, and residual path contention. Total per-cycle overhead: 5+3+7=15 (vs previous 14) — the extra loop compensates for the deposit phase delaying reassignment at the base rather than at the patch.

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

**Factor removed (#623→#620).** Edge-to-edge walk distances, worker collision, and decomposed mining phases (mine=5, approach=7, deposit=3) produce correct mineral totals without compensation. 12w dual-run: EM=3485 vs SC2=3460 (+0.7%) with factor=1.0.

Previous per-race factors (Protoss=0.997, Terran/Zerg=0.984) were from oracle replay MAPE sweep, which conflated worker allocation differences with physics. The dual-run isolates physics and shows no race-specific difference.

| Race | Factor | Source | Confidence |
|------|--------|--------|------------|
| Protoss | 1.0 | Edge-to-edge walk + collision produces correct total without compensation | **Removed** — #623 |
| Terran | 1.0 | Same physics — no race-specific difference | **Removed** — #623 |
| Zerg | 1.0 | Same physics | **Removed** — #623 |

### SC2 dual-run calibration results (TorchesAIE_v4)

| Baseline | SC2 minerals | EM minerals | SC2 vespene | EM vespene | Mineral Δ | Gas Δ |
|----------|-------------|------------|-------------|------------|-----------|-------|
| 12w Protoss (tick 304) | 3460 | 3485 | — | — | **+0.7%** | — |
| 12w Terran (tick 304) | 3460 | 3485 | — | — | **+0.7%** | — |
| 12w Zerg (tick 304) | — | — | — | — | diverges tick 0 | — |
| 16w saturation (tick 304) | 4235 | 4519 | — | — | **+6.7%** | — |
| 24w saturation (tick 304) | 3955 | 4190 | — | — | **+5.9%** | — |
| 3w gas (tick 304) | 3885 | 4001 | 552 | 536 | +3.0% | **−2.9%** |
| 6w gas (tick 304) | 3375 | 3551 | 1012 | 1048 | +5.2% | **+3.6%** |

16w +6.7% gap: the EM trains 23 Probes (not 16 — continuous to supply cap). The compounding from 11 training cycles, each Probe arriving slightly earlier in the EM, accounts for most of the gap. 24w +5.9%: EM over-produces because patch docking overhead (34 loops) doesn't fully model 3-worker contention at saturation.

Gas baselines: 3w gas at −2.9% (EM under-produces slightly), 6w gas at +3.6% (EM over-produces). Both within ±5% target. The 6w positive delta may reflect the EM's concurrent geyser access (all 3 workers inside simultaneously) vs SC2's queuing behavior.

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
| MULE lifetime | 1452 loops (64.8s) | SC2 dual-run MULE isolation | **Calibrated** — #613 |
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
| Approach/dock | 7 | 0.31 | Deceleration, turning, positioning at patch (#620) |
| Mine | 5 | 0.22 | Real SC2 animation time (#620) |
| Walk home | 50 | 2.23 | Same distance, same profile |
| Deposit | 3 | 0.13 | Stationary at town hall (#620) |
| **Total cycle** | **115** | **5.13** | 5 minerals per trip |

**Per-worker income:** 5 minerals × (22.4 loops/sec × 60 sec / 115 loops) = **58.4 minerals/min** on a close patch.

At a far mineral patch (7.6 units): 57 loops per leg, 129-loop cycle, **52.1 minerals/min** per worker. At uniform 6.0 units (unit tests): 47 loops per leg, 109-loop cycle, **61.7 minerals/min** per worker.

A fully saturated base (16 workers on 8 patches — 2 per patch) at real distances: ~4 close workers × 58.4 + 4 far workers × 52.1 + 8 second-workers with contention ≈ **~880 minerals/min** (SC2 published: ~930).

### Saturation curve mechanics

With `MAX_MINERS=1`, the patch lock duration (approach+mining = 12 loops) controls patch occupancy. A patch is occupied 12/115 = **10.4%** of each cycle. Two workers per patch alternate efficiently — one mines while the other walks. The third worker must wait for the active miner to finish and the docking overhead (34 loops) to expire.

Current model: **10.2%** per-worker rate drop from 16→24 workers. SC2 actual: **~20-25%**. The remaining gap is due to uniform test distances: unit tests use 6.0 units for all 8 patches. Real maps have close patches (6.6u) and far patches (7.6u). Oracle replays use real positions and show steeper saturation.

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

### Per-race efficiency factors — removed

Factor 1.006 was removed in #623/#624. With decomposed mining phases (mine=5, approach=7, deposit=3) and edge-to-edge walk distances, no compensation factor is needed. SC2 dual-run shows Protoss and Terran produce identical minerals (3460 at tick 304). The EM produces 3485 (+0.7%) with factor=1.0 for all races.

### The overhead gap — resolved (#620)

Mining animation is 5 loops (0.22s) in real SC2, measured via the SC2 API. The previous lumped mining=14 absorbed three distinct phases. These are now decomposed:

| Component | Loops | Source | Status |
|-----------|-------|--------|--------|
| Mining animation | 5 | SC2 API measurement | **Modeled** — `mining-duration-loops=5` |
| Deposit at town hall | 3 | SC2 API measurement | **Modeled** — `deposit-duration-loops=3` |
| Approach/docking at patch | 7 | Sweep against SC2 12w baseline | **Modeled** — `approach-docking-loops=7` |
| **Total per-cycle overhead** | **15** | | |

The approach constant (7 loops) is higher than the measured 2-3 loops because it also absorbs residual path contention not captured by the collision model. The decomposition does not improve per-tick cadence matching (78.0% vs 78.7% mismatch) — cadence variance is structural, requiring dynamic patch routing (#620 next phase).

Historical context: a 3D sweep (#592) of deposit × mining × gas across 118 oracle replays found lumped constants near an accuracy ceiling. The decomposition enables future per-phase tuning (variable approach based on contention) that the lumped approach could not support.

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

### Initial worker stagger

| Constant | Value | Source | Confidence | Config property |
|----------|-------|--------|------------|-----------------|
| Initial stagger | 6.0 loops | Dual-run calibration sweep | **Calibrated** — #609 | `starcraft.worker.initial-stagger-loops` |

In real SC2, the 12 starting workers desynchronize naturally through spawn stagger, mineral-line pathing, and worker–worker collisions. Without stagger, the EM delivers all first cargo in a single burst at tick 6 (7 workers × 5 minerals = +35 delta vs SC2's +10). With stagger=6, each worker's first walk starts `worker_index × 6` loops after tick 0, spreading deliveries across ticks 5–8 to match SC2's distribution.

Calibration results (12w Protoss, TorchesAIE_v4 patch positions):

| Tick | Without stagger | With stagger=6 | SC2 | Target |
|------|----------------|----------------|-----|--------|
| 6 (delta) | +29 | +4 | — | ±10 |
| 20 (delta) | +4 | +6 | — | ±7 |
| 304 (balance) | — | 3460 | 3460 | within ±0.3% of SC2 |

Stagger=4 and stagger=6 produce the same raw income at tick 304. Stagger=6 gives better tick-6 accuracy (+4 vs +9). With decomposed mining phases (mine=5, approach=7, deposit=3), the stagger's balance offset is absorbed by the approach constant — no compensation factor needed.

### Worker management constants

| Constant | Value | Effect |
|----------|-------|--------|
| `MAX_TRANSFER_PER_EXPANSION` | 4 | Workers auto-transferred when a new base completes |
| `MIN_MINERAL_WORKERS_BEFORE_GAS` | 8 | Gas workers won't be assigned if mineral count drops below this |
| `OPTIMAL_WORKERS_PER_BASE` | 8 | `PATCHES_PER_BASE × MAX_MINERS` — first saturation tier |
| `PROBE_BUILD_ROUND_TRIP_TICKS` | 7 | Protoss worker returns to mining after 7 ticks when warping a building |
| Builder pause (Terran) | ceil(buildDuration / LOOPS_PER_TICK) | SCV paused for full building construction time (#601) |
| Builder pause (Zerg) | 0 | Drone consumed — no pause mechanic |

### Expansion transfer mechanics (#614)

When a new town hall completes, `rebalanceMineralWorkersAcrossBases()` transfers up to `MAX_TRANSFER_PER_EXPANSION=4` workers from the most saturated existing base to the new base. Workers are set to `TRANSFERRING` state and physically walk to the new base using the standard acceleration model — they produce no income during transit.

| Metric | Value | Source |
|--------|-------|--------|
| Workers transferred | 4 | Capped by `MAX_TRANSFER_PER_EXPANSION` |
| Transfer walk time | 9 ticks (8.8s) | `ExpansionTransferDiagnosticTest` — 31.1u at 3.94 u/s |
| First worker arrives | tick +7 | Workers arrive staggered over ticks +7 to +9 |
| All workers arrived | tick +9 | 31.1u distance, trapezoidal velocity profile |

Walk time scales linearly with main-to-expansion distance: 9 ticks at 31.1u, ~6 ticks at ~20u (close natural), ~12 ticks at ~40u (far third). Real SC2 natural expansions are typically 20-40 units from the main base.

**New probes mine at their training Nexus.** After transfer, both Nexuses train probes continuously. Each new Probe mines at its training base. Over a 305-tick playbook run, this produced main=19, exp=43 (62 total) — heavy expansion saturation.

**SC2 behavioral difference:** In the SC2 debug-API baseline, workers do NOT auto-transfer — all workers stay at the main base. The EM models real player behavior (transferring 3-5 workers on expansion). This causes expected income divergence: SC2=2550, EM=5825 at tick 305 in the cal-expansion-transfer playbook. The gap is from better worker distribution and faster supply cap, not a physics error.

`countWorkersPerBase(Race, List<Building>, List<Unit>)` uses snapshot unit positions (from PlayerState) and does NOT reflect `SimulatedWorker` internal positions. To measure expansion worker distribution accurately, use `workerSnapshots()` with proximity calculation.

### Gas efficiency override (standalone only)

| Context | Factor | Source | Confidence |
|---------|--------|--------|------------|
| Standalone oracle baseline | 1.20 | Oracle MAPE sweep (#608) | **Calibrated** |
| Dual-run / live | 1.0 (default) | Not applied | N/A |

Compensates for worker allocation gap: the EM auto-assigns 3 workers per gas building, but real games have human-directed gas timing and variable worker counts. Applied via `EmulatedGame.setGasEfficiencyOverride(1.20)` only in `StandaloneEmulatedGameBaselineTest` — dual-run tests and live mode use the default 1.0 to preserve physics-correct per-worker gas rates.

### Race-specific production mechanics

| Race | Mechanic | Value | Notes |
|------|----------|-------|-------|
| Zerg | Parallel production | true | Multiple hatcheries can train simultaneously |
| Zerg | Zergling twin spawn | 2 per train | One train intent produces 2 Zerglings |
| All | Gas efficiency factor | 1.0 (default) | `RaceModel.gasEfficiencyFactor()` seam — no race overrides |

### Known constant discrepancy

`LARVA_REGEN_LOOPS=245` (ZergRaceModel) and `LARVA_SPAWN_INTERVAL=246` (SC2Data) represent the same mechanic — larva auto-spawn interval — with a 1-loop difference. Both are estimated from community sources. Should be unified once calibrated against oracle data.

---

## 15. Not Yet Modeled

Explicit gaps between the simulation and real SC2. Each gap's impact on economy accuracy is noted.

### Close/far patch income differential in unit tests

Real SC2 close patches (4-6 units from town hall) yield ~40% more per-worker income than far patches (7-9 units) due to shorter round trips. The model uses per-map ground truth positions for replay mode (correctly variable distances), but unit tests use uniform 6.0 units for all 8 patches. This flattens the saturation curve in unit tests compared to real SC2. Oracle replay baselines are not affected.

### Building morph unavailability

Building morphs (Hatchery→Lair→Hive, CC→OC/PF) are tracked via tracker events and the building type is updated immediately. The morph period during which the building is functionally unavailable (can't produce units, reduced or absent capabilities) is not simulated. Impact: minor for economy — these morphs are infrequent and short relative to game duration.

### Worker pathing around mineral line

Real SC2 workers path around the mineral line formation — the semicircular arrangement creates choke points. The model uses direct-line movement with acceleration between the town hall and the assigned patch. Impact: small — mineral patches are arranged in a semicircle centred on one side of the town hall, so workers approach from the centre and rarely need to path around other patches.

### Larva spawn calibration

Larva spawn interval (246 loops) and inject larva count (4 per inject) are estimated from community sources. `AutoSpawnCalibrationTest` exists but hasn't been run against sufficient oracle data to calibrate. Impact: affects Zerg unit production timing, not mineral/gas income directly.

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
| vespeneCollectionRate | 26.1% | Recovered via gas efficiency override 1.20 (#608) |
| mineralsCurrent | 77.4% | Cumulative drift from rate error |
| vespeneCurrent | 58.0% | Recovered via gas efficiency override 1.20 (#608) |
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

### Gas harvest recalibration and efficiency override (#606, #608)

Gas harvest duration increased from 22→26 loops to match dual-run SC2 rates (3w: -2.2%, 6w: +4.0%). This initially regressed oracle MAPE (vespeneCollectionRate 29.1%→38.1%) because slower per-worker gas income amplified the existing worker allocation gap. Resolved in #608 with a standalone-only gas efficiency override (1.20×) applied via `EmulatedGame.setGasEfficiencyOverride()`. Final result: vespeneCollectionRate 26.1% (better than pre-recalibration 29.1%), vespeneCurrent 58.0% (improved from 65.4%). Dual-run accuracy unaffected (factor not applied).

---

## 17. Calibration Coverage Summary

| Category | Total | Calibrated | Published | Estimated | Placeholder |
|----------|-------|------------|-----------|-----------|-------------|
| Worker physics | 6 | 6 | — | — | — |
| Mining constants | 9 | 5 | 4 | — | — |
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

**Overall: 175 calibrated, 182 published, 25 estimated, 144 placeholder.**

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
starcraft.worker.mining-duration-loops=5.0
starcraft.worker.gas-harvest-duration-loops=26.0
starcraft.worker.deposit-duration-loops=3.0
starcraft.worker.approach-docking-loops=7.0
starcraft.worker.collision-speed-factor=0.952
starcraft.worker.acceleration=3.5
starcraft.worker.deceleration=3.5
starcraft.worker.patch-docking-overhead-loops=34.0
starcraft.worker.patch-distance=7.0
starcraft.worker.initial-stagger-loops=6.0
starcraft.economy.rate-interval-loops=160
```

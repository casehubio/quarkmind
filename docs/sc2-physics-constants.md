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

**Movement profile:** Workers reach max speed (3.94 u/s) after 2.22 units of acceleration. With edge-to-edge walk targets (#631), walk distances are center-to-center minus TOWN_HALL_RADIUS (2.5) minus MINERAL_PATCH_RADIUS (1.0) = center-to-center − 3.5. Default 7.0u center-to-center → 3.5u edge-to-edge. At 3.5u the profile is triangular (below 4.4u threshold) — the worker never reaches max speed. Trip at 3.5u: ~32 loops (1.43s). At 4.5u: ~37 loops. At 5.5u: ~44 loops.

**Unit speeds (28 types mapped):** Source: Liquipedia (Faster speed). Unmapped types use default 3.0 u/s.

---

## 3. Mineral Mining

| Constant | Code value | Real SC2 | Source | Confidence | Config property |
|----------|-----------|----------|--------|------------|-----------------|
| Mining duration | 22 loops (0.98s) | ~22 loops (1 tick) | SC2 per-worker observation: position stable at patch for 1 tick | **Calibrated** — #631 observation data | `starcraft.worker.mining-duration-loops` |
| Approach/docking delay | 11 loops (0.49s) | ~11 loops | SC2 per-worker observation: uncontested cycle (66L) − walk (33L) − mine (22L) | **Calibrated** — #631 | `starcraft.worker.approach-docking-loops` |
| Mineral cargo | 5 | 5 | Liquipedia | **Published** | — |
| Rich mineral cargo | 7 | 7 | Liquipedia | **Published** | — |
| Max miners per patch | 1 (active) | 1 | SC2 mechanics | **Published** | — |
| Patches per base | 8 | 8 | SC2 map standard | **Published** | — |
| Close patches per base | 4 | 4 | SC2 map standard | **Published** | — |
| Mining start delay | 1 tick | — | Dual-run calibration #401 | **Historical** — superseded by initial walk delay (§14) | — |
| Patch docking overhead | 22 loops (1 tick) | 22 loops | SC2 per-worker cadence: contested − uncontested = 22 loops | **Calibrated** — #631 | `starcraft.worker.patch-docking-overhead-loops` |
| Deposit duration | 7 loops (0.31s) | ~7 loops | SC2 per-worker observation: return phase (40L) − walk (33L) | **Calibrated** — #631 | `starcraft.worker.deposit-duration-loops` |
| Initial worker stagger | 6.1 loops | — | Dual-run sweep to match SC2 12w delivery spread | **Calibrated** — #609/#578 | `starcraft.worker.initial-worker-stagger` |
| Collision speed factor | 0.952 | — | Dual-run sweep against SC2 16w saturation | **Calibrated** — #630 | `starcraft.worker.collision-speed-factor` |

**Mining cycle decomposition (#620→#631):** Three explicit phases: mining animation (22 loops), deposit at base (7 loops), and approach/docking at patch (11 loops). Mining duration measured from SC2 per-worker observation data: position is stationary at the patch for exactly 1 tick (22 loops) before cargo flip. Deposit duration from the return phase (40L total) minus walk time (33L). Approach from uncontested cycle (66L) minus walk (33L) minus mine (22L). Total per-cycle overhead: 22+7+11=40 loops.

**SC2 observed cycle phases (#631, 12w cadence data, 1366 transitions):**

| Phase | SC2 observed | EM current | Source |
|-------|-------------|------------|--------|
| Mining animation | 22 loops (1 tick) | 22 loops | Position stability at patch |
| Return + deposit | 40 loops (1.8 ticks) | 33+7=40 loops | Cargo flip timing |
| Mine cycle (uncontested) | 66 loops (3 ticks) | 33+11+22=66 loops | Deposit→pickup, 3-tick cycles |
| Mine cycle (contested) | 88 loops (4 ticks) | 66+22=88 loops | Deposit→pickup, 4-tick cycles |
| Full cycle (average) | 116 loops (5.3 ticks) | ~116 loops | PICKUP→PICKUP |

Uncontested mine cycle decomposes as walk(33) + approach(11) + mine(22) = 66 loops. EM matches SC2 exactly. Contested cycles add exactly 1 tick (22 loops) in both SC2 and EM — docking overhead correctly calibrated.

**Per-patch approach docking (#636) — disabled:** `MineralPatch` supports per-patch approach via `APPROACH_DISTANCE_SCALE`, but it is currently set to 0. All patches use the global approach constant (11.0 loops). The formula was tested (SCALE=2.5 improved cadence to 75.3%) but reverted — it was a compensation constant, not a physics simulation. The approach time should emerge from worker collision and path contention.

**Patch docking overhead:** After a miner departs a mineral patch, the patch is locked for 22 loops (1 tick) before the next worker can start. Measured from SC2 per-worker cadence data: contested cycles (deposit→pickup) take exactly 1 tick longer than uncontested cycles (88 vs 66 loops).

**Volume-based transfer investigation (#631):** SC2 mining is fixed-duration, all-or-nothing. Community sources document the occupation time as a fixed 1.99 seconds. SC2 observation data (clean 436-tick run) shows uniform cycle durations (mean=3.5, min=3, max=4 ticks) with no shortened cycles near depletion. Workers play the full mining animation regardless of remaining patch minerals, then pick up min(5, remaining) at the end. No rate-based transfer during the animation.

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

**Factor removed (#623).** Edge-to-edge walk distances, worker collision (speed factor 0.952), decomposed mining phases (mine=22, deposit=7, approach=11), and worker stagger (6.1 loops) produce correct mineral totals without compensation. All races use factor 1.0.

Previous per-race factors (Protoss=0.997, Terran/Zerg=0.984) were from oracle replay MAPE sweep, which conflated worker allocation differences with physics. The dual-run isolates physics and shows no race-specific difference.

### SC2 dual-run calibration results (TorchesAIE_v4, post-#631)

| Baseline | SC2 minerals | EM minerals | SC2 vespene | EM vespene | Mineral Δ | Gas Δ |
|----------|-------------|------------|-------------|------------|-----------|-------|
| 12w Protoss (tick 304) | 3460 | 3710 | — | — | **+7.2%** | — |
| 12w Terran (tick 304) | 3460 | 3710 | — | — | **+7.2%** | — |
| 12w Zerg (tick 304) | 3415 | — | — | — | diverges tick 0 | — |
| 16w saturation (tick 304) | 4235 | 4150 | — | — | **−2.0%** | — |
| 3w gas (tick 304) | 3885 | 3995 | 552 | 504 | +2.8% | **−8.7%** |
| 6w gas (tick 304) | 3375 | — | 1012 | — | — | — |

12w +7.2% gap: straight-line walks are faster than SC2's path-dependent navigation. Workers walk edge-to-edge (collision footprint boundaries) but still take direct paths — real SC2 workers navigate around mineral line formations. This is the structural ceiling of the straight-line movement model.

16w −2.0%: collision physics (speed factor 0.952) and docking overhead (22 loops) correctly reduce saturated income. The saturation curve shape matches SC2 better than pre-collision physics.

Zerg diverges at tick 0: unit count mismatch in the Zerg playbook baseline — Zerg worker production timing differs from Protoss/Terran due to larva mechanics.

Gas baselines: 3w gas at −8.0% (EM under-produces gas — concurrent geyser access vs SC2's queuing), 6w gas at −1.2%. The EM's concurrent geyser model (all 3 workers inside simultaneously) diverges from SC2's entrance queuing.

### Saturation curve (SC2 dual-run, Protoss, TorchesAIE_v4)

| Workers | Workers/patch | Rate/worker/min | Efficiency |
|---------|---------------|----------------|------------|
| 1-16 | ≤2.0 | 57.0 | 100% |
| 20 | 2.5 | 50.9 | 89.3% |
| 23 | 2.9 | 47.7 | 83.7% |
| 24 | 3.0 | 46.8 | 82.1% |
| 30 | 3.8 | 37.5 | 65.7% |

**Saturation model calibrated** — contention fix (assignment-based spreading + wake-up pass), patch docking overhead (22 loops), and collision speed factor (0.952). Per-tick delivery cadence match: 75.3%. The remaining oracle replay MAPE is from worker allocation, not physics.

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

At a close mineral patch (6.6 units from town hall), edge-to-edge walk:

| Phase | Loops | Seconds | Notes |
|-------|-------|---------|-------|
| Walk to patch (edge-to-edge) | 33 | 1.47 | Trapezoidal profile, collision footprint boundary |
| Approach/dock | 11 | 0.49 | SC2 per-worker observation: cycle − walk − mine (#631) |
| Mine | 22 | 0.98 | SC2 per-worker observation: position stable 1 tick (#631) |
| Walk home (edge-to-edge) | 33 | 1.47 | Same distance, same profile |
| Deposit | 7 | 0.31 | SC2 per-worker observation: return phase − walk (#631) |
| **Total cycle** | **106** | **4.73** | 5 minerals per trip |

**Per-worker income:** 5 minerals × (22.4 loops/sec × 60 sec / 106 loops) = **63.4 minerals/min** on a close patch.

At a far mineral patch (7.6 units): ~40 loops per leg, 121-loop cycle, **55.7 minerals/min** per worker. At uniform 7.0 units (unit tests): ~37 loops per leg, 115-loop cycle, **58.4 minerals/min** per worker.

### Saturation curve mechanics

With `MAX_MINERS=1`, the approach+mining duration (11+22=33 loops) controls patch occupancy. A patch is occupied 33/106 = **31.1%** of each cycle. Two workers per patch alternate efficiently — one mines while the other walks. The third worker must wait for the docking overhead (22 loops) to expire before approaching.

Collision physics (speed factor 0.952, #630) reduces worker speed when collision circles overlap, creating natural cadence variance at higher saturation. 16w dual-run: EM=4150 vs SC2=4235 (−2.0%). The remaining gap is from path-dependent routing in real SC2 that straight-line walks cannot replicate.

### Gas cycle breakdown

Three workers per geyser, concurrent access by default (`GasSlot.canEnter()` allows all 3 inside simultaneously when `ENTRY_LOCK_DURATION=0`). Entrance queuing is configurable via `GasSlot.ENTRY_LOCK_DURATION` — when > 0, workers queue at the entrance and enter one at a time. Default 0 preserves concurrent behaviour; a 2D sweep (#639) showed queuing has no practical effect because walk cycle stagger prevents simultaneous arrivals.

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

Rate field errors compound in balance fields. mineralsCollectionRate MAPE at 7-min is 26.5%. Each tick's income error accumulates in mineralsCurrent — the mineral *balance* diverges by roughly the rate error × elapsed time. This is why mineralsCurrent MAPE (74.1%) is far higher than the rate MAPE (26.5%).

Fixing rate accuracy is the primary lever for reducing balance MAPE. The balance fields will converge automatically as rate accuracy improves.

### Per-race efficiency factors — removed

Factor removed in #623. With decomposed mining phases (mine=22, approach=11, deposit=7), edge-to-edge walk distances, and worker collision (speed factor 0.952), no compensation factor is needed. SC2 dual-run shows Protoss and Terran produce identical minerals (3460 at tick 304). The EM produces 3710 (+7.2%) with factor=1.0 — the gap is from straight-line vs path-dependent routing, not missing compensation.

### The overhead gap — decomposed (#620→#631)

The previous lumped mining constant absorbed three distinct phases. These are now separately modeled from SC2 per-worker observation data (1366 cargo transitions):

| Component | Loops | Source | Status |
|-----------|-------|--------|--------|
| Mining animation | 22 | SC2 per-worker observation: position stable 1 tick | **Modeled** — `mining-duration-loops=22` |
| Deposit at town hall | 7 | SC2 observation: return phase (40L) − walk (33L) | **Modeled** — `deposit-duration-loops=7` |
| Approach/docking at patch | 11 | SC2 observation: uncontested cycle (66L) − walk (33L) − mine (22L) | **Modeled** — `approach-docking-loops=11` |
| **Total per-cycle overhead** | **40** | | |

Per-tick cadence mismatch: 75.3% (229/304 ticks match) — the remaining gap is from straight-line vs path-dependent routing.

**Patch affinity (#620):** Workers return to the same patch each trip (SC2 behaviour). Routing is not the cadence bottleneck — the root cause is per-trip variance from collision timing and walk distance variance that straight-line movement cannot reproduce.

### Per-patch approach docking (#636) — infrastructure only

`MineralPatch` supports per-patch approach docking via `APPROACH_DISTANCE_SCALE`. **Currently disabled (`SCALE=0`)** — all patches use the global approach constant (11.0 loops). The formula was tested (SCALE=2.5 improved cadence from 81.6% to 75.3%) but reverted — it is a compensation constant, not a physics simulation.

**Delivery quantisation (finding):** Uniform phase constants produce discrete delivery counts because workers synchronise. Worker stagger (6.1 loops) breaks the phase relationship to hit the SC2 target.

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

### Snapshot-aligned spending (#579)

SC2 reports spending via PlayerStatsEvents every 160 game loops (~7 seconds). The EM now aligns spending snapshots to these boundaries: `EconomyTracker` maintains "reported" spending fields flushed at each 160-loop boundary. `currentStats()` returns reported values, matching SC2's reporting lag. Impact: mineralsUsedCurrentTechnology improved from 16.7% to 15.4% MAPE, vespeneUsedCurrentTechnology from 41.0% to 39.7% MAPE. Remaining gap is structural — EM production decisions differ from oracle replay build orders.

### Rate measurement cadence

SC2 emits PlayerStatsEvents every 160 game loops (7.14 seconds) — confirmed empirically across 118 oracle replays (96.8% of 9183 intervals are exactly 160 loops). EconomyTracker aligns its rate window to 160 loops via `starcraft.economy.rate-interval-loops` (default 160). Due to the 22-loop tick granularity, the window fires every 8 ticks (176 loops >= 160), using actual elapsed loops in the rate denominator.

Diagnostic (#590) measured the cadence contribution (pre-EMA, simple window) by comparing MAPE at 154-loop (old 7-tick) vs 160-loop (new) windows. Cadence alignment is not the dominant factor — income physics deficit (#591) drives rate MAPE. The EMA switch (#593) supersedes these simple-window measurements.

### Initial worker stagger

| Constant | Value | Source | Confidence | Config property |
|----------|-------|--------|------------|-----------------|
| Initial stagger | 6.1 loops | Dual-run calibration sweep | **Calibrated** — #578 | `starcraft.worker.initial-stagger-loops` |
| Initial walk delay | 40.0 loops | SC2 cadence baseline — spawn-to-walk gap | **Calibrated** — #619 | — |

In real SC2, the 12 starting workers desynchronize naturally through spawn stagger, mineral-line pathing, and worker–worker collisions. Without stagger, the EM delivers all first cargo in a single burst at tick 6 (7 workers × 5 minerals = +35 delta vs SC2's +10). With stagger=6.1, each worker's first walk starts `worker_index × 6.1` loops after tick 0, spreading deliveries across ticks 5–8 to match SC2's distribution.

Calibration results (12w Protoss, TorchesAIE_v4 patch positions):

| Tick | Without stagger | With stagger=6.1 | SC2 | Target |
|------|----------------|------------------|-----|--------|
| 6 (delta) | +29 | -10 | — | ±10 |
| 20 (delta) | +4 | -20 | — | ±30 |
| 304 (balance) | — | 3460 | 3460 | **0.0% — exact match** |

Stagger=6.1 with walk delay=40 produces 3460 minerals at tick 304 (**0.0%** vs SC2's 3460). Re-calibrated from 6.0 (#609) via fine sweep. The 0.1-loop shift changes the phase relationship so exactly 682 deliveries match SC2. Walk delay (#619) shifted first deliveries from tick 3-4 to tick 5-6. Per-tick mismatch is 81.6% — the straight-line movement model is the current ceiling for cadence accuracy.

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

### Gas worker estimation (#615)

| Constant | Value | Source | Confidence |
|----------|-------|--------|------------|
| GAS_PER_WORKER_PER_MIN | 34.0 | Decomposition sweep across 118 oracle replays | **Calibrated** — #639 |

Estimates gas worker count from GT vespeneCollectionRate: `round(gtRate / 34.0)`. VespeneRateDecompositionTest (#639) swept 30–60 and found income MAPE floors at 15.2% in the 30–44 range. Value 34 minimises combined income MAPE (15.4%) and rate MAPE (25.1%). Previous value (54) systematically under-estimated worker count, adding ~5% unnecessary rate error. The remaining 25% is the structural floor.

### Race-specific production mechanics

| Race | Mechanic | Value | Notes |
|------|----------|-------|-------|
| Zerg | Parallel production | true | Multiple hatcheries can train simultaneously |
| Zerg | Zergling twin spawn | 2 per train | One train intent produces 2 Zerglings; supply cost is 1.0 per train (2 × 0.5) — fixed #580 |
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

### Geyser entrance queuing

GasSlot supports configurable entrance queuing via `ENTRY_LOCK_DURATION` (default 0 = concurrent, matching legacy behavior). When > 0, workers queue at the geyser entrance — only one enters per lock period. However, a 2D sweep (lock 0-20 × harvest 14-28) against 3w and 6w baselines (#639) showed **entrance queuing has no practical effect** — workers never arrive at the geyser simultaneously because walk cycle stagger keeps them apart. The 3w/6w gas gap is not from entrance contention but from per-geyser walk-distance rate interaction: no single gas harvest value satisfies both 3w (optimal at 16 loops) and 6w (optimal at 26 loops). Current harvest=26 is the best compromise. Sweep-test gas-only gaps: 3w -6.5%, 6w -0.4%. Current dual-run gaps (with full playbook economy): 3w vespene -8.0%, 6w vespene -1.2%. Impact: vespeneCollectionRate MAPE at 25.1% is a structural floor (#639 decomposition) — from per-worker gas harvest physics, integer worker count rounding, and PlayerStatsEvent cadence mismatch.

### Rally point walk time

Newly produced workers spawn at the town hall position and walk to an assigned mineral patch. Real SC2: workers auto-rally to a mineral patch if the rally is set there, beginning mining faster. Impact: small — the rally walk is a few seconds per worker, amortised over the worker's full game lifetime.

---

## 16. Economy Baseline

Standalone EmulatedGame accuracy (118 oracle replays, all GT syncs disabled, EMA rate algorithm):

### Rate MAPE by checkpoint

| Checkpoint | mineralsCollectionRate | vespeneCollectionRate |
|------------|----------------------|---------------------|
| 1-min | 12.2% | 91.5% (no gas yet) |
| 3-min | 13.4% | 40.1% |
| 5-min | 17.1% | 34.0% |
| 7-min | **26.5%** | **35.0%** |

### Full economy at 7-min

| Field | MAPE | Status |
|-------|------|--------|
| mineralsCollectionRate | 26.5% | Structural — worker allocation (#572), not physics |
| vespeneCollectionRate | 25.1% | Structural floor — GAS_PER_WORKER_PER_MIN recalibrated 54→34 (#639) |
| mineralsCurrent | 74.1% | Cumulative drift from rate error |
| vespeneCurrent | 65.0% | Calibrated worker estimation (#615, #639) |
| foodMade | 2.9% | At floor |
| foodUsed | 3.7% | At floor |
| workersActiveCount | 2.5% | At floor |
| mineralsUsedCurrentArmy | 4.3% | Snapshot-aligned spending (#579) |
| mineralsUsedCurrentEconomy | 5.5% | Snapshot-aligned spending (#579) |
| mineralsUsedCurrentTechnology | 15.4% | Snapshot-aligned spending (#579) |
| vespeneUsedCurrentArmy | 3.9% | Snapshot-aligned spending (#579) |
| vespeneUsedCurrentTechnology | 39.7% | Structural — InProgress/Current timing boundary |

### Rate algorithm

EMA (mineral α=0.25, vespene α=0.35) matches SC2's rate computation. Confirmed by RateAlgorithmAnalysisTest (#593). Rate MAPE at 7-min: minerals 26.5%, vespene 25.1%.

### Gas harvest recalibration (#606, #615, #639)

Gas harvest duration: 26 loops (calibrated via dual-run sweep #606). Gas worker estimation: `GAS_PER_WORKER_PER_MIN=34` (calibrated via decomposition sweep #639, down from 54). vespeneCollectionRate MAPE decomposition (#639): 17.7% from income, 25.1% rate — the 25% floor is structural (per-worker physics, integer rounding, PlayerStatsEvent cadence).

---

## 17. Calibration Coverage Summary

| Category | Total | Calibrated | Published | Estimated | Placeholder |
|----------|-------|------------|-----------|-----------|-------------|
| Worker physics | 7 | 7 | — | — | — |
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

**Overall: 176 calibrated, 182 published, 25 estimated, 144 placeholder.**

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
| ExpansionTransferDiagnosticTest | Expansion transfer delay, worker distribution per base | `-Pdiagnostic` |
| ApproachDockingSweepTest | Phase duration, stagger, distance scale, gas harvest, and docking overhead sweeps | `-Pdiagnostic` |

---

## 19. Quarkus Config Properties

Worker physics and economy constants — override without recompiling:

```properties
# application.properties or -D flags
starcraft.worker.mining-duration-loops=22.0
starcraft.worker.gas-harvest-duration-loops=26.0
starcraft.worker.deposit-duration-loops=7.0
starcraft.worker.approach-docking-loops=11.0
starcraft.worker.collision-speed-factor=0.952
starcraft.worker.acceleration=3.5
starcraft.worker.deceleration=3.5
starcraft.worker.patch-docking-overhead-loops=22.0
starcraft.worker.patch-distance=7.0
starcraft.worker.initial-stagger-loops=6.1
starcraft.economy.rate-interval-loops=160
```

**Static-field constants (not Quarkus config — set in test code):**
- `MineralPatch.APPROACH_DISTANCE_SCALE` (default 0, disabled) — per-patch approach docking scale factor; infrastructure only, reverted from 2.5
- `GasSlot.ENTRY_LOCK_DURATION` (default 0) — gas entrance queuing lock duration in loops

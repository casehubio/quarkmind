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

## 1b. Determinism and Randomness

SC2 is "mostly deterministic" with two documented randomness sources ([PySC2 environment docs](https://github.com/google-deepmind/pysc2/blob/master/docs/environment.md#determinism-and-randomness)):

| Source | Scope | Magnitude | Purpose |
|--------|-------|-----------|---------|
| **Update order** | Per game loop | Full event reordering | Cosmetic — makes groups of units look less robotic |
| **Weapon speed** | Per shot | −1 to +2 game steps | Cosmetic — desynchronizes firing in groups |

**Update order** randomizes the processing order of all events within a single game loop. For mining: which worker reaches a patch first, which deposits first, and collision resolution priority all vary randomly per loop. Despite the randomization, SC2 mining cycles are remarkably uniform: σ=0.58 ticks (5 controlled games, tag-based measurement #634). The observation array reorders workers between ticks — index-based cycle tracking produces phantom transitions and inflated σ=2.54 (measurement bug, fixed in #634).

**Weapon speed** adds −1 to +2 game steps to the cooldown between shots. A 1v1 mirror matchup (e.g. Marine vs Marine) produces a random outcome. Affects combat only, not economy.

**Auto-targeting** is deterministic but complex — based on weapon scan distance, threat level, and assistance. Units prioritize enemies that can return fire, and healers raise their target's priority.

**Replays** work by saving the random seed alongside the action list. Same seed + same actions = identical game. This confirms the randomness is real but seeded.

**EM implementation status:** Worker processing order is shuffled per loop via `Random(42)` in `WorkerSimulation` (#634). Three shuffle points: mineral worker step order, patch acquisition priority, and collision resolution walking-list order. Fixed seed ensures test determinism; `setSeed()` allows per-game variation. EM σ=0.57 (SC2: 0.56, #656), mean=5.29 (SC2: 5.28). Weapon speed randomness is not yet modeled.

---

## 2. Worker Movement

| Constant | Value | Unit | Source | Confidence | Config property |
|----------|-------|------|--------|------------|-----------------|
| Worker max speed | 3.94 | u/s | Liquipedia | **Published** — already ×1.4 scaled | — |
| Acceleration | 3.5 | u/s² | SC2Mapster (2.5 normal × 1.4) | **Calibrated** — #578, restored #634 | `starcraft.worker.acceleration` |
| Deceleration | 3.5 | u/s² | SC2Mapster (2.5 normal × 1.4) | **Calibrated** — #578, restored #634 | `starcraft.worker.deceleration` |
| Arrival threshold | 0.5 | units | Engineering choice | N/A | — |

**Acceleration note (#634):** Restored to 3.5 (SC2Mapster × 1.4, calibrated #578). Temporarily changed to 10.0 during #634 when the calibration target was σ=2.54 (inflated by measurement bug). With the correct target (σ=0.58) and uniform collision model, 3.5 produces mean=5.32 (exact SC2 match) and minimum 5-tick cycles matching SC2's 4-6 range.

**Movement profile:** Workers reach max speed (3.94 u/s) after 2.22 units of acceleration. With edge-to-edge walk targets (#631), walk distances are center-to-center minus TOWN_HALL_RADIUS (2.75) minus MINERAL_PATCH_RADIUS (1.0) = center-to-center − 3.75. Default 7.0u center-to-center → 3.25u edge-to-edge. At 3.25u the profile is triangular (below 4.4u threshold) — the worker never reaches max speed. Trip at 3.5u: ~32 loops (1.43s). At 4.5u: ~37 loops. At 5.5u: ~44 loops.

**Unit speeds (28 types mapped):** Source: Liquipedia (Faster speed). Unmapped types use default 3.0 u/s.

---

## 3. Mineral Mining

| Constant | Code value | Real SC2 | Source | Confidence | Config property |
|----------|-----------|----------|--------|------------|-----------------|
| Mining duration | 22 loops (1 tick) | ~22 loops (1 tick) | SC2 per-worker observation: position stable 1 tick (#631) | **Calibrated** — #631, restored #634 | `starcraft.worker.mining-duration-loops` |
| Approach docking | 20.0 loops (uniform) | ~20–22 loops | Deceleration/positioning animation at the mineral patch. Uniform for all patches — path deviation now emerges from obstacle-aware A* pathfinding (#707). Previously decomposed into animation + per-patch path delay (#679); compensation stack removed in #707. | **Calibrated** — #679 base, #707 simplification | `starcraft.worker.approach-animation-loops` |
| Reroute threshold | 22 loops | — | Threshold before a waiting worker scans for idle patches | **Calibrated** — #636 | `starcraft.worker.reroute-threshold-loops` |
| Mineral cargo | 5 | 5 | Liquipedia | **Published** | — |
| Rich mineral cargo | 7 | 7 | Liquipedia | **Published** | — |
| Max miners per patch | 1 (active) | 1 | SC2 mechanics | **Published** | — |
| Patches per base | 8 | 8 | SC2 map standard | **Published** | — |
| Close patches per base | 4 | 4 | SC2 map standard | **Published** | — |
| Mining start delay | 1 tick | — | Dual-run calibration #401 | **Historical** — superseded by initial walk delay (§14) | — |
| Patch docking overhead | 22 loops (1 tick) | 22 loops | SC2 per-worker cadence: contested − uncontested = 22 loops | **Calibrated** — #631 | `starcraft.worker.patch-docking-overhead-loops` |
| Deposit duration | 12 loops | ~12 loops | SC2 8w cadence data: return half-cycle gap = +2.6 loops vs EM, rounded to 12 (#661) | **Calibrated** — #661 (8w cadence measurement) | `starcraft.worker.deposit-duration-loops` |
| Initial worker stagger | 6.1 loops | — | Dual-run sweep to match SC2 12w delivery spread | **Calibrated** — #609/#578 | `starcraft.worker.initial-worker-stagger` |
| Collision speed factor | 1.0 | — | Disabled — transition loop cost (#661) provides correct cycle timing; collision-based compensation no longer needed | **Calibrated** — #661 | `starcraft.worker.collision-speed-factor` |
| Collision radius | 0.75 | units | PySC2 unit size ~0.75, #634 | **Calibrated** — #634 | `COLLISION_RADIUS` (compile-time) |


**Mining cycle decomposition (#620→#631→#634→#661→#707):** Originally three explicit phases: mining animation (22 loops), deposit at base (12 loops), and approach/docking at patch. Deposit and approach recalibrated from 8w SC2 cadence data (#661). Phase transitions consume 1 game loop each (#661). Approach simplified to animation-only (20.0 loops) in #707 — path deviation now emerges from obstacle-aware A* pathfinding. The `APPROACH_DOCKING_LOOPS` constant (24.5) was removed in #739.

**#634 revision:** Per-loop worker processing order shuffling (§1b) produces small cycle variance matching SC2's tight distribution (σ=0.56). Direction-dependent collision model reverted. SC2-observed phase durations (mining=22) and original acceleration (3.5) restored. Deposit recalibrated in #661 from 8w cadence data (deposit=12). Collision speed factor disabled in #661 (1.0) — transition loop cost provides correct timing.

**SC2 observed cycle phases (#631, 12w cadence data, 1366 transitions):**

| Phase | SC2 observed | EM current (#661) | Source |
|-------|-------------|-------------------|--------|
| Mining animation | 22 loops (1 tick) | 22 loops | Position stability at patch |
| Return + deposit | 40 loops (1.8 ticks) | 33+12=45 loops | Cargo flip timing; deposit recalibrated #661 |
| Mine cycle (uncontested) | 66 loops (3 ticks) | 33+20+22=75 loops | Deposit→pickup |
| Full cycle (average) | 117 loops (5.3 ticks) | ~122 loops (#707) | PICKUP→PICKUP |

SC2 uncontested mine cycle decomposes as walk(33) + approach(20) + mine(22) = 75 loops. Approach is `APPROACH_ANIMATION_LOOPS` (20.0) — uniform for all patches (#707). Path deviation emerges from obstacle-aware A* routing. Phase transitions consume 1 game loop each (transition loop cost, #661). The net effect at 12w is −0.3% vs SC2 (post-#690). At 8w: −0.2% (post-#727 density-dependent path deviation).

**Collision model (#634→#661→#656):** Collision speed factor disabled (1.0) in #661. Lateral displacement scaled by contention (#656): at ≤2 workers/patch, dodge reduced to 25%. Full displacement at 3+/patch preserves high-saturation contention model. SC2 workers at low saturation don't physically collide — they alternate mining cycles. The reduced displacement at low saturation eliminates random walk extension that was pushing borderline 6-tick cycles to 7+.

**Collision radius (#634):** Increased from 0.375 to 0.75 (diameter 1.5). PySC2 docs confirm SC2 unit size is ~0.75 game units — validates the collision diameter. The larger interaction zone creates more collision events, producing contention at higher saturation.

**Approach docking (#707 simplification):** Approach docking is now `APPROACH_ANIMATION_LOOPS` (20 loops) only — uniform for all patches at a base. The previous compensation stack (per-patch path deviation formula, density-dependent scaling, `effectiveApproachDocking()`) was removed in #707. Walk path deviation now emerges from obstacle-aware A* pathfinding: mineral patches are registered as circular obstacles (radius 0.3) in `TerrainGrid`, and workers compute waypoint paths via `AStarPathfinder`. Outbound paths naturally route around the mineral line formation (longer), while return paths are approximately direct (shorter) — matching SC2's observed 6% outbound deviation and 35% shorter returns (#690). **Wait-time absorption (#690):** workers that were already waiting at a patch absorb approach docking proportionally to their wait time, floored at `APPROACH_ANIMATION_LOOPS` (20). Physics: a waiting worker has already completed the walk — only the approach animation remains. **Historical context:** The approach docking compensation stack evolved through #655→#661→#679→#690→#714→#727, peaking at a per-patch formula with density-dependent scaling. Each iteration improved accuracy but remained a compensation for missing obstacle physics. #707 replaces the entire stack with the root-cause fix.

**Speed mining and deceleration (#721 research):** SC2 workers decelerate before reaching targets (patch or TH). At standard mineral distances (~3-4 tiles edge-to-edge), workers never reach max speed — the walk is entirely acceleration + deceleration (triangular velocity profile). Speed mining (issuing move commands to bypass deceleration) gives 10-12% income boost in real SC2. The EM's acceleration/deceleration values (3.5 u/s² at Faster speed) match SC2 data editor values. The deceleration overhead is a natural mechanism, not a bug — a future QuarkMind agent could use speed mining as a legitimate optimisation by issuing two-point commands, just like human players and AI bots do.

**Effective mining/deposit radii (#690 investigation):** SC2 cadence data at 3 saturations shows workers stop at different effective distances from buildings, varying with saturation:

| Saturation | Workers | Mining radius | Deposit distance | Path deviation |
|------------|---------|---------------|-----------------|----------------|
| 8w (1/patch) | 8 | 1.215u | 3.755u | 0.863 |
| 12w (1.5/patch) | 12 | 1.291u | 3.765u | 0.836 |
| 16w (2/patch) | 13 | 1.623u | 3.559u | 0.905 |

Mining radius increases with saturation (contention effect — workers compete for approach angles). Per-patch mining radius varies 0.9–1.6u at 8w and 0.9–2.8u at 16w with no distance correlation — determined by rectangular patch geometry and approach angle, not distance. Fixed effective radius constants do not work across saturations; the wait-time absorption mechanism handles this correctly without artificial constants. Root cause of the walk model gap was the EM's straight-line movement — resolved by obstacle-aware A* pathfinding (#707).

**Wait-then-reroute (#636):** Workers waiting at an occupied patch for longer than `rerouteThresholdLoops` (default 22.0, config: `starcraft.worker.reroute-threshold-loops`) scan for idle patches at the same base. Reroutes only when estimated travel time < estimated remaining wait. Permanent reassignment — worker's `assignedPatch` is updated. 12w diagnostic shows 31 reroute events per 304 ticks (post-#655). At 2 workers/patch (16w optimal saturation), the lockout bypass (#655) means waiting workers start mining immediately when the active miner departs — wait time ≈ 22 loops, equal to but not exceeding the threshold, so no rerouting triggers. This is correct: SC2 workers at 2/patch alternate efficiently without rerouting (#662).

**Patch docking overhead:** After a miner departs a mineral patch, the patch is locked for 22 loops (1 tick) before the next worker can start. Measured from SC2 per-worker cadence data: contested cycles (deposit→pickup) take exactly 1 tick longer than uncontested cycles (88 vs 66 loops).

**Volume-based transfer investigation (#631):** SC2 mining is fixed-duration, all-or-nothing. Community sources document the occupation time as a fixed 1.99 seconds. SC2 observation data (clean 436-tick run) shows uniform cycle durations (mean=3.5, min=3, max=4 ticks) with no shortened cycles near depletion. Workers play the full mining animation regardless of remaining patch minerals, then pick up min(5, remaining) at the end. No rate-based transfer during the animation.

---

## 4. Gas Harvesting

| Constant | Code value | Real SC2 | Source | Confidence | Config property |
|----------|-----------|----------|--------|------------|-----------------|
| Gas harvest duration | 31.7 loops (1.415s) | 32 loops (1.43s) inside geyser | Liquipedia 1.415s; SC2 profiler confirms 32 loops inside (#665) | **Published** — Liquipedia + #665 | `starcraft.worker.gas-harvest-duration-loops` |
| Gas approach docking | 11.5 loops (0.51s) | (entry animation + path deviation) | Dual-run sweep against 3w/6w baselines (#689, was 12.5 in #677) | **Calibrated** — #689 | `starcraft.worker.gas-approach-docking-loops` |
| Gas deposit congestion | 0.0 (removed) | — | SC2 dual-geyser shows no throughput penalty vs 2× single-geyser (#696). Previous 4.0 loops/worker penalized throughput that SC2 doesn't penalize | **Removed** — #696 | `starcraft.worker.gas-deposit-congestion-per-worker` |
| Gas deposit lock | 8.0 loops | — | Deposit lock prevents immediate re-entry | **Default** | `starcraft.worker.gas-deposit-lock-loops` |
| Gas building radius | 1.75u | 1.69u (Assimilator) | SC2 API `Unit.getRadius()` (#665) | **Calibrated** — #665 | `GAS_BUILDING_RADIUS` (compile-time) |
| Gas cargo | 4 | 4 | Liquipedia | **Published** | — |
| Max workers per geyser | 3 | 3 | SC2 mechanics | **Published** | — |
| Gas geyser distance (seeded) | ~7.6u from town hall | — | SC2 dual-run (TorchesAIE_v4) | **Calibrated** — #597 | — |
| Gas geyser distance (SC2 API) | 5.7u and 8.5u | — | SC2 observation API | **Calibrated** — #602 | — |
| Inter-geyser distance (same base) | 4.0u (some maps) | — | Oracle replay analysis (451 pairs, 118 replays) | **Measured** — #671 | — |

**Edge-to-edge gas walks (#665, #672):** Gas workers walk to building edge points using reduced radii: `GAS_BUILDING_RADIUS - ARRIVAL_THRESHOLD = 1.25` and `TOWN_HALL_RADIUS - ARRIVAL_THRESHOLD = 2.25`. This compensates for the 0.5u ARRIVAL_THRESHOLD stopping distance, so the worker physically stops at the actual building edge. SC2 API confirms Assimilator radius=1.69, Nexus radius=2.75. Workers enter the geyser at 1.84–1.87u from gas center (just outside the building radius).

**Gas harvest duration decomposition (#665, #677, #689, #695):** SC2 profiler (per-game-loop tracking, `GasCycleProfilerTest`) confirms the time inside the geyser is exactly 32 loops (1.43s), range=32-32 across 50 cycles. The EM uses `GAS_HARVEST_DURATION_LOOPS=31.7` (Liquipedia's 1.415s) with `GAS_APPROACH_DOCKING_LOOPS=11.5` (recalibrated in #689 from 12.5 in #677). The docking constant absorbs entry animation and path deviation overhead not captured by the straight-line walk model — but is proportionally absorbed by wait time when a worker queues at the entrance (#695) — the longer the wait, the less residual docking on entry. Workers that waited longer than the docking duration enter immediately. Gas walk targets use reduced building radius (`GAS_BUILDING_RADIUS - ARRIVAL_THRESHOLD`) so the ARRIVAL_THRESHOLD stopping distance lands the worker at the actual building edge (#672).

**SC2 gas cycle decomposition (#665, #682, #684 — TorchesAIE_v4, 3w on 1 geyser):**

| Phase | SC2 observed | EM (post-#695) | EM (#682) | EM (#672) |
|-------|-------------|-------------------|-----------|-----------|
| Walk to geyser | 45 loops (2.0 ticks) | 34 loops (1.5 ticks) | 34 loops | ~42 loops (est.) |
| Wait at entry | (implicit in walk) | 8 loops (0.4 ticks) | 2 loops | 0 |
| Inside geyser (harvest+dock) | 32 loops (1.5 ticks) | 45 loops (2.1 ticks) | 32 loops | 32+15.8 loops |
| Walk to base | 45 loops (2.0 ticks) | 36 loops (1.6 ticks) | 36 loops | ~42 loops (est.) |
| Deposit | (included in walk) | 12 loops (0.5 ticks) | 12 loops | 12 loops |
| **Full cycle** | **122 loops (5.5 ticks)** | **136 loops (6.2 ticks)** | 116 loops | ~122 loops |

**EM (#689, #695, #696) cycle note:** `GAS_APPROACH_DOCKING_LOOPS=11.5` (#689) models entry animation for the first worker or when no queue exists. Docking is proportionally absorbed by wait time (#695): `effective_docking = max(0, 11.5 - waited_loops)`. In the rotation-limited regime (3w on 1 geyser at close distances), workers wait long enough to absorb the full docking — effective inside time drops to 31.7 loops (harvest only), matching Liquipedia's 163 gas/min (EM 165.6 = +1.6% at 5u). At larger distances (5.7u+), partial absorption applies. Gas deposit congestion model removed (#696) — SC2 dual-geyser shows better efficiency than 2× single-geyser (mean 1140 vs 2×552=1104), so the per-worker penalty was fighting reality. Remaining deposit lock (8 loops) under investigation (#698). Dual-run accuracy: 3w vespene −1.4%, 6w vespene −2.8% vs 5-run mean.

**Gas walk ARRIVAL_THRESHOLD compensation (#672):** Gas walk targets use reduced building radius (`GAS_BUILDING_RADIUS - ARRIVAL_THRESHOLD` and `TOWN_HALL_RADIUS - ARRIVAL_THRESHOLD`) so the ARRIVAL_THRESHOLD stopping distance lands the worker at the actual building edge.

**#682 dual-geyser gas surplus — serial geyser access:** The root cause of the 6w surplus was the EM allowing all 3 workers inside the geyser simultaneously (`MAX_WORKERS=3` in `canEnter`). Liquipedia confirms: "Only one worker can be inside the building collecting at any one time, but multiple workers can be queued up to mine from it." Fix: `GasSlot.MAX_INSIDE=1` serialises geyser access — workers queue outside and enter one at a time. Combined with per-loop gas worker processing (matching SC2's unified per-loop model), gas worker shuffle, and per-base deposit lock (8 loops).

**#689→#696 dual-geyser asymmetry — congestion model removed:** The original #689 congestion model (4.0 loops per extra worker) was designed to explain a dual-geyser throughput penalty. But SC2 5-run data (#688) shows dual-geyser is *more* efficient than 2× single-geyser (mean 1140 vs 2×552=1104 = +3.3% bonus). The congestion model was fighting reality — removed in #696, improving 6w gas from −10.9% to −2.8% vs mean. The remaining gap is likely from the per-base deposit lock (8 loops, #698).

**Liquipedia reconciliation (https://liquipedia.net/starcraft2/Resources):**

**Empirical-first principle:** Where SC2 recordings or oracle replay data conflict with Liquipedia, we follow empirical. Liquipedia values are reference points for sanity-checking, not calibration targets. Liquipedia rates assume idealised conditions (close distances, no contention) that don't match real SC2 games — e.g., mineral and gas rates diverge 5-30%+ at standard map distances. Record both values and the delta; never silently replace empirical with Liquipedia.

**Speed conversion:** Liquipedia times are real seconds at Faster speed (22.4 game loops/s). Convert: `loops = seconds * 22.4`. All EM constants are in game loops.

#### Gas — Liquipedia vs EM

| Parameter | Liquipedia | Loops | EM value | Delta | Rationale |
|-----------|-----------|-------|----------|-------|-----------|
| Inside geyser time | 1.415s | 31.7 | 31.7 (`GAS_HARVEST_DURATION_LOOPS`) | 0% | Direct match. SC2 profiler confirms 32 loops inside (#665) |
| Gas cargo | 4 | — | 4 (`GAS_CARGO`) | 0% | Direct match |
| Serial access | 1 at a time | — | 1 (`GasSlot.MAX_INSIDE`) | 0% | Direct match (#682) |
| Max gas rate | 169.61/min | 31.7/delivery | — | — | Theoretical (zero walk time) |
| 1w gas rate | 61/min | 88.1/delivery | 58.0 at 5u | -4.9% | Walk-limited; matches at close distance (#685) |
| 2w gas rate | 123/min | 43.7/delivery | 116.7 at 5u | -5.1% | Walk-limited; matches at close distance (#685) |
| 3w gas rate | 163/min | 33.0/delivery | 165.6 at 5u | +1.6% | Rotation-limited: workers queue at entrance, docking overlaps with wait time (#695). Matches Liquipedia |
| 2x3w gas rate | 325/min | 16.5/delivery | 330.5 at 5u | +1.7% | Follows from 3w fix. 0% throughput loss at 5u (#695) |

#### Minerals — Liquipedia vs EM

| Parameter | Liquipedia | Loops | EM value | Delta | Rationale |
|-----------|-----------|-------|----------|-------|-----------|
| Occupy time | 1.99s | 44.6 | mining(22)+approach(20-25)=42-47 | ~0% | Liquipedia measures total time at patch; EM decomposes into mining animation + approach docking |
| Pause before return | 0.3571s | 8.0 | deposit=12 loops | +50% | Different measurement points: Liquipedia=patch pause, EM=TH deposit. Deposit calibrated from SC2 8w cadence (#661) |
| Mineral cargo | 5 (7 rich) | — | 5 (`MINERAL_CARGO`) | 0% | Direct match |
| Max rate / 8 fields | 1206/min | — | — | — | Theoretical (zero walk time) |
| 16w rate | ~925/min | — | ~912 at tick 304 | -1.4% | Dual-run calibration (#661) |
| 24w rate | ~1140/min | — | ~1108 at tick 304 | -2.8% | Dual-run calibration (#661) |

#### MULE — Liquipedia vs EM (trip-based)

| Parameter | Liquipedia | EM (trip-based) | Delta | Source |
|-----------|-----------|-----------------|-------|--------|
| Cargo per trip | 25 | 25 (`MULE_MINERAL_CARGO`) | 0% | **Empirically verified** (#726): 25 gives +1.4% vs SC2, 30 gives +12.3%. 13 trips × 25 = 325 min/lifetime |
| Mining overhead | — | 20 loops (`MULE_MINING_OVERHEAD_LOOPS`) | — | Calibrated from SC2 dual-run MULE isolation baseline (#717) |
| Total yield | 225 minerals | ~263 (3615-3352 worker baseline at tick 305) | **+17%** | EM trip-based at standard distances; Liquipedia assumes ideal short distance |
| Isolation delta | — | +1.4% (EM=3615 vs SC2=3565) | — | MULE isolation playbook (#717) |
| Lifetime | 64s | 1452 loops (64.8s) | +1.3% | SC2 dual-run calibration (#613) |
| Mining model | trip-based | trip-based (walk→mine→deposit→repeat) | — | Replaced flat rate (#717); uses WorkerSimulation movement physics |

**Gas rate MAPE improvement (#675):** vespeneCollectionRate MAPE at 5-min dropped from 29.9% to 15.3% (aggregate, 236 player runs). Per-race: Protoss 16.1→15.2%, Terran 24.0→13.2%, Zerg 31.8→25.4%. Zerg residual is variance-driven (per-replay cycle differences), not systematic bias.

**Seeded geyser positions (all races):** (15,11) and (11,15) relative to town hall at (8,8) = 7.6u symmetric. **SC2 API positions (#602):** real geysers on TorchesAIE_v4 are asymmetric — 5.7u and 8.5u from Nexus center (avg 7.1u). All 10 calibration baselines now include 32 geyser positions (full map); `DualRunCalibrationTest` filters to the 2 within 12u of the town hall.

**Inter-geyser distances (#671):** Across 118 oracle replays (451 geyser pairs), 8.9% of pairs are less than 5.0u apart. The dominant cluster is at exactly 4.0u — two geysers on the same axis separated by 4 tiles. Min=0.0 (rebuilt geysers), P5=4.0, median=14.9, max=164.8. The old position-based gas slot dedup (`distance < 5.0`) rejected the second geyser at these bases, causing 14% gas building under-count at 5-min. Fixed by tag-based dedup in #671.

---

## 5. Mineral Patch Geometry

| Constant | Value | Source | Confidence |
|----------|-------|--------|------------|
| Close patch distance | avg 6.6 units | Oracle replay mineral positions | **Calibrated** — #567 |
| Far patch distance | avg 7.6 units | Oracle replay mineral positions | **Calibrated** — #567 |
| Default close patch distance | 6.7 units | SC2 API positions (TorchesAIE_v4 mean of 4 close patches) | **Calibrated** — #651 | `MineralPatch.CLOSE_PATCH_DISTANCE` |
| Default far patch distance | 7.5 units | SC2 API positions (TorchesAIE_v4 mean of 4 far patches) | **Calibrated** — #651 | `MineralPatch.FAR_PATCH_DISTANCE` |
| Patch distance (config) | 7.0 units | SC2 dual-run calibration sweep — used by explicit `setMineralPatchResources()` | **Calibrated** — #599 | `starcraft.worker.patch-distance` |
| SC2 API distances (TorchesAIE_v4) | 3.0–5.9u (center-to-center) | SC2 observation API | **Calibrated** — #605 |
| Close patch minerals | 1500 | SC2 standard | **Published** |
| Far patch minerals | 750 | SC2 standard | **Published** |

**SC2 API patch positions (#605):** All 10 calibration baselines now include exact mineral patch positions from the SC2 observation API (154 patches per map). The DualRunCalibrationTest injects these via `setMineralPatchResources()` before `reset()`, so the EM uses real distances instead of the default 7.0u uniform distance. TorchesAIE_v4 starting base patches are 3.0–5.9u from Nexus center (close=4.0u avg, far=4.6u avg) — much shorter than the oracle replay averages because the API returns center-to-center distances while replay distances include model offsets.

---

## 6. Mining Efficiency Factors

**Factor removed (#623).** Edge-to-edge walk distances, decomposed mining phases (mine=22, deposit=12, approach=per-patch ~22–27), transition loop cost (#661), and worker stagger (6.1 loops) produce correct mineral totals without compensation. Collision speed factor disabled (1.0) since #661. Collision lateral displacement scaled to 25% at ≤2 workers/patch (#656) via `LOW_SATURATION_DODGE_SCALE`. All races use factor 1.0.

Previous per-race factors (Protoss=0.997, Terran/Zerg=0.984) were from oracle replay MAPE sweep, which conflated worker allocation differences with physics. The dual-run isolates physics and shows no race-specific difference.

### SC2 dual-run calibration results (TorchesAIE_v4, post-#690)

| Baseline | SC2 minerals | EM minerals | SC2 vespene | EM vespene | Mineral Δ | Gas Δ |
|----------|-------------|------------|-------------|------------|-----------|-------|
| 8w Protoss (tick 304) | 2330 | 2340 | — | — | **+0.4%** | — |
| 12w Protoss (tick 304) | 3460 | 3450 | — | — | **−0.3%** | — |
| 16w saturation (tick 304) | 4270 | 4150 | — | — | **−2.8%** | — |
| 24w saturation (tick 304) | 3955 | 3985 | — | — | **+0.8%** | — |
| 3w gas (tick 304) | 3885 | 3910 | 552 | 568 | +0.6% | **+2.9%** |
| 6w gas (tick 304, run-2) | 3260 | 3430 | 1204 | 1108 | +5.2% | **−8.0%** |
| 6w gas (5-run mean) | — | — | 1140 | 1108 | — | **−2.8%** |
| 12w Zerg (tick 304) | 3415 | 3425 | — | — | **+0.3%** | — |
| 12w Terran (tick 304) | 3460 | 3450 | — | — | **−0.3%** | — |
| 1w speed (tick 121) | 1370 | 1355 | — | — | **−1.1%** | — |
| MULE isolation (tick 305) | 3565 | 3615 | — | — | **+1.4%** | — |

**MULE trip-based mining (#717).** Replaced flat-rate MULE income (277.8 min/min) with trip-based mining: MULEs walk to nearest mineral patch, harvest 25 minerals per trip (`MULE_MINERAL_CARGO`), walk back to town hall, deposit, repeat until expiry. Uses WorkerSimulation movement physics but doesn't claim patches or participate in collision resolution. Mining overhead calibrated at 20 loops (`MULE_MINING_OVERHEAD_LOOPS`) to match SC2 baseline. Delta reduced from +3.4% to +1.4%.

**6w gas multi-run variance (#688, #696):** 5 SC2 runs produce bimodal vespene at tick 304: {1044, 1204, 1204, 1044, 1204}. Mean=1140, σ=78.4, CV=6.88%. Baseline updated from outlier (1012) to run-2 (1204, dominant mode) in #696. SC2's random worker-to-geyser assignment creates two distinct outcomes per game — the EM tracks the lower mode (1044), suggesting its worker-to-geyser routing matches the less-efficient SC2 pattern. Baselines in `calibration/multi-run/cal-gas-dual-6w-run-{1..5}.json`.

**#661 base cycle speed fix** — three changes:
1. **Transition loop cost**: mining→returning and depositing→mining transitions in `stepOneMineralLoop` now consume 1 game loop each (previously zero-cost via `while(!loopConsumed)` chaining). Matches SC2's per-loop transition processing.
2. **Approach docking recalibrated**: 11.5→24.5 loops. Measured from 8w SC2 cadence data: outbound half-cycle is +12 loops slower than EM. The increased approach absorbs the positioning overhead that straight-line walks don't capture.
3. **Deposit duration recalibrated**: 7→12 loops. Measured from 8w cadence: return half-cycle gap = +2.6 loops.
4. **Collision speed factor disabled**: 0.96→1.0. The transition loop cost provides correct cycle timing across all saturation levels; the collision-based compensation that previously masked the fast base cycle is no longer needed.

Previously (pre-#655): 12w +0.6%, 16w −20.2%, 24w −13.4%. The compound collision model penalised 16w workers with 5+ overlapping neighbours per loop, reducing throughput far beyond SC2's actual contention.

**Per-phase cycle decomposition (#719):** `WorkerPhaseListener` instrumentation at 8w (zero contention) shows two worker clusters matching SC2 cadence data:
- Close patches (probes 8–11): EM 110–111 loops (5.04 ticks) vs SC2 ~4.8 ticks — EM is **+5% per cycle** (too slow)
- Far patches (probes 4–7): EM 119–120 loops (5.44 ticks) vs SC2 ~5.6 ticks — EM is **−2.9% per cycle** (too fast)
- Mining phase is constant (51–52 loops = approach animation 20 + approach docking 4–5 + mining 22 + path deviation 5–6)
- Walk and deposit phases account for the remaining gap: walk→patch varies 28–34 loops by distance, walk→base 18–23 loops, deposit constant at 12 loops
- The per-patch approach docking formula partially normalises cycle times across distances but over-corrects for close patches, making them slightly too slow while leaving far patches slightly too fast

### Saturation curve (SC2 dual-run, Protoss, TorchesAIE_v4)

| Workers | Workers/patch | Rate/worker/min | Efficiency |
|---------|---------------|----------------|------------|
| 1-16 | ≤2.0 | 57.0 | 100% |
| 20 | 2.5 | 50.9 | 89.3% |
| 23 | 2.9 | 47.7 | 83.7% |
| 24 | 3.0 | 46.8 | 82.1% |
| 30 | 3.8 | 37.5 | 65.7% |

**Saturation model calibrated** — contention fix (assignment-based spreading + wake-up pass), patch docking overhead (22 loops), transition loop cost (#661), approach animation (20 loops, #707), deposit duration (12 loops, #661), lockout bypass for waiting workers (#655), wait-then-reroute (#636), and obstacle-aware A* pathfinding (#707, replaced density-dependent path deviation). Collision speed factor disabled (1.0) since #661.

**Low-saturation boundary validation (#721→#727):** SC2 dual-run at 4w/6w/10w exposed a data error: the #721 finding had SC2/EM columns swapped. The EM actually *over*-produces at low saturation (EM walks straight lines, SC2 curves). The flat bonus was correct in direction (adding overhead) but wrong in shape (uniform across all densities ≤1 w/p). Fixed in #727 with inverse-density scaling. Post-#727 deltas: 4w +3.6% (was +10.7%), 6w +2.7% (was +5.3%), 8w −0.2%, 10w +1.8%, 12w −0.3%.

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
| MULE cargo | 25 minerals/trip | Liquipedia consensus | **Published** — #717 |
| MULE mining overhead | 20 loops | Calibrated from SC2 dual-run MULE isolation | **Calibrated** — #717 |
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

All building types mapped to rectangular tile footprints via `SC2Data.buildingFootprint(BuildingType)` (#736), returning an immutable `SC2Data.Footprint(width, height)` record (#740) with `halfWidth()`/`halfHeight()` helpers. `enforceWall()` uses `insideFootprint()` — axis-aligned rectangle containment check, tile-inclusive boundaries. The deprecated `buildingRadius()` delegates to `buildingFootprint()` and returns `max(width, height) / 2.0f` for backward compatibility.

| Footprint | Half-extents | Examples |
|-----------|-------------|----------|
| 5×5 (Large) | 2.5 × 2.5 | Nexus, CC, Hatchery |
| 4×4 | 2.0 × 2.0 | Ultralisk Cavern |
| 3×3 (Medium) | 1.5 × 1.5 | Gateway, Barracks, Spawning Pool, Evolution Chamber, Assimilator, Refinery, Extractor |
| 2×2 (Small) | 1.0 × 1.0 | Pylon, Supply Depot, Photon Cannon, Spine/Spore Crawler, Nydus Canal |
| 1×1 (Tiny) | 0.5 × 0.5 | Creep Tumor |

Confidence: **Published** (SC2 data editor). Evolution Chamber corrected 2×2→3×3 (#738). Gas buildings (Assimilator/Refinery/Extractor/Assimilator_Rich) corrected 2×2→3×3 (#738) — matches 3×3 geyser footprint (#742).

**Rectangular vs circular collision (#736):** The rectangular footprint differs from the old circular radius at diagonal positions — the rectangle extends to sqrt(hw²+hh²) at corners while the circle boundary is constant at max(hw,hh). For a 5×5 Nexus at (8,8): a unit at (10,10) is inside the rectangle (10 ≤ 10.5) but outside the circle (distance=2.83 > 2.5). This is correct SC2 behaviour — buildings occupy rectangular tiles, not circles.

**Mineral patch obstacles:** Still use circular obstacles (radius 0.3, `MINERAL_PATCH_OBSTACLE_RADIUS`) on the pathing grid for A* pathfinding. Converting to 2×1 rectangular footprint was tested (#733) but caused +4.1% MULE regression — the 2×1 rectangle blocks twice as many tiles as the calibrated 0.3-radius circle. Conversion deferred to #735 pending empirical calibration of the correct pathfinding obstacle size.

---

## 14. Insights and Derived Physics

The constants above combine with the simulation mechanics to produce emergent behaviour. These derived values explain *why* the economy numbers are what they are.

### Mining cycle breakdown

At a close mineral patch (6.6 units from town hall), edge-to-edge walk:

| Phase | Loops | Seconds | Notes |
|-------|-------|---------|-------|
| Walk to patch (edge-to-edge) | 33 | 1.47 | Trapezoidal profile, collision footprint boundary |
| Approach animation | 20 | 0.89 | Fixed docking/positioning overhead at the patch (#679, #707) |
| Mine | 22 | 0.98 | SC2 per-worker observation: position stable 1 tick (#631) |
| Walk home (edge-to-edge) | 33 | 1.47 | Same distance, same profile |
| Deposit | 12 | 0.54 | 8w SC2 cadence: return half-cycle gap (#661) |
| Transition costs | 2 | 0.09 | 1 loop per phase transition × 2 transitions (#661) |
| **Total cycle** | **~122** | **~5.45** | 5 minerals per trip |

Per-patch path deviation was previously modeled as a constant (#656, #679) but removed in #707 — it now emerges from obstacle-aware A* paths (workers route around mineral patches and buildings). The `APPROACH_DOCKING_LOOPS` config constant was removed in #739.

**Per-worker income:** 5 minerals × (22.4 loops/sec × 60 sec / 122 loops) = **55.1 minerals/min** on a close patch. SC2 8w measured: 57.0 deliveries/worker in 304 ticks = ~53.7 min/worker.

At a far mineral patch (7.6 units): ~40 loops per leg, 129-loop cycle, **52.1 minerals/min** per worker. At uniform 7.0 units (unit tests): ~37 loops per leg, 126-loop cycle, **53.3 minerals/min** per worker.

### Saturation curve mechanics

With `MAX_MINERS=1`, the approach+mining duration (20+22=42 loops) controls patch occupancy. A patch is occupied 42/122 = **~34%** of each cycle. Two workers per patch alternate efficiently — one mines while the other walks. The approach+mine phase (42 loops) approximately fills the time the other worker spends walking+depositing (~45 loops), producing near-zero contention at 2 workers/patch. SC2 8w and 12w per-worker rates are nearly identical (57.0 vs 56.8 deliveries/worker), confirming near-zero contention at ≤2 workers/patch.

16w dual-run (#661→#690): EM=4150 vs SC2=4270 (−2.8%). Collision speed factor disabled (1.0) since #661 — transition loop cost provides correct timing without collision-based compensation.

### Gas cycle breakdown

Three workers per geyser, serial access (`GasSlot.MAX_INSIDE=1`, #682). Workers queue outside and enter one at a time, matching Liquipedia: "Only one worker can be inside the building collecting at any one time." Measured by `GasWorkerCycleDiagnosticTest` (#684).

| Phase | Loops | Ticks | Notes |
|-------|-------|-------|-------|
| Walk to geyser | 34 | 1.5 | Edge-to-edge, straight-line |
| Wait at entry | 8 | 0.4 | Serial access queue (avg, 3w) |
| Harvest + docking | 43.2 | 2.0 | GAS_HARVEST=31.7 + APPROACH_DOCKING=11.5 (#689) |
| Walk to base | 36 | 1.6 | Edge-to-edge, straight-line |
| Deposit | 12 | 0.5 | DEPOSIT_DURATION=12 |
| **Total cycle** | **~130** | **~5.9** | 4 gas per trip |

**Rotation-limited cycle:** At 3 workers, the theoretical minimum cycle is 3×inside = 3×43.2 = 129.6 loops. Actual ~130 loops is 100.3% of theoretical — the cycle is fully rotation-limited. Walk time and deposit are shorter than inside time, so they fit within the gaps.

**Dual-run accuracy (post-#689):** 3w vespene -1.4% (EM=544 vs SC2=552), 6w vespene +0.4% (EM=1016 vs SC2=1012 single-run). Against 5-run mean (1140, #688): -10.9%. SC2 6w gas is bimodal: {1044, 1204}, CV=6.88% (#688). Mineral accuracy: 3w -2.1%, 6w -2.8%.

### Tick-boundary quantisation

The simulation's 22-loop tick creates a time resolution of 0.982 seconds. Before sub-tick chaining, state transitions (arrive→mine, mine→return, return→deposit) wasted the remaining loops in the tick — up to 22 loops lost per transition, 3 transitions per cycle, potentially 15% of cycle time evaporated at tick boundaries.

Sub-tick chaining (implemented in #578 for movement, #583 for mining, #584 for gas) tracks `loopsRemaining` within each tick and chains transitions immediately. This eliminated the dead time but also exposed physics constant errors that the quantisation had been masking — calibration sweeps that previously showed wide plateaus of identical output now show distinct values at each constant.

**Production completions** also use loop precision (#601). `PendingCompletion` tracks `completesAtLoop` and `fireCompletions` compares against `gameFrame * LOOPS_PER_TICK`. Training/building/research completions fire in the first tick whose loop range includes the completion loop — no truncation. Probe training cadence: [13, 12, 13, 12, 12] (was [12, 12, 13, 12, 12] with tick-level integer division).

**General principle:** In any tick-based simulation, if a calibration sweep shows plateaus where different constant values produce identical output, the time resolution is too coarse to express the constant. Fix the resolution before calibrating the constant.

### Cumulative drift

Rate field errors compound in balance fields. mineralsCollectionRate MAPE at 7-min is 38.6% (2026-11-22 baseline). Each tick's income error accumulates in mineralsCurrent — the mineral *balance* diverges by roughly the rate error × elapsed time. This is why mineralsCurrent MAPE (84.9%) is far higher than the rate MAPE (38.6%). Drift amplification ratio is ~2.2× at 7-min.

Fixing rate accuracy is the primary lever for reducing balance MAPE. Gap decomposition (#744) is quantifying the per-component contribution — rate algorithm (#676), map geometry (#745), worker timing (#746). The balance fields will converge automatically as rate accuracy improves (#747 will validate).

### Per-race efficiency factors — removed

Factor removed in #623. With decomposed mining phases (mine=22, approach=20.0, deposit=12), edge-to-edge walk distances, transition loop cost (#661), obstacle-aware A* paths (#707), and wait-time absorption (#690), no compensation factor is needed. SC2 dual-run shows 12w EM within −0.3% of SC2 at tick 304.

### The overhead gap — decomposed (#620→#631)

The previous lumped mining constant absorbed three distinct phases. These are now separately modeled from SC2 per-worker observation data (1366 cargo transitions):

| Component | Loops | Source | Status |
|-----------|-------|--------|--------|
| Mining animation | 22 | SC2 per-worker observation: position stable 1 tick | **Modeled** — `mining-duration-loops=22` |
| Deposit at town hall | 12 | 8w SC2 cadence: return half-cycle gap (#661) | **Modeled** — `deposit-duration-loops=12` |
| Approach animation at patch | 20.0 | Base animation overhead (#679, #707) | **Modeled** — `approach-animation-loops=20.0` |
| Transition costs | 2 | 1 loop per phase transition × 2 (#661) | **Modeled** — `loopConsumed=true` |
| **Total per-cycle overhead** | **56.0** | | |

Post-#727 (density-dependent path deviation): 4w +3.6%, 6w +2.7%, 8w −0.2%, 10w +1.8%, 12w −0.3%. Historical: 8w was +4.3% post-#661, reduced to +0.4% by #714 bonus, then −0.2% by #727 inverse-density scaling.

**Patch affinity (#620):** Workers return to the same patch each trip (SC2 behaviour). Routing is not the cadence bottleneck — the root cause is per-trip variance from collision timing and walk distance variance that straight-line movement cannot reproduce.

### Per-patch approach docking — history

**#636→#679:** Per-patch approach docking evolved through `APPROACH_DISTANCE_SCALE` (#653, removed), per-patch formula with `PATH_DEVIATION_SCALE=0.4` and `PATH_DEVIATION_BASE=4.5` (#679), and density-dependent scaling (#727). **All removed in #707** — obstacle-aware A* pathfinding makes path deviation emerge from simulation, not constants. Current approach docking is `APPROACH_ANIMATION_LOOPS=20.0` only, uniform for all patches. See §3 for details.

**Delivery quantisation (finding):** Uniform phase constants produce discrete delivery counts because workers synchronise. Worker stagger (6.1 loops) breaks the phase relationship to hit the SC2 target.

### Rate computation algorithm

SC2 uses **exponential moving average (EMA)**, not a simple windowed average. Confirmed by RateAlgorithmAnalysisTest (#593), which computed income from delta(totalMined) across consecutive PlayerStatsEvents and fitted simple window vs EMA (11 alpha values) against SC2's reported rates.

**Quantisation discovery (#676):** SC2 mineral rates are quantised at exactly 28 minerals/min. Across 118 oracle replays, every reported `mineralsCollectionRate` (after the first interval) satisfies `rate ≡ 27 (mod 28)`, i.e. `rate = 28N - 1`. This implies SC2 counts discrete deliveries in a 240-loop window: `deliveries × MINERAL_CARGO / 240 × LOOPS_PER_MINUTE = deliveries × 28`. The -1 offset is likely integer truncation. However, implementing a 240-loop sliding window in the EM's EconomyTracker increased gas rate MAPE (18.8% vs 15.3% EMA) because the EM's delivery timing doesn't match SC2's closely enough — the EMA's smoothing compensates for per-delivery timing errors. The window approach becomes viable only after tighter physics alignment.

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

Stagger=6.1 with walk delay=40 produced 3460 minerals at tick 304 (**0.0%** vs SC2's 3460) pre-shuffle with the previous collision/approach constants. Re-calibrated from 6.0 (#609) via fine sweep. Post-#661 (approach=24.5, deposit=12, collision=1.0, transition cost): 12w EM=3365 vs SC2=3460 (−2.7%). Post-#690 (wait-time absorption): 12w EM=3450 vs SC2=3460 (−0.3%). Walk delay (#619) shifted first deliveries from tick 3-4 to tick 5-6.

### Worker management constants

| Constant | Value | Effect |
|----------|-------|--------|
| `MAX_TRANSFER_PER_EXPANSION` | 4 | Workers auto-transferred when a new base completes |
| `MIN_MINERAL_WORKERS_BEFORE_GAS` | 4 | Gas workers won't be assigned if mineral count drops below this — lowered from 8 to 4 (#675); players assign gas at expansions with 7+ workers |
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

**Gas rebalancing on mineral sync (#675):** `syncMineralWorkersFromGt()` redistributes mineral workers between bases each tick, but previously never checked if expansion gas slots needed workers. Added `rebalanceGasWorkers()` call at the end of `syncMineralWorkersFromGt()` so that undersaturated gas slots at any base are filled whenever the mineral worker count allows (respects `MIN_MINERAL_WORKERS_BEFORE_GAS=4` guard).

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

Real SC2 close patches (4-6 units from town hall) yield ~40% more per-worker income than far patches (7-9 units) due to shorter round trips. The model uses per-map ground truth positions for replay mode (correctly variable distances), but unit tests use variable distances (close=6.7u, far=7.5u) calibrated from SC2 API positions (#651). Oracle replay baselines are not affected.

### Building morph unavailability

Building morphs (Hatchery→Lair→Hive, CC→OC/PF) are tracked via tracker events and the building type is updated immediately. The morph period during which the building is functionally unavailable (can't produce units, reduced or absent capabilities) is not simulated. Impact: minor for economy — these morphs are infrequent and short relative to game duration.

### Worker pathing and terrain — OBSTACLE-AWARE (#707, #734)

Workers use A* pathfinding around mineral patches registered as circular obstacles (radius 0.3, `MINERAL_PATCH_OBSTACLE_RADIUS`) in `TerrainGrid` (#707). The dynamic obstacle layer uses reference-counted `int[][]` cells. Workers compute waypoint paths via `AStarPathfinder` once per route and follow waypoints using existing `moveOneLoop()` acceleration physics. Outbound paths naturally route around the mineral line (longer walks), return paths are approximately direct (shorter walks) — matching SC2's observed path asymmetry (#690). The per-patch approach docking formula (#656) and density-dependent scaling (#727) have been replaced by this physical obstacle routing. Path deviation emerges from simulation, not constants.

**Default terrain (#734):** `EmulatedGame()` constructor wires `TerrainGrid.emulatedMap()` by default — all tests get obstacle-aware worker pathfinding without explicit setup. `setTerrainGrid()` overrides the default for real SC2 maps.

**Fog decoupling (#734):** Fog-of-war visibility filtering is controlled by `fogEnabled` (default false), independent of terrain. Previously gated on `terrainGrid != null`, which coupled pathfinding terrain with snapshot visibility filtering. Production profiles (`EmulatedEngine`, `EmulatedSC2Server`) enable fog explicitly.

**Building collision (#736, #743):** `enforceWall()` uses rectangular tile footprints via `insideFootprint()` — see §13. Army units cannot enter building footprints (complete or incomplete). Workers are not affected by `enforceWall()` — they use the pathing grid obstacle layer for A* routing. `enforceWall` skips buildings that are registered as terrain obstacles (covered by the `isWalkable` check), reducing iteration from O(units × all_buildings) to O(units × initial_buildings) per tick (#743). Initial town hall buildings are NOT registered as obstacles because worker A* paths route through the base area.

**Army building obstacles (#731):** Buildings are registered as rectangular obstacles on `TerrainGrid` via `markRectangularObstacle()` from the moment construction starts (incomplete buildings block pathing, matching SC2). `AStarPathfinder` routes army units around buildings. `enforceWall()` remains as a safety net for edge cases. All army paths are invalidated when a building is placed or destroyed — `MovementStrategy.invalidateAllPaths()` clears all cached waypoints. Building obstacles are cleared on `reset()` via `clearAllObstacles()`.

**Resource obstacles (#732, #742):** Vespene geysers are registered as 3×3 rectangular obstacles (half-extents 1.5) on `TerrainGrid` via `setGeyserResources()`. The 3×3 footprint matches gas buildings (Assimilator/Refinery/Extractor) confirmed as 3×3 in the SC2 data editor (#738). Mineral patches are already registered as circular obstacles (radius 0.3) by `WorkerSimulation` on the shared grid — army A* routes around both. Geyser obstacles are cleared and re-registered when `setGeyserResources()` is called, and cleared on `reset()` via `clearAllObstacles()`.

### Larva spawn calibration

Larva spawn interval (246 loops) and inject larva count (4 per inject) are estimated from community sources. `AutoSpawnCalibrationTest` exists but hasn't been run against sufficient oracle data to calibrate. Impact: affects Zerg unit production timing, not mineral/gas income directly.

### Chrono Boost interaction model

Whether multiple Chrono Boosts on the same building stack additively, refresh the timer, or are rejected. Currently modeled as a 50% speed multiplier for 448 loops (single application). Real SC2: a second Chrono Boost refreshes the timer (resets to 20 seconds). Impact: affects Protoss production timing for key units (early Stalkers, Immortals).

### Geyser entrance queuing

GasSlot supports configurable entrance queuing via `ENTRY_LOCK_DURATION` (default 0 = concurrent, matching legacy behavior). When > 0, workers queue at the geyser entrance — only one enters per lock period. However, a 2D sweep (lock 0-20 × harvest 14-28) against 3w and 6w baselines (#639) showed **entrance queuing has no practical effect** — workers never arrive at the geyser simultaneously because walk cycle stagger keeps them apart. The 3w/6w gas gap is not from entrance contention but from per-geyser walk-distance rate interaction: no single gas harvest value satisfies both 3w (optimal at 16 loops) and 6w (optimal at 26 loops). Current harvest=26 is the best compromise. Sweep-test gas-only gaps: 3w -6.5%, 6w -0.4%. Current dual-run gaps (with full playbook economy, post-#689): 3w vespene -1.4%, 6w vespene +0.4%. Impact: vespeneCollectionRate MAPE at 25.1% is a structural floor (#639 decomposition) — from per-worker gas harvest physics, integer worker count rounding, and PlayerStatsEvent cadence mismatch.

### Rally point walk time

Newly produced workers spawn at the town hall position and walk to an assigned mineral patch. Real SC2: workers auto-rally to a mineral patch if the rally is set there, beginning mining faster. Impact: small — the rally walk is a few seconds per worker, amortised over the worker's full game lifetime.

---

## 16. Economy Baseline

Standalone EmulatedGame accuracy (118 oracle replays, all GT syncs disabled, EMA rate algorithm). Baseline date: 2026-11-22.

### Rate MAPE by checkpoint

| Checkpoint | mineralsCollectionRate | vespeneCollectionRate |
|------------|----------------------|---------------------|
| 1-min | 35.6% | 90.8% (few gas workers) |
| 3-min | 24.8% | 29.5% |
| 5-min | 29.3% | 22.2% |
| 7-min | **38.6%** | **20.5%** |

Mineral rate MAPE increased vs prior baselines — under investigation (#744). Gas rate MAPE improved significantly from recalibrated harvest decomposition (#689, #695).

### Full economy at 7-min

| Field | MAPE | Structural floor | Status |
|-------|------|-----------------|--------|
| mineralsCollectionRate | 38.6% | ~10-12% | Gap: rate algorithm (#676), map geometry (#745), worker timing (#746) |
| vespeneCollectionRate | 20.5% | ~12-15% | Approaching floor — rate algorithm (#676) is remaining lever |
| mineralsCurrent | 84.9% | ~40-50% | Cumulative drift from rate error; reduces proportionally with rate fix |
| vespeneCurrent | 61.1% | ~35-45% | Cumulative drift; gas rate closer to floor so less headroom |
| foodMade | 0.9% | ~0.5-1% | At floor |
| foodUsed | 3.7% | ~2-3% | Near floor |
| workersActiveCount | 2.5% | ~2-3% | At floor |
| mineralsUsedCurrentArmy | 4.3% | ~5-8% | At floor — snapshot-aligned spending (#579) |
| mineralsUsedCurrentEconomy | 0.9% | ~0.5-1% | At floor |
| vespeneUsedCurrentArmy | 3.9% | ~5-8% | At floor |
| vespeneUsedCurrentTechnology | 39.7% | ~10-15% | Structural — InProgress/Current timing boundary |

### Accuracy plan — closing the gap

Six issues scheduled in dependency order under epic #607:
1. **#744** (M/Med) — Gap decomposition: identify which components drive the 38.6% mineral rate MAPE
2. **#745** (S/Med) — Per-replay mineral patch geometry: quantify map distance contribution
3. **#746** (S/Med) — Worker startup/transfer timing: quantify allocation delay contribution
4. **#676** (M/High) — Rate algorithm reverse-engineering: break the ~16% EMA floor
5. **#747** (S/Low) — Balance validation: verify balance MAPE drops proportionally after rate fixes
6. **#666** (L/High) — Forward simulation mode: EM-driven income without GT sync

Target after all land: mineralsCollectionRate ≤12%, vespeneCollectionRate ≤15%, mineralsCurrent ≤50%.

### Rate algorithm

EMA (mineral α=0.25, vespene α=0.35) approximates SC2's rate computation. Confirmed by RateAlgorithmAnalysisTest (#593) as best-fit among tested algorithms, but still carries ~16% MAPE floor — SC2's internal rate signal differs from our EMA-from-delta(totalMined) approach (#676). Rate MAPE at 7-min: minerals 38.6%, vespene 20.5%.

### Gas harvest recalibration (#606, #615, #639, #689, #695)

Gas harvest duration: 31.7 loops / 1.415s (Liquipedia; SC2 profiler confirms 32 loops inside geyser, #665). Gas approach docking: 11.5 loops (recalibrated #689, proportionally absorbed by wait time #695). vespeneCollectionRate MAPE improved from 35.0% to 20.5% at 7-min through harvest decomposition and docking absorption model.

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
| GasWorkerCycleDiagnosticTest | Per-phase gas cycle timing: walk, wait, inside, return, deposit | `-Pdiagnostic` |

---

## 19. Quarkus Config Properties

Worker physics and economy constants — override without recompiling:

```properties
# application.properties or -D flags
starcraft.worker.mining-duration-loops=22.0        # SC2 observed — restored in #634
starcraft.worker.gas-harvest-duration-loops=31.7   # Liquipedia 1.415s; SC2 profiler confirms 32 loops (#665)
starcraft.worker.gas-approach-docking-loops=11.5   # gas geyser entry overhead — calibrated #689
starcraft.worker.deposit-duration-loops=12.0       # 8w SC2 cadence — calibrated #661
starcraft.worker.approach-animation-loops=20.0     # approach animation overhead (#679, #707)
starcraft.worker.mineral-patch-obstacle-radius=0.3  # A* pathing grid obstacle radius for mineral patches; calibrated #707
starcraft.worker.collision-speed-factor=1.0        # disabled — transition loop cost replaces (#661)
starcraft.worker.acceleration=3.5                  # SC2Mapster × 1.4 — restored from 10.0 (#634)
starcraft.worker.deceleration=3.5                  # SC2Mapster × 1.4 — restored from 10.0 (#634)
starcraft.worker.patch-docking-overhead-loops=22.0
starcraft.worker.patch-distance=7.0
starcraft.worker.initial-stagger-loops=6.1
starcraft.worker.reroute-threshold-loops=22.0
starcraft.economy.rate-interval-loops=160
```

**Static-field constants (not Quarkus config — set in code):**
- `SimulatedWorker.COLLISION_RADIUS` (0.75, was 0.375) — worker collision detection radius; calibrated #634
- `MineralPatch.CLOSE_PATCH_DISTANCE` (6.7f) — default close patch distance for first 4 patches; calibrated #651 from SC2 API positions
- `MineralPatch.FAR_PATCH_DISTANCE` (7.5f) — default far patch distance for last 4 patches; calibrated #651 from SC2 API positions
- ~~`MineralPatch.PATH_DEVIATION_SCALE`~~ — **removed** in #707 (obstacle-aware A* replaced per-patch path delay formula)
- ~~`MineralPatch.PATH_DEVIATION_BASE`~~ — **removed** in #707
- `WorkerSimulation.LOW_SATURATION_DODGE_SCALE` (0.25f) — collision dodge reduction at ≤2 workers/patch; calibrated #656 from dodge sweep
- `GasSlot.MAX_INSIDE` (1) — max workers inside gas building simultaneously; Liquipedia: "Only one worker can be inside the building collecting at any one time" (#682)
- `GasSlot.ENTRY_LOCK_DURATION` (default 0) — gas entrance queuing lock duration in loops
- `WorkerSimulation.GAS_DEPOSIT_LOCK_LOOPS` (8.0) — per-base gas deposit serialisation lock in loops; prevents simultaneous gas deposits at the same town hall; calibrated #682 by sweep against 6w dual-geyser baseline (lock 6–9 optimal, cliff at 13+)

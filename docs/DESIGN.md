# QuarkMind — Design

## Overview

QuarkMind (formerly "starcraft", package root `io.quarkmind`) is a Quarkus application that plays StarCraft II (Protoss) as a plugin platform. Primary purpose is R&D: a living testbed for Drools, Quarkus Flow, and CaseHub (a Blackboard/CMMN framework). The platform provides scaffolding, SC2 connection, and the CaseHub control loop; intelligence is provided by swappable plugins behind CDI seams.

All four plugin seams (Strategy, Economics, Tactics, Scouting) are implemented using different R&D frameworks. The bot can connect to a live SC2 process and issue real game commands. An emulation engine (`EmulatedGame`) provides physics-based game simulation without requiring a live SC2 binary, served with a Three.js live visualizer in an Electron window.

**GitHub:** `mdproctor/quarkmind`
**Test count:** 690 (unit + integration + Playwright E2E)

---

## Architecture

```
SC2Client  →  GameObserver  →  GameStateTranslator  →  AgentOrchestrator
                                                               │
                                                     CaseHub CaseEngine
                                                    (Blackboard control loop)
                                                               │
                                    ┌──────────┬──────────────┼──────────────┐
                                StrategyTask EconomicsTask TacticsTask ScoutingTask
                                    └──────────┴──────────────┴──────────────┘
                                                  (plugin seams)
                                                               │
                                              CommandDispatcher / IntentQueue
                                                               │
                                                      ActionTranslator
                                                   (Intent → ResolvedCommand)
```

The game loop fires once per Quarkus Scheduler tick. Each tick:
1. `SC2Engine.tick()` advances the internal clock (mock/emulated only; no-op for real SC2)
2. `SC2Engine.observe()` returns the current `GameState`
3. `GameStateTranslator` converts it to a CaseHub CaseFile map
4. `AgentOrchestrator` cycles the CaseEngine; plugins fire and produce `Intent` objects
5. `SC2Engine.dispatch()` drains the `IntentQueue` → `ActionTranslator.translate()` → `ResolvedCommand` records applied via ocraft `ActionInterface`

In the `%emulated` profile, `EmulatedGame` replaces the SC2 client with a physics simulation engine; `GameStateBroadcaster` pushes state to a Three.js visualizer via WebSocket each tick.

---

## Domain Model

Plain Java records in `domain/` — no framework dependencies, always native-compatible.

| Record | Purpose |
|---|---|
| `GameState` | Snapshot: minerals, vespene, supply, unit lists, enemy buildings, mineral patches, geysers, game frame |
| `Unit` | Single unit: tag, type, position, health, shields, maxShields |
| `Building` | Single building: tag, type, position, health, isComplete |
| `UnitType` | Enum: PROBE, ZEALOT, STALKER, IMMORTAL, COLOSSUS, CARRIER, etc. |
| `BuildingType` | Enum: NEXUS, PYLON, GATEWAY, CYBERNETICS_CORE, etc. |
| `Point2d` | Map coordinate |
| `PendingCompletion` | Under-construction building: completesAtTick, buildingType |
| `ResourceBudget` | Per-tick spending budget: minerals/vespene consumed by plugins |
| `Race` | Enum: PROTOSS, ZERG, TERRAN — used by `EnemyStrategy` and `TechTree` |
| `EnemyObservation` | Snapshot from the enemy's perspective: playerUnits, enemyBuildings, minerals, gameFrame |
| `EnemyStrategy` | Interface — see §Mock Infrastructure |
| `EnemyAttackConfig` | Record: armyThreshold, attackIntervalFrames, retreatHealthPercent, retreatArmyPercent |
| `TechTree` | Prerequisite graph — see §Mock Infrastructure |

`SC2Data` in `domain/` provides shared constants for both `SimulatedGame` and `EmulatedGame`: damage-per-tick, attack range, supply cost, shield values, building health, mineral costs, and SC2 timing constants (`LOOPS_PER_TICK=22`, `GAME_LOOPS_PER_SECOND=22.4`). `trainTimeInLoops(UnitType)` returns empirically calibrated integer game-loop train durations (e.g. Probe=272 — SC2 stores these as integers, not as `seconds × 22.4`; values calibrated from 29 AI Arena replays via `SC2TrainTimeCalibrationTest`); `trainTimeInTicks` derives from it. `mineralIncomePerTick(int probeCount)` implements a per-base three-tier probe saturation model (50/25/5 min/min per probe, configurable via `MINERAL_PATCHES_PER_BASE` and `MINERAL_TIER_RATES_PER_TICK[]`); callers with multiple bases sum across each base's probe count. Centralised here to eliminate drift between engines.

---

## Component Structure

| Package | Responsibility |
|---|---|
| `domain/` | Plain Java records — no CDI, no framework imports |
| `sc2/` | CDI interfaces: `SC2Engine` (unified engine seam), `ScenarioRunner`, `IntentQueue` |
| `sc2/intent/` | Sealed `Intent` interface + types: `BuildIntent`, `TrainIntent`, `AttackIntent`, `MoveIntent`; `TimedIntent` (Intent tagged with its absolute game loop) |
| `sc2/mock/` | Mock implementation: `SimulatedGame`, `MockGameObserver`, `MockCommandDispatcher` |
| `sc2/mock/scenario/` | `ScenarioLibrary` — living specification of SC2 behaviour |
| `sc2/real/` | Real SC2: `RealSC2Client`, `RealGameObserver`, `RealCommandDispatcher`, `SC2BotAgent`, `ObservationTranslator`, `ActionTranslator` |
| `sc2/emulated/` | Physics simulation: `EmulatedGame`, `EmulatedEngine`, `SC2Data` (shared with domain) |
| `sc2/replay/` | Replay-driven: `ReplayEngine` (observe-only), `ReplaySimulatedGame`, `GameEventStream` (thin MPQ reader: `events(Path) → List<Event>`), `AbilityMapping` (stateful `CmdEvent`→`Intent` translator; owns selection state per player), `ReplayCommandExtractor` (orchestrates `GameEventStream` + `AbilityMapping` → `ReplayCommandStream`), `DivergenceReport`, `ReplayValidationHarness` |
| `agent/` | `AgentOrchestrator`, `GameStateTranslator`, `QuarkMindCaseFile` (key constants) |
| `agent/plugin/` | Plugin seam interfaces: `StrategyTask`, `EconomicsTask`, `TacticsTask`, `ScoutingTask` |
| `plugin/` | Real plugin implementations: `BasicEconomicsTask`, `BasicScoutingTask`, `BasicTacticsTask`, `DroolsStrategyTask`, `FlowEconomicsTask`, `DroolsTacticsTask` |
| `plugin/drools/` | Drools Rule Units: `StrategyRuleUnit`, `TacticsRuleUnit`, `ScoutingRuleUnit`, `.drl` rule files |
| `plugin/flow/` | Quarkus Flow: `EconomicsFlow`, `FlowEconomicsTask`, `GameStateTick` (flow-specific tick snapshot carrying domain state + `ResourceBudget`) |
| `agent/QuarkMindTaskRegistrar` | Startup bean wiring all four plugin seams into `TaskDefinitionRegistry` |
| `visualizer/` | `GameStateBroadcaster` (WebSocket push), `SpriteProxyResource` (Liquipedia CORS proxy) |
| `qa/` | QA REST endpoints — dev/test only (`@UnlessBuildProfile("prod")`) |
| `electron/` | Electron wrapper — `main.js` spawns Quarkus as subprocess, health-polls, manages window |
| `META-INF/resources/` | `visualizer.js` (Three.js WebGL renderer — WebSocket client, 3D terrain, directional sprites, fog of war, unit inspect panel); `three.min.js` served at `/sprites/three.min.js` |

---

## Plugin System

Each plugin seam (`StrategyTask`, `EconomicsTask`, `TacticsTask`, `ScoutingTask`) is a CDI interface extending CaseHub's `TaskDefinition`. Swap an implementation by providing a new `@ApplicationScoped @CaseType("starcraft-game")` bean — no wiring changes elsewhere.

### R&D Framework Assignments

| Task | Class | R&D Framework | Approach |
|---|---|---|---|
| `StrategyTask` | `DroolsStrategyTask` | Drools 10.1.0 Rule Units | Forward-chaining DRL rules write `STRATEGY` key to CaseFile; Java dispatches intents. Hot-reloadable. Native-safe via Executable Model. |
| `EconomicsTask` | `FlowEconomicsTask` | Quarkus Flow | Per-tick Flow instance via `@Incoming` + `startInstance(tick)`. Single `consume()` step calls all four decisions sequentially — required to prevent `ResourceBudget` reset between steps (GE-0059). |
| `TacticsTask` | `DroolsTacticsTask` | Drools + custom Java GOAP | Drools classifies unit groups (rule phase 1); Java A* finds cheapest action plan per group (rule phase 2). First action dispatched as `AttackIntent`/`MoveIntent`. |
| `ScoutingTask` | `BasicScoutingTask` | Drools CEP + Java-managed buffers | Fresh `RuleUnitInstance` per tick from Java `Deque` buffers. Avoids Drools Fusion STREAM mode incompatibility with drools-quarkus extension. |

`BasicStrategyTask` is retained as a plain (non-CDI) class: reference implementation and direct-instantiation test target.

Plugins are registered at startup by `QuarkMindTaskRegistrar` — injecting each seam interface keeps Arc from removing the beans as unused (Arc's dead-bean elimination previously silently kept the registry empty).

### Plugin Framework Key Decisions

| Decision | Chosen | Why | Alternatives Rejected |
|---|---|---|---|
| Orchestration framework | CaseHub (Blackboard/CMMN) | Case file, task lifecycle, reactive control loop | Custom scheduler, Kogito |
| StrategyTask framework | Drools 10 forward-chaining rules | Declarative rules, hot-reload, native-safe Executable Model | Jadex BDI (GPL-3.0), Jason (no Maven Central) |
| EconomicsTask framework | Quarkus Flow (CNCF Serverless Workflow) | Stateful workflow, Quarkus-native, LangChain4j bridge | SmallRye reactive pipeline, Temporal |
| TacticsTask framework | Drools + custom Java GOAP planner | Native-safe, no external dep, Drools already in stack; `gdx-ai` is JVM-only | gdx-ai behaviour trees |
| ScoutingTask framework | Drools rule units + Java-managed CEP buffers | Avoids Drools Fusion STREAM mode incompatibility with Quarkus rule unit model | Drools Fusion `window:time()` (requires KieSession + kmodule.xml) |
| Drools inter-phase signalling | `DataStore<String>` (not `List<String>`) | DataStore insertions trigger RETE re-evaluation; plain List mutations don't (GE-0109) | `eval(list.stream()...)` in Phase 2 LHS — silently never fires |
| Plugin deactivation | `@Alternative` on inactive CDI bean | Arc deactivates `@Alternative` beans without beans.xml or `@Priority` on the replacement | Deleting old implementation |
| Flow input type | Immutable `GameStateTick` record | Jackson cannot serialize plain mutable classes (GE-0060); records work natively | Mutable POJO |
| Flow integration | `@Incoming` bridge + `startInstance(tick)` | `listen` task only accepts CloudEvents; in-memory channel carries plain POJOs | `listen` task (silent — never fires, GE-0061) |
| Flow step collapse | Single `consume()` step, all four decisions | Quarkus Flow serialises `GameStateTick` between `consume()` steps — resetting `ResourceBudget` each time; collapsed = one serialisation boundary | Four separate `consume()` steps (budget reset per step — broken) |
| GOAP role of Drools | Action compiler — fires once per tick, produces `GoapAction` list | No session cloning per A* node; one session per tick | Per-node oracle (session cloning) |
| Intent interface | Sealed | Compiler enforces switch exhaustiveness; new intent type can't silently fall through to `default` no-op | Open with `default` warn-and-skip |

---

## Mock Infrastructure

`SimulatedGame` is the scripted test oracle — updated whenever real SC2 surprises us. `EmulatedGame` is a physics simulation engine for development without a live SC2 binary. They are kept separate: mixing physics into `SimulatedGame` would corrupt its determinism.

| Class | Role |
|---|---|
| `SimulatedGame` | Hand-crafted stateful SC2 simulation; CDI bean in `%mock` profile |
| `ReplaySimulatedGame` | Replay-driven variant; plain Java, driven from real `.SC2Replay` tracker events (PlayerStats, UnitBorn, UnitDied, UnitInit, UnitDone). Captures neutral units (`ctrlId==0`) as mineral patches and geysers; enemy buildings tracked via UnitBorn/UnitInit/UnitDone/UnitDied. |
| `ReplayEngine` | `SC2Engine` for `%replay` profile — observe-only, records agent intents |
| `EmulatedGame` | Physics referee: holds `PlayerState friendly` + `PlayerState enemy`; both players share the same `applyIntent(Intent, PlayerState)` and `IntentQueue` drain path; CDI bean in `%emulated` profile. `applyIntent(TimedIntent)` preserves the absolute game loop for sub-tick train-completion precision; `startTraining` uses pure integer arithmetic `(loopOffset + SC2Data.trainTimeInLoops(unitType)) / LOOPS_PER_TICK` — no float cast. Exposes two harness injection paths: `injectReplayBuilding(Building)` (free, for game-start buildings gifted to the player) and `injectReplayBuildingWithCost(Building)` (deducts mineral cost; minerals may go negative, representing a short-lived debt repaid through income — clamping at 0 was evaluated and worsens divergence); `markReplayBuildingComplete(String)` marks a building complete by tag; `setMiningProbesPerBase(int... probesPerBase)` — one-shot override consumed by the next tick (defensively cloned); `setSupplyCapForHarness(int)` — syncs supply cap from GT; `tick()` auto-computes `miningProbesPerBase` from `friendly.buildings` (complete nexuses) and `friendly.units` (probes) via `countProbesPerBase(List<Building>, List<Unit>)` using squared-distance nearest-nexus assignment — unless `miningProbesOverridden` flag is set (cleared after consumption); sums `SC2Data.mineralIncomePerTick` across each base's probe count. Replay harness calls `setMiningProbesPerBase` before every tick to inject ground-truth counts; standalone/emulated mode auto-computes from game state. `drainBuildingQueues` propagates the previous unit's absolute completion loop to the next queued unit via `buildingCompletionAtLoop`, preserving sub-tick precision across queued training. |
| `PlayerState` | Per-player mutable state: units, buildings, stagingArea, minerals, supply, pendingCompletions, buildingCompletionAtLoop (sub-tick loop propagation for queued units), movement/combat maps |
| `PlayerBehavior` | Interface: `tick(GameState, IntentQueue)` — drives decisions for one player per tick |
| `EnemyBehavior` | `implements PlayerBehavior`; owns production loop, tech-tree gating, attack launch, retreat, strategy switching |
| `EnemyStrategy` | Interface: `name()`, `race()`, `mineralsPerTick()`, `attackConfig()`, `nextUnit(obs)`, `shouldSwitch(obs)` |
| `FixedBuildOrderStrategy` | `EnemyStrategy` implementation: loops a fixed `List<UnitType>`, never switches |
| `ReactiveStrategy` | `EnemyStrategy` implementation: re-evaluates every N frames, counter-picks based on dominant friendly unit type |
| `EnemyStrategyLibrary` | Static registry of 9 named strategies (3 Protoss, 3 Zerg, 3 Terran) + `REACTIVE`; `forName(String)` and `randomForRace(Race)` return fresh instances |
| `TechTree` | Domain class: static prerequisite graph for all three races; `canTrain(unit, builtSet)` and `nextRequired(unit, builtSet)` |
| `EmulatedEngine` | `SC2Engine` wrapping `EmulatedGame`; active on `@IfBuildProfile("emulated")`; wires `EnemyBehavior` at `joinGame()` using race + strategy from `EmulatedConfig` |
| `ScenarioLibrary` | Named test scenarios (set-resources, spawn-enemy-attack, etc.) |
| `SC2Data` | Shared constants — see §Domain Model |

### Emulation Engine Key Decisions

| Decision | Chosen | Why | Alternatives Rejected |
|---|---|---|---|
| `EmulatedGame` separate from `SimulatedGame` | `EmulatedGame` in `sc2/emulated/` | `SimulatedGame` is the scripted test oracle; mixing physics corrupts its determinism | Evolve SimulatedGame in-place |
| `SC2Data` in `domain/` | Shared constants for both engines | Eliminates drift between SimulatedGame and EmulatedGame data tables | Duplicate tables in each engine |
| `@IfBuildProfile("emulated")` on `EmulatedEngine` | Positive guard — active only in `%emulated` | Prevents CDI ambiguity without growing other engines' exclusion lists | Add "emulated" to all other `@UnlessBuildProfile` lists |
| Symmetric `PlayerState` × 2 | Both players same data shape; `applyIntent(Intent, PlayerState)` shared | Eliminates duplicated train/build/move logic; combat symmetric by construction | Asymmetric enemy subclass with overrides |
| `PlayerBehavior` interface | `tick(GameState, IntentQueue)` — same contract for both sides | Future CaseHub-driven enemy is a drop-in; friendly side already satisfies the contract implicitly | Enemy-specific callback shape |
| `EnemyBehavior` package-private | Only `EmulatedGame` (same package) constructs and wires it | No reason to expose production detail; `setEnemyBehavior()` shim for tests | Public CDI bean |
| `setEnemyStrategy()` test shim | Wraps strategy in an `EnemyBehavior` with a permissive `TechTree` | Existing tests don't exercise tech gating — shim preserves backward compatibility | Force all tests to supply `TechTree` |
| `TechTree` in `domain/` | No framework deps; friendly plugins can optionally consult it | Prerequisite logic is pure data; no reason to couple to emulation layer | `sc2/emulated/` package (would prevent friendly-side use) |
| `EnemyStrategyLibrary.forName()` returns fresh instance | Each caller gets independent build-order index and internal state | Shared instance would corrupt state if two callers advance the build order | Singleton strategy objects |
| `REACTIVE` excluded from `randomForRace()` | Has no fixed race; race reflects chosen counter at runtime | `randomForRace()` callers expect a race-homogeneous pool | Include REACTIVE in random pool |
| Mineral accumulation inside `EnemyBehavior` | `enemy.minerals += strategy.mineralsPerTick()` each tick | Decouples rate from physics tick; rate is a strategy decision | `EmulatedGame` accumulates minerals and gives enemy a budget |
| Tech-tree building deduplication via `pendingBuildings` | `Set<BuildingType>` tracks in-progress prereq builds | Without dedup, every tick without minerals queues another `BuildIntent` for the same prereq | Re-check enemy.buildings only — misses in-flight builds |

### Emulation Engine Progress

| Stage | What | Status |
|---|---|---|
| E1 | Unit movement, probe mineral harvesting, build times, `EmulatedGame`/`EmulatedEngine` infrastructure | ✅ Complete |
| E2 | Vector-based movement, scripted enemy wave at frame 200, full intent handling, cost deduction, `EmulatedConfig` live config panel | ✅ Complete |
| E3 | Shields/maxShields on `Unit`, two-pass simultaneous combat resolution, `SC2Data.damagePerTick`/`attackRange`/`maxShields`, unit death | ✅ Complete |
| E4 | Enemy active AI — `PlayerState`×2 symmetric architecture, `EnemyBehavior`, `EnemyStrategyLibrary` (9 strategies + REACTIVE), `TechTree`, `ReactiveStrategy` | ✅ Complete |
| E5 | Pathfinding + terrain — A* on tile map, terrain-aware edge costs (RAMP=1.5×), sub-tile LOS path smoothing, SC2BotAgent CDI bean with `TerrainProvider` injection | ✅ Complete |
| E6 | Building collision — `enforceWall()` extended with circular building footprints; `SC2Data.buildingRadius(BuildingType)`; entry-only semantics; always active (independent of terrain grid) | ✅ Complete |

### Combat Model (E3 + auto-engage)

Two-pass simultaneous combat resolution prevents order-dependency:
1. **Collect phase**: for every unit (friendly and enemy), if an opponent is within weapon range, accumulate effective damage into `Map<String, Integer>` (tag → total damage). No explicit `AttackIntent` required — units fire automatically at the nearest in-range opponent each tick.
2. **Apply phase**: subtract from health (and shields first for Protoss), remove units at HP ≤ 0

`attackingUnits` is a `Set<String>` on `PlayerState`, still written by `setTarget` (AttackIntent adds, MoveIntent removes), but no longer consulted in combat resolution. It is retained as dead state; cleanup tracked in #134. Movement toward an attack target is still driven by `unitTargets`.

---

## Visualizer

A Three.js live visualizer renders game state each tick in a 3D orbiting-camera scene, served by Quarkus over WebSocket, wrapped in an Electron native window.

| Component | Role |
|---|---|
| `GameStateBroadcaster` | `SC2Engine` frame listener; pushes JSON game state to all WebSocket clients on each tick |
| `visualizer.js` | Three.js WebGL renderer: 3D terrain plane, directional canvas-2D sprite textures per unit type, fog of war, health tinting, unit/building inspect panel, SC2-style and free-orbit camera modes |
| `visualizer.html` | Loads `three.min.js` from `/sprites/three.min.js`, then `visualizer.js`; no build step |
| `electron/main.js` | Spawns Quarkus as subprocess, health-polls until ready, opens OS window |

**Renderer migration:** The original PixiJS 8 renderer (E1–E13) was replaced by Three.js in E14 to support a 3D orbiting camera, terrain height, and directional sprite sheets. The server-side `GameStateBroadcast` protocol is unchanged.

**Canvas testing:** `window.__test` semantic API exposed from `visualizer.js` — Three.js renders to a WebGL canvas; the API provides semantic assertions (sprite counts, positions, panel text, pixel samples) that survive visual changes.

### Visualizer Key Decisions

| Decision | Chosen | Why | Alternatives Rejected |
|---|---|---|---|
| WebSocket push | `GameStateBroadcaster` as frame listener | Zero latency — pushed on each tick; no wasted polls | `setInterval(fetch)` polling |
| Three.js replaces PixiJS | Client-side only swap (E14) | 3D orbiting camera, terrain height, directional sprite textures impossible in PixiJS 2D | Stay with PixiJS — no 3D |
| Directional sprites | Canvas 2D textures baked per facing | No asset pipeline; all geometry hand-coded in JS; survives SC2 asset restrictions | External sprite sheets |
| `window.__test` semantic API | Exposes game state and DOM reads to Playwright | WebGL canvas has no accessible DOM; semantic API bridges the gap for CI | Screenshot pixel comparison |
| Electron wraps Quarkus | `main.js` with health poll | Single native window; Quarkus lifecycle managed by Electron | Separate terminal windows |

---

## Real SC2 Integration

`ActionTranslator` — a pure static class mirroring `ObservationTranslator` — converts the `IntentQueue` drain into `ResolvedCommand` records. `SC2BotAgent.onStep()` applies them via the ocraft `ActionInterface`.

`SC2BotAgent` is `@ApplicationScoped @IfBuildProfile("sc2")` — a CDI bean injected by `RealSC2Engine`. It `@Inject`s `TerrainProvider` and extracts the pathing grid in `onGameStart()`, populating `TerrainGrid` for use by `AStarPathfinder`.

| Decision | Chosen | Why | Alternatives Rejected |
|---|---|---|---|
| `translate()` returns `List<ResolvedCommand>` | Pure function — testable without mocking | `ActionInterface` has 12+ overloads; returning data eliminates any mocking framework | Call `ActionInterface` directly in translator |
| `ResolvedCommand` package-private | Only `ActionTranslator` + `SC2BotAgent` interact | No reason to expose beyond `sc2.real` | Public record |
| Tag-based dispatch | `ActionInterface.unitCommand(Tag, ...)` | Dead/stale tags silently ignored by SC2; no `UnitInPool` lookup needed | Look up `UnitInPool` via `observation()` |
| `casehub-persistence-memory` as runtime dep | CaseHub split into core + persistence modules | `CaseEngine` injects `TaskRepository`/`CaseFileRepository` from persistence module | Bundle everything in casehub-core |
| `quarkus.index-dependency` for persistence jar | No Jandex in casehub-persistence-memory jar | Quarkus skips CDI scanning of jars without `META-INF/jandex.idx` | Rebuild CaseHub with Jandex plugin |

---

## Core Agent Loop Decisions

| Decision | Chosen | Why | Alternatives Rejected |
|---|---|---|---|
| Engine abstraction | Single `SC2Engine` seam (replaces 3 interfaces) | All three always move together; one injection point | Keep 3 separate seams |
| Replay mode | `ReplayEngine` observe-only; `dispatch()` records intents | Replay is immutable; intents logged for offline evaluation | Apply intents to shadow simulation |
| Resource arbitration | `ResourceBudget` in CaseFile, consumed by plugins | Prevents double-spend without inter-plugin communication | Check raw minerals; accept over-commit |
| Build times | `PendingCompletion` with `completesAtTick`; buildings appear as `isComplete=false` immediately | Plugins need to see under-construction buildings; supply granted on completion | 1-tick instant |
| Training queues | Per-building queue (max 5 total); supply reserved at queue time; building type validated against `SC2Data.trainedBy()` | SC2-accurate: parallel training across buildings, no over-supply | Single global queue; supply at completion |
| Active scouting | `@ApplicationScoped` state tracking scout probe tag | Singleton CDI bean state persists across ticks | CaseFile key per tick (doesn't persist) |
| Mock auto-start | `MockStartupBean` with `@UnlessBuildProfile(anyOf = {"sc2","replay","test","prod"})` | Mirrors SC2StartupBean/ReplayStartupBean pattern; `anyOf` undocumented but works | Require manual POST /sc2/start |

---

## Quarkus Profiles

| Profile | SC2 needed | Active beans |
|---|---|---|
| `%mock` (default) | No | `SimulatedGame`, `MockSC2Client`, `MockGameObserver`, `MockCommandDispatcher` |
| `%emulated` | No | `EmulatedGame`, `EmulatedEngine`; visualizer WebSocket active |
| `%sc2` | Yes | `RealSC2Client`, `RealGameObserver`, `RealCommandDispatcher`, `SC2BotAgent` |
| `%replay` | No | `ReplayEngine` — full agent loop against a `.SC2Replay` file; `dispatch()` is observe-only |
| `%test` | No | Same as mock; scheduler disabled |
| `%prod` | — | QA endpoints stripped (`@UnlessBuildProfile("prod")`) |

---

## Key Dependencies

| Dependency | Purpose | Native? |
|---|---|---|
| `casehub-core` + `casehub-persistence-memory` | Blackboard/CMMN engine (local Maven install) | TBD |
| `drools-quarkus` + `drools-ruleunits-api/impl` | Drools 10.1.0 — Rule Units, AOT via Executable Model | ✅ Native-compatible |
| `quarkus-flow` | CNCF Serverless Workflow — per-tick economics flow | ✅ Quarkus-native |
| `ocraft-s2client` | SC2 protobuf API client | ❌ JVM-only — tracked in NATIVE.md |
| `scelight-mpq` + `scelight-s2protocol` | SC2 replay parsing (local fork) | ❌ JVM-only — tracked in NATIVE.md |
| `three.min.js` | Three.js WebGL renderer — served at `/sprites/three.min.js` | N/A (JS) |
| Playwright + Chromium | E2E canvas testing via `window.__test` semantic API | N/A (test only) |
| Electron | Native OS window wrapping Quarkus visualizer | N/A (desktop) |
| Quarkus 3.34.2 | Container, CDI, scheduler, REST, WebSocket | ✅ BOM |

---

## Testing Strategy

- **Unit tests** (`new`, no CDI): `SimulatedGameTest`, `ReplaySimulatedGameTest`, `IntentQueueTest`, `MockPipelineTest`, `ScenarioLibraryTest`, `GameStateTranslatorTest`, `GameStateTest`, `EmulatedGameTest`, `TerrainGridTest`, `AStarPathfinderTest`, `PathfindingMovementTest`, `SC2BotAgentTerrainTest`, `AbilityDiscoveryTest`, `AbilityMappingTest`, `ReplayCommandExtractorTest`, `ReplayValidationTest`, `ReplaySimulatedGameMovementTest`, `SC2TrainTimeCalibrationTest` (two-source range-bounded modal calibration — pairs GAME_EVENTS abilLink commands with tracker UnitBorn events across 29 AI Arena replays)
- **Replay divergence report** (`@Tag("report")`, `mvn test -Preport`): `ReplayValidationReportTest` — runs `ReplayValidationHarness` to completion and prints full economic divergence report to stdout; excluded from default surefire run
- **Integration tests** (`@QuarkusTest`, full CDI): `QaEndpointsTest`, `FullMockPipelineIT` — scheduler disabled, `orchestrator.gameTick()` called directly
- **Playwright E2E tests**: 251 render tests — sprite counts/positions/health tinting/death; panel inspect (team label, HP text, portrait canvas pixel alpha); pixel-colour sampling for minerals, geysers, creep; fog; use `window.__test` semantic API including `clickUnit(tag,isEnemy)`, `clickBuilding(tag,isEnemy)`, `panelTeam()`, `panelHpText()`, `panelPortraitSample()`, `unitHasTag(tag)`, `buildingHasTag(tag)`
- **Benchmark tests** (`@Tag("benchmark")`, `mvn test -Pbenchmark`): excluded from normal runs; `AtomicReference<TickTimings>` in `AgentOrchestrator` exposes last tick's phase breakdown; baseline: 2ms mean plugin time (pre-E2)
- **Total: ~649 tests**

**Rules:**
- Never use `@QuarkusTest` for tests that can be plain JUnit
- Exception: Drools Rule Unit tests require `@QuarkusTest` — `DataSource.createStore()` is initialized by the Quarkus extension at build time and unavailable in plain JUnit (GE-0053). `DroolsStrategyTaskTest` injects `StrategyTask` and calls `execute(CaseFile)` directly.
- WebSocket tests: use `java.net.http.WebSocket` (built-in Java 11 client) — Tyrus standalone conflicts with Quarkus classloader

---

## Current State

E1–E6 complete. QuarkMind:
- Connects to and issues commands in a live SC2 game (all four plugins, real unit/building tags, sealed Intent dispatch)
- `SC2BotAgent` is a CDI bean (`@ApplicationScoped @IfBuildProfile("sc2")`); injects `TerrainProvider` and extracts the pathing grid in `onGameStart()`
- Runs full agent loop against `EmulatedGame` with symmetric two-player physics: friendly and enemy each have a `PlayerState`, both share the same `applyIntent()` / `IntentQueue` path
- Enemy driven by `EnemyBehavior` implementing `PlayerBehavior`: production loop, tech-tree gating (`TechTree`), 9 named strategies across all 3 races via `EnemyStrategyLibrary`, and `ReactiveStrategy` that counter-picks based on observed friendly unit composition
- A* pathfinding with terrain-aware edge costs (RAMP tiles cost 1.5×); `AStarPathfinder.smoothPath()` applies sub-tile LOS greedy string-pulling post-processing; `PathfindingMovement.advance()` applies smoothing after `findPath()`
- Building collision in emulated physics: `enforceWall()` blocks unit entry into completed building footprints; `SC2Data.buildingRadius(BuildingType)` maps types to circular radii (2.5 for Nexus/Hatchery/CC, 1.5 for 3×3 tech, 1.0 for 2×2 structures); entry-only semantics allow workers already near Nexus to move freely
- Three.js 3D visualizer (replaced PixiJS in E14): orbiting camera, terrain, directional cartoon sprites for all three races, fog of war, unit/building inspect panel (instant — reads from cached WebSocket state)
- 629 tests: unit + integration + Playwright E2E

## Next Steps

- **#13 Live SC2 smoke test** — blocked on SC2 availability
- **#14 GraalVM native image tracing** — blocked on #13
- **Deferred visualizer work** — probe overlap fix, HTML mineral display, geyser sprite, time-based UI tests
- **LangChain4j experimental StrategyTask** — LLM-guided strategy as a fifth R&D integration (Phase 4+, Ollama local model); deferred until core emulation is stable
- **Intent dispatch quality** — no guard against dead unit tags or incomplete buildings; bot commands whatever tag the plugin supplies
- **#143 Multi-base mining** — ✅ resolved: `EmulatedGame` sums per-base income; `ReplayValidationHarness` assigns probes to nearest nexus from GT; `SC2Data.mineralIncomePerTick` unchanged (per-base function)
- **#148 Vespene income model** — ✅ resolved: harness syncs vespene from pre-tick GT via `setVespeneForHarness(int)`; gas-unit train commands no longer rejected
- **#153 Code review nits** — ✅ resolved: sqrt→squared distance in `countProbesPerBase`, defensive clone in `setMiningProbesPerBase`, `ReplayValidationHarnessTest` added
- **#152 Per-base probe distribution** — ✅ resolved: `tick()` auto-computes `miningProbesPerBase` from buildings/units; `countProbesPerBase` extracted to `EmulatedGame` as canonical algorithm; one-shot override flag for replay harness

---

## Open Questions

- `ReplaySimulatedGame` uses `shields=0` for replay units — replay tracker events don't include instantaneous shield state
- Observer supply cost was defaulting to 2 (real SC2 value is 1) — fixed in `SC2Data.supplyCost`; test `SC2DataTest#observerSupplyCostIsOne` covers it
- Expansion detection heuristic: "enemy unit > 50 tiles from main base" accuracy against real SC2 unknown
- GOAP goal assignment hot-reload — DRL enables it but never exercised in practice
- Playwright Chromium install in CI — currently requires manual install step
- `SC2Engine.tick()` ownership — who owns the tick loop when real SC2 is connected? Open since Phase 0


---

## ADRs

See [docs/adr/INDEX.md](adr/INDEX.md) for the full index.

| ADR | Decision |
|---|---|
| [ADR-0001](adr/0001-quarkus-flow-placement.md) | Quarkus Flow placement — per-tick stateful plugin |
| [ADR-0002](adr/0002-two-pass-simultaneous-combat-resolution.md) | Two-pass simultaneous combat resolution |
| [ADR-0003](adr/0003-attackingunits-set-semantics.md) | `attackingUnits` Set for attack-mode tracking |
| [ADR-0004](adr/0004-flow-single-consume-step.md) | Quarkus Flow single `consume()` step for economics |
| [ADR-0005](adr/0005-sc2data-in-domain.md) | `SC2Data` shared constants in `domain/` |
| [ADR-0006](adr/0006-emulatedgame-simulatedgame-separation.md) | `EmulatedGame`/`SimulatedGame` separation |

**Deferred:**
- `HttpSC2Engine` — network bridge; SC2 on one machine, agent on another (Phase 4)
- **#140 Terran replay files** — Terran ability IDs in `AbilityMapping` are stubs; need raw `.SC2Replay` files from Terran games to populate via `AbilityDiscoveryTest`
- **#138 Terran/Zerg EmulatedGame** — `EmulatedGame` currently models Protoss mechanics only

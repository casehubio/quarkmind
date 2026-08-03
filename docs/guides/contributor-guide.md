# quarkmind -- Contributor Guide

> Internals, architecture, and extension points for quarkmind platform contributors.

**GitHub:** [casehubio/quarkmind](https://github.com/casehubio/quarkmind)

---

## Module Structure

### What It Owns

- **SC2 domain model:** game state, units, buildings, actions, intents; `SC2Data` -- all game constants (costs, timings, ranges, armour, attributes, mineral income saturation model); `UnitCosts` exhaustive `EnumMap`; `UnitCombatStats`, `UnitDefenses`, `UnitAttribute`
- **Plugin seam interfaces:** `StrategyTask`, `EconomicsTask`, `TacticsTask`, `ScoutingTask` -- each extends QuarkMind's own `TaskDefinition` interface (package `io.quarkmind.agent`)
- **Active plugin implementations:**
  - Strategy: `DroolsStrategyTask` (primary Drools Rule Units), `EarlyPressureStrategyTask`, `EconomicExpansionStrategyTask` (competing L6 strategies)
  - Economics: `BasicEconomicsTask` (Java), `FlowEconomicsTask` (Quarkus Flow)
  - Tactics: `DroolsTacticsTask` (Drools + GOAP), `BasicTacticsTask` (Java fallback)
  - Scouting: `BasicScoutingTask` (Java), `DroolsScoutingTask` (Drools CEP + `PatternClassifier`)
- **Strategy routing:** `SC2StrategyRouterTask` (CBR-based), `DispositionAwareRoutingStrategy`, `QuarkMindTrustRoutingPolicyProvider`
- **`QuarkMindCaseFile`** -- 30 CaseFile key constants covering observation state, resource budget, agent strategy, intel, commentary triggers, coaching triggers
- **SC2 engine seam:** `SC2Engine` interface; `IntentQueue`; `GameStarted`/`GameStopped` CDI events; sealed `Intent` interface (6 permits: `BuildIntent`, `TrainIntent`, `AttackIntent`, `MoveIntent`, `BlinkIntent`, `MuleCalldownIntent`); `TimedIntent`
- **Mock, emulated, replay, emulated-sc2, and real SC2 profiles**
- **`EmulatedGame`** -- full physics simulation: probe-driven mining (per-base, three-tier saturation model), parallel training queues, sub-tick train timing, building cost deduction, vespene harvesting, combat (damage, armour, Hardened Shield), blink mechanics, auto-engage, visibility grid (`VisibilityGrid`, `TileVisibility`), building collision (`enforceWall` with circular footprints), enemy AI (`EnemyBehavior`, `TechTree`, `ReactiveStrategy`)
- **`RaceModel`** plugin seam for multi-race `EmulatedGame`: `ProtossRaceModel`, `TerranRaceModel` (MULE calldown, MULE expiry, flat income bonus), `ZergRaceModel` (larva regen, EGG morphing, Queen energy + auto-inject, Overlord supply); `RaceModelFactory`
- **`ReplayValidationHarness`** -- replay ground truth vs `EmulatedGame` per-tick economic divergence
- **`IEM10CommandExtractor`** -- extracts `List<TimedIntent>` from SC2EGSet JSON `gameEvents` using IEM10-era abilLink constants
- **`TerrainGrid`** (HIGH/LOW/RAMP/WALL height model), `AStarPathfinder` (terrain-aware A* with RAMP edge cost 1.5x), `PathfindingMovement` (LOS greedy string-pulling post-processing)
- **Three.js 3D visualiser:** 65+ unit/building sprites across all 3 races, fog of war, terrain shading, click-to-inspect panel, replay scrub control, Electron wrapper
- **Hierarchical event summarisation:** `MomentDetectionTask` (Drools rule unit), `GamePhaseSummariser`, `GameArcSummariser`; four-level temporal abstraction: raw ticks -> moments -> phases -> arcs. Pre-positioned for `casehub-blocks` migration.
- **LLM advisory team:** 12 `AgentDescriptor` configurations in `QuarkMindAgentRegistrar`: 6 advisory (crisis/strategic/economic x 2 dispositions), 4 commentary (reactive/narrative x 2 dispositions), 2 coaching (directive/socratic). `DispositionAwareRoutingStrategy`, `AdvisoryWorkerFactory`, multi-dimensional trust scoring via `MilestoneOutcomeRecorder`.
- **Commentary system:** `CommentaryWorkerFactory` creates reactive + narrative workers; `CommentaryAccumulator` manages output accumulation; `CommentaryTriggerBuilder` generates triggers from game events; `NarrativeContextHolder` maintains cross-tick narrative state
- **Coaching system:** `CoachingWorkerFactory`, `CoachingTriggerBuilder`, `CoachingComplianceEvaluator` (position-based verification: `ArmyCentroidMovement`, `ExpansionPlacement`, `UnitsNearLocation`, `LocationResolver`), `CoachingSessionSelector`, `CoachingEffectivenessTrustRecorder`
- **Enemy strategy classifier:** `PatternClassifier` + `PatternClassificationRuleUnit` (Drools CEP); `ConfidenceRevision` (decay, counter-indication, multi-archetype publishing); race guards prevent false positives in cross-race matchups
- **Dominance assessment:** `MultiFactorDominanceAssessor` (economy, tech, army value, base count); `DominanceWeightStrategy` SPI with `TemporalDominanceWeightStrategy`, `SituationalDominanceWeightStrategy`, `DroolsDominanceWeightStrategy` (DRL rule-driven); `WeightContext`, `WeightModifier`
- **Strategy taxonomy:** `StrategyTaxonomy` -- 58 `StrategyArchetype` entries across all 3 races, 3 game phases, 6 categories; matchup-keyed counter data (`CounterEntry`, `CounterInfo`)
- **Dynamic phase detection:** `StateBasedPhaseResolver` implements `PhaseResolver`; resolves `GamePhase` from supply thresholds, expansion count, tech tier presence, and game time
- **CBR integration:** `SC2StrategyRouterTask` queries `CbrCaseMemoryStore` for past game experiences; `SC2GameCbrCase` captures matchup + game state features; `SC2CbrRetentionObserver` records outcomes
- **EmulatedSC2Server:** SC2 protocol wrapper over `EmulatedGame` -- bidirectional translators (`GameStateToProtobuf`, `ProtobufToIntent`), walkability bitmap encoding, full `ResponseObservation` round-trip
- **Map terrain extraction:** `MapDownloader`, `SC2MapCache`, `SC2MapTerrainExtractor` -- download and parse SC2 map files for terrain grid data

### Code Organisation

```
src/main/java/io/quarkmind/
  domain/              Plain Java records and enums -- no framework deps
  sc2/                 SC2Engine seam, IntentQueue, events, sealed Intent, SC2WebSocketCodec
  sc2/intent/          BuildIntent, TrainIntent, AttackIntent, MoveIntent, BlinkIntent,
                       MuleCalldownIntent, TimedIntent
  sc2/real/            Live SC2 -- QuarkusSC2Transport, RealSC2Engine, SC2BotAgent,
                       ObservationTranslator, ActionTranslator, SC2DebugScenarioRunner
  sc2/emulated/        EmulatedGame, EmulatedEngine, RaceModel (Protoss/Terran/Zerg),
                       EnemyBehavior, ReactiveStrategy, FixedBuildOrderStrategy,
                       EnemyStrategyLibrary, PlayerState, PlayerBehavior, VisibilityGrid,
                       DamageCalculator, PhysicsState, PathfindingMovement
  sc2/emulated/server/ EmulatedSC2Server, GameStateToProtobuf, ProtobufToIntent
  sc2/mock/            SimulatedGame, MockEngine, ReplaySimulatedGame, IEM10CommandExtractor,
                       IEM10JsonSimulatedGame
  sc2/mock/scenario/   ScenarioLibrary -- living specification of SC2 behaviour
  sc2/replay/          ReplayEngine, ReplayCommandExtractor, ReplayValidationHarness,
                       GameEventStream, AbilityMapping, DivergenceReport, UnitOrderTracker
  sc2/map/             MapDownloader, SC2MapCache, SC2MapTerrainExtractor
  agent/               AgentOrchestrator, GameStateTranslator, GameTickExecutor,
                       QuarkMindCaseHub, QuarkMindCaseFile, TickOrchestratorWorker,
                       MutableMapCaseContext, MapCaseContext, PluginDispatchBroker,
                       PluginOutcomeAuditor, GameSession, ResourceBudget
  agent/plugin/        StrategyTask, EconomicsTask, TacticsTask, ScoutingTask,
                       ScoutingIntelConsumer, ScoutingIntelPayload, ScoutingIntelType,
                       MomentDetectionSeam, SummarisationTickable
  agent/cbr/           SC2StrategyRouterTask, SC2GameCbrCase, SC2CbrRetentionObserver,
                       SC2CbrSchemaRegistrar, SC2ImplementationRoutingStrategy
  agent/               DominanceAssessor, MultiFactorDominanceAssessor,
                       DominanceWeightStrategy, DroolsDominanceWeightStrategy,
                       SituationalDominanceWeightStrategy, TemporalDominanceWeightStrategy,
                       WeightContext, WeightModifier,
                       DispositionAwareRoutingStrategy, StrategyTaxonomy,
                       StateBasedPhaseResolver, PhaseResolverProducer,
                       MilestoneOutcomeRecorder, MilestoneConfig, MilestoneSession,
                       MilestoneTrigger, MilestoneEvent,
                       ScoutingIntelBroker, PluginDecisionEvent, AdvisoryTriggerBuilder
  plugin/              DroolsStrategyTask, BasicEconomicsTask, BasicScoutingTask,
                       BasicTacticsTask, DroolsTacticsTask,
                       EarlyPressureStrategyTask, EconomicExpansionStrategyTask
  plugin/advisory/     AdvisoryChannelBroker, AdvisoryChannelBackend,
                       AdvisoryWorkerFactory, QuarkMindAgentRegistrar (12 agents),
                       CompletionCallback
  plugin/coaching/     CoachingWorkerFactory, CoachingChannelBroker, CoachingTriggerBuilder,
                       CoachingComplianceEvaluator, CoachingSessionSelector,
                       CoachingEffectivenessTrustRecorder, CoachingDispositionRegistrar,
                       position verification: ArmyCentroidMovement, ExpansionPlacement,
                       UnitsNearLocation, LocationResolver, LocationReference,
                       CoachingAdvice, CoachingUrgencyTier, CoachingDomain
  plugin/commentary/   CommentaryWorkerFactory, CommentaryChannelBroker,
                       CommentaryChannelBackend, CommentaryAccumulator,
                       CommentaryTriggerBuilder, NarrativeContextHolder,
                       CommentaryType, Commentary
  plugin/drools/       StrategyRuleUnit, TacticsRuleUnit, DominanceWeightRuleUnit,
                       AdvisoryFact
  plugin/flow/         EconomicsFlow, FlowEconomicsTask, EconomicsDecisionService,
                       EconomicsLifecycle, GameStateTick
  plugin/scouting/     DroolsScoutingTask, PatternClassifier,
                       PatternClassificationRuleUnit, ScoutingRuleUnit,
                       ConfidenceRevision, EvidenceMarker, ScoutingSessionManager,
                       events: EnemyArmyNearBase, EnemyExpansionSeen, EnemyUnitFirstSeen
  plugin/summarisation/ MomentDetectionTask, MomentDetectionRuleUnit, MomentBroker,
                       MomentConsumer, GameMoment, GameMomentType, GamePhaseSummariser,
                       GameArc, GameArcSummariser, SummarisationLifecycle, TacticalPosture
  plugin/tactics/      GoapPlanner, GoapAction, WorldState,
                       FocusFireStrategy, LowestHpFocusFireStrategy,
                       OverkillRedirectFocusFireStrategy,
                       KiteStrategy, DirectKiteStrategy, TerrainAwareKiteStrategy
  qa/                  QA REST endpoints -- dev/test only (@UnlessBuildProfile("prod"))
  visualizer/          GameStateBroadcaster, SpriteProxyResource
  electron/            main.js (Electron native window wrapper)
```

**Drools rule files (6):**

| File | Rule Unit | Purpose |
|------|-----------|---------|
| `StarCraftStrategy.drl` | `StrategyRuleUnit` | Posture-driven strategy assessment |
| `StarCraftTactics.drl` | `TacticsRuleUnit` | Unit group classification for GOAP |
| `DroolsScoutingTask.drl` | `ScoutingRuleUnit` | CEP scouting event processing |
| `PatternClassification.drl` | `PatternClassificationRuleUnit` | Enemy strategy detection with race guards |
| `DominanceWeightAdjustment.drl` | `DominanceWeightRuleUnit` | Rule-driven dominance weight resolution |
| `MomentDetectionTask.drl` | `MomentDetectionRuleUnit` | Significant game moment detection |

## Internal Architecture

### Agentic Harness Structure

| Layer | CaseHub primitive | QuarkMind expression |
|-------|-------------------|---------------------|
| Agent coordination | `casehub-engine` CaseHub runtime | `QuarkMindCaseHub extends CaseHub` with `signalAndAwaitSync` per tick; `TickOrchestratorWorker` chains plugins via `WorkerFunction.Sync` |
| Plugin tasks | `TaskDefinition` (QuarkMind-owned) | `StrategyTask`, `EconomicsTask`, `TacticsTask`, `ScoutingTask` |
| Plugin dispatch | `PluginDispatchBroker` | DECLINE/DONE on plugin activation transitions; writable context with delta tracking via `MutableMapCaseContext` |
| Adaptive selection | Binding conditions | Plugin selection based on game state in CaseFile; `requires()` + `activateIf()` two-stage gate |
| Durable execution | Quarkus Flow | `FlowEconomicsTask` -- build order execution with retry |
| Rule-based reasoning | Drools 10.1.0 | `DroolsStrategyTask`, `DroolsTacticsTask`, `DroolsScoutingTask`, `PatternClassifier`, `DroolsDominanceWeightStrategy` |
| Typed advisory channel | `casehub-qhorus` | `ScoutingIntelBroker` publishes to `quarkmind-scouting-intel`; LLM advisors subscribe as `MessageObserver`; coaching channel separate |
| Trust-weighted routing | `casehub-ledger` | Four-phase Bayesian Beta maturity; `MilestoneOutcomeRecorder` writes intra-game trust attestations at configurable milestones |
| CBR experience learning | `casehub-neocortex` | `SC2StrategyRouterTask` queries `CbrCaseMemoryStore`; `SC2CbrRetentionObserver` records game outcomes with strategy features |
| Agent personalities | `casehub-eidos` | 12 `AgentDescriptor` configurations with `ConscientiousnessTerm` disposition traits; `DispositionAwareRoutingStrategy` composes trust + disposition |
| Event summarisation | `casehub-blocks` | `MomentDetectionTask` (Drools) -> `GamePhaseSummariser` -> `GameArcSummariser`; four-level temporal abstraction |

### Layer Taxonomy

The layered structure applies to the agentic harness -- not to the SC2 emulation layer, which is domain-specific.

| Layer | Adds | Status |
|-------|------|--------|
| 1 | Naive game loop -- direct plugin calls, no CaseHub | complete (conceptual) |
| 2 | casehub-engine blackboard -- shared state between plugins | complete |
| 3 | casehub-qhorus -- typed inter-plugin communication (dual-stack) | complete |
| 4 | casehub-ledger -- audit trail for agent decisions | complete |
| 5 | Adaptive plugin selection -- binding conditions | complete |
| 6 | Trust routing -- four-phase Bayesian Beta; milestone-based intra-game trust | complete |
| 7 | Comparison vs naive game AI | complete |

### casehub-engine Phase 2 Migration

`QuarkMindCaseHub extends CaseHub` with `signalAndAwaitSync` per tick. `TickOrchestratorWorker` chains plugins via `WorkerFunction.Sync`. `MutableMapCaseContext` provides writable context with delta tracking. `casehub-poc` dependency removed. `MapCaseContext` provides read-only snapshot for cross-tick safety.

### Plugin System Internals

Each plugin seam is a CDI interface extending `TaskDefinition`. Swap an implementation by providing a new `@ApplicationScoped @CaseType("starcraft-game")` bean -- no wiring changes elsewhere. Plugins are registered at startup by `QuarkMindTaskRegistrar` (injecting each seam interface keeps Arc from removing beans as unused).

**R&D Framework Assignments:**

| Task | Class | Framework | Approach |
|---|---|---|---|
| `StrategyTask` | `DroolsStrategyTask` | Drools 10.1.0 Rule Units | Forward-chaining DRL rules write `STRATEGY` key to CaseFile; Java dispatches intents. Hot-reloadable. Native-safe via Executable Model. |
| `EconomicsTask` | `FlowEconomicsTask` | Quarkus Flow 0.7.1 | Per-tick Flow instance via `@Incoming` + `startInstance(tick)`. Single `consume()` step calls all four decisions sequentially (budget reset prevention). |
| `TacticsTask` | `DroolsTacticsTask` | Drools + custom Java GOAP | Drools classifies unit groups (rule phase 1); Java A* finds cheapest action plan per group (rule phase 2). First action dispatched as `AttackIntent`/`MoveIntent`. |
| `ScoutingTask` | `DroolsScoutingTask` | Drools CEP + Java-managed buffers | Fresh `RuleUnitInstance` per tick from Java `Deque` buffers. Avoids Drools Fusion STREAM mode incompatibility. |

**Competing strategy implementations (L6):** `EarlyPressureStrategyTask`, `EconomicExpansionStrategyTask` -- selectable via trust-weighted routing.

**Tactics strategies (CDI seam):**
- Focus fire: `FocusFireStrategy` (base), `LowestHpFocusFireStrategy`, `OverkillRedirectFocusFireStrategy`
- Kiting: `KiteStrategy` (interface), `DirectKiteStrategy`, `TerrainAwareKiteStrategy` (configurable via `quarkmind.tactics.kite.strategy` property)

### Enemy Strategy Classifier

`PatternClassifier` invokes `PatternClassificationRuleUnit` with Drools CEP rules. Rules are race-guarded to prevent false positives (e.g., `PROTOSS_CANNON_RUSH` cannot match in non-Protoss matchups). `ConfidenceRevision` applies temporal decay, counter-indication suppression, and multi-archetype publishing. Classification accuracy calibrated at >= 70% acceptance criterion against IEM10 replay dataset.

### Dominance Assessment Pipeline

1. `MultiFactorDominanceAssessor` computes per-factor scores: economy, tech, army value, base count
2. `DominanceWeightStrategy` SPI resolves factor weights per game phase and situation
3. `DroolsDominanceWeightStrategy` uses DRL rules (`DominanceWeightAdjustment.drl`) for weight resolution
4. `WeightModifier` records enable rule-based weight adjustments with `WeightContext` (pattern assessments, phase, army composition)

### Coach Mode Pipeline

Active in `%coach` profile. Human plays; AI observes and advises.

1. `CoachingTriggerBuilder` detects coaching-worthy moments from game state changes
2. `CoachingSessionSelector` picks appropriate coaching agent based on `CoachingUrgencyTier` and disposition
3. `CoachingWorkerFactory` creates LLM workers with game state context + `StrategyTaxonomy` counter data
4. `CoachingComplianceEvaluator` verifies compliance with position-based predicates:
   - `ArmyCentroidMovement` -- did army move in advised direction?
   - `ExpansionPlacement` -- was expansion built at advised location?
   - `UnitsNearLocation` -- are units positioned near advised location?
5. `CoachingEffectivenessTrustRecorder` writes trust attestations based on compliance verification outcomes

### Commentary System

Dual-pattern narration: reactive (immediate game event commentary) and narrative (contextual story-arc narration). `CommentaryTriggerBuilder` generates triggers; `CommentaryAccumulator` manages output; `NarrativeContextHolder` maintains cross-tick narrative state. Two `CommentaryType` modes with separate output keys.

### Hierarchical Event Summarisation

Four-level temporal abstraction pipeline:

1. **Raw ticks** -- per-tick game state
2. **Moments** -- `MomentDetectionTask` (Drools rule unit) detects significant events (`GameMoment` with `GameMomentType`); `MomentBroker` publishes to `MomentConsumer` subscribers
3. **Phases** -- `GamePhaseSummariser` aggregates moments into phase summaries
4. **Arcs** -- `GameArcSummariser` detects narrative arcs across phases

Pre-positioned for `casehub-blocks` migration.

## Emulation Engine

### Engine Architecture

`EmulatedGame` is a physics referee: holds `PlayerState friendly` + `PlayerState enemy`; both players share the same `applyIntent(Intent, PlayerState)` and `IntentQueue` drain path. Race-specific mechanics are fully delegated to a `RaceModel` plugin.

| Component | Role |
|-----------|------|
| `EmulatedGame` | Central physics simulation; `tick()` auto-computes per-base worker counts, mineral income, combat resolution |
| `EmulatedEngine` | `SC2Engine` CDI bean for `%emulated` profile |
| `PlayerState` | Per-player mutable state: units, buildings, stagingArea, minerals, supply, pendingCompletions, buildingCompletionAtLoop |
| `PlayerBehavior` | Interface: `tick(GameState, IntentQueue)` -- drives decisions for one player per tick |
| `EnemyBehavior` | `implements PlayerBehavior`; owns production loop, tech-tree gating, attack launch, retreat, strategy switching |
| `RaceModel` | Plugin seam: `seedInitialState`, `tickPassive`, `canProduce` (returns `ProductionDecision`: PROCEED/BLOCKED), `onProductionCommitted`, `onUnitSpawned`, `onCalldown`, `trainCount` |
| `ProtossRaceModel` | Seeds 12 Probes + NEXUS; all production methods no-op |
| `TerranRaceModel` | Seeds 12 SCVs + CC; MULE calldown via `MuleCalldownIntent`, expiry + flat income bonus |
| `ZergRaceModel` | Seeds 12 Drones + HATCHERY + OVERLORD; larva regen (245-loop), EGG morphing, Queen energy + auto-inject |
| `EnemyStrategyLibrary` | Static registry of 9 named strategies (3 per race) + `REACTIVE`; `forName(String)` returns fresh instance |
| `FixedBuildOrderStrategy` | Loops a fixed `List<UnitType>`, never switches |
| `ReactiveStrategy` | Re-evaluates every N frames, counter-picks based on dominant friendly unit type |
| `TechTree` | Prerequisite graph for all 3 races; `canTrain(unit, builtSet)` and `nextRequired(unit, builtSet)` |
| `DamageCalculator` | Combat damage computation with armour and Hardened Shield |
| `VisibilityGrid` | Per-tile visibility tracking (`TileVisibility`); fog of war |
| `PhysicsState` | Physics simulation state container |

### Combat Model

Two-pass simultaneous combat resolution prevents order-dependency:
1. **Collect phase**: for every unit (friendly and enemy), if an opponent is within weapon range, accumulate effective damage. No explicit `AttackIntent` required -- units fire automatically at the nearest in-range opponent each tick (auto-engage).
2. **Apply phase**: subtract from health (shields first for Protoss), remove units at HP <= 0.

### Emulation Stages (E1-E6 Complete)

| Stage | What | Status |
|---|---|---|
| E1 | Unit movement, probe mining, build times, infrastructure | Complete |
| E2 | Vector-based movement, scripted enemy wave, cost deduction, live config panel | Complete |
| E3 | Shields, two-pass simultaneous combat, damage tables, unit death | Complete |
| E4 | Enemy active AI -- symmetric `PlayerState` x 2, `EnemyBehavior`, 9 strategies + REACTIVE, `TechTree` | Complete |
| E5 | Pathfinding + terrain -- A* on tile map, terrain-aware edge costs (RAMP=1.5x), sub-tile LOS path smoothing | Complete |
| E6 | Building collision -- circular footprints; `SC2Data.buildingRadius(BuildingType)`; entry-only semantics | Complete |

### EmulatedSC2Server

SC2 protocol wrapper over `EmulatedGame` for `%emulated-sc2` profile. Bidirectional translators:
- `GameStateToProtobuf` -- converts `EmulatedGame` state to SC2 `ResponseObservation`
- `ProtobufToIntent` -- converts SC2 protobuf actions to `Intent` objects

Enables full agent stack testing against emulated physics using the real SC2 protocol -- without a live SC2 binary.

## Replay Infrastructure

| Component | Role |
|-----------|------|
| `ReplayEngine` | `SC2Engine` for `%replay` profile -- observe-only, records agent intents |
| `ReplaySimulatedGame` | Replay-driven variant driven from real `.SC2Replay` tracker events (PlayerStats, UnitBorn, UnitDied, UnitInit, UnitDone) |
| `GameEventStream` | Thin MPQ reader: `events(Path) -> List<Event>` |
| `AbilityMapping` | Stateful `CmdEvent` -> `Intent` translator; owns selection state per player |
| `ReplayCommandExtractor` | Orchestrates `GameEventStream` + `AbilityMapping` -> `ReplayCommandStream` |
| `ReplayValidationHarness` | Per-tick economic divergence comparison: replay ground truth vs `EmulatedGame` |
| `IEM10CommandExtractor` | Extracts `List<TimedIntent>` from SC2EGSet JSON `gameEvents` using IEM10-era abilLink constants |
| `DivergenceReport` | Per-tick divergence metrics |
| `UnitOrderTracker` | Tracks unit production orders across replay events |

**IEM10 validation:** Cross-validated across all 30 IEM10 games, providing statistical coverage across PvT, PvZ, PvP matchups. Calibration metrics: `firstUnitDivergenceTick >= 80`, `maxUnitDelta <= 2`.

## Visualizer

Three.js 3D visualizer renders game state each tick, served over WebSocket, wrapped in Electron.

| Component | Role |
|-----------|------|
| `GameStateBroadcaster` | `SC2Engine` frame listener; pushes JSON to all WebSocket clients per tick |
| `visualizer.js` | Three.js WebGL: 3D terrain, directional canvas-2D sprite textures, fog of war, health tinting, unit/building inspect panel, SC2-style and free-orbit camera |
| `visualizer.html` | Loads three.min.js + visualizer.js; no build step |
| `electron/main.js` | Spawns Quarkus as subprocess, health-polls, opens OS window |

**Renderer migration:** PixiJS 8 (E1-E13) replaced by Three.js (E14) for 3D orbiting camera, terrain height, directional sprite sheets.

**Canvas testing:** `window.__test` semantic API exposed from `visualizer.js` -- provides semantic assertions (sprite counts, positions, panel text, pixel samples) for Playwright E2E tests.

## Dependencies

### Depends On

```
quarkmind
  -> casehub-engine + casehub-engine-api      (CaseHub runtime, CaseContext, CaseDefinition)
  -> casehub-engine-ledger                    (TrustCandidateClassifier, TrustWeightedAgentStrategy)
  -> casehub-engine-planning                  (WritablePanelImpl, CaseContextImpl)
  -> casehub-engine-persistence-memory        (in-memory SPIs: CaseInstanceRepository, EventLogRepository, etc.)
  -> casehub-engine-scheduler-quartz          (JobScheduler SPI for engine runtime)
  -> casehub-qhorus + casehub-qhorus-api      (advisory channel; messaging persistence)
  -> casehub-ledger + casehub-ledger-api      (trust scoring, outcome recording)
  -> casehub-ledger-memory                    (in-memory trust store for game-loop speed)
  -> casehub-eidos + casehub-eidos-api + vocab (agent descriptors, disposition traits, ConscientiousnessTerm)
  -> casehub-eidos-memory                     (in-memory agent registry stubs)
  -> casehub-neocortex-memory-cbr-inmem       (in-memory CBR store for strategy learning)
  -> casehub-blocks                           (reusable summarisation framework)
  -> casehub-platform + casehub-platform-api  (identity, preferences, MockPreferenceProvider)
  -> Drools 10.1.0                            (rule-based reasoning: strategy, tactics, scouting, pattern classification, dominance weights, moment detection)
  -> Quarkus Flow 0.7.1                       (durable economics build order execution)
  -> ocraft-s2client-protocol 0.4.21          (SC2 protobuf types; no Vert.x/RxJava2 -- bot jar removed)
  -> LangChain4j                              (LLM integration for advisory/commentary/coaching)
  -> quarkus-smallrye-fault-tolerance          (SC2 connection resilience: @CircuitBreaker, @Retry, @Fallback)
  -> quarkus-messaging                         (SmallRye Reactive Messaging -- in-memory channel to EconomicsFlow)
  -> quarkus-jdbc-h2                           (H2 for qhorus named datasource)
  -> Playwright 1.49.0                         (E2E visualizer testing, test scope)
```

### Depended On By

None -- QuarkMind is a leaf application. No other CaseHub module depends on it.

## Testing

**828 unit/integration tests (default surefire run) + 288 Playwright E2E.**

- **Unit tests** (plain JUnit, no CDI): domain records, `SimulatedGame`, `EmulatedGame` (all race models), `IntentQueue`, `MockPipeline`, `ScenarioLibrary`, `GameStateTranslator`, `TerrainGrid`, `AStarPathfinder`, `PathfindingMovement`, `AbilityMapping`, `ReplayCommandExtractor`, `IEM10CommandExtractor`, `SC2TrainTimeCalibrationTest` (29 replays), `SC2BuildTimeCalibrationTest` (30 replays), coaching position verification, commentary accumulation, pattern classification, dominance weight calculation
- **Integration tests** (`@QuarkusTest`): `QaEndpointsTest`, `FullMockPipelineIT`, `AdaptivePluginSelectionIT`, `TrustWeightedStrategyIT`, `StrategyOutcomeRecordIT`, `LedgerAuditIT`, `FlowEconomicsTaskIT`, `DroolsScoutingTaskIT`, `DroolsTacticsTaskIT`, `AdvisoryIntegrationIT`, `CommentaryIntegrationIT`, `CoachingIntegrationIT`, `SummarisationPipelineIT`, `MomentBrokerIT`
- **Drools Rule Unit tests** (`@QuarkusTest` required -- `DataSource.createStore()` initialized at Quarkus build time): `DroolsStrategyTaskTest`, `DroolsTacticsRuleUnitTest`, `DroolsScoutingRulesTest`, `PatternClassificationRuleUnitTest`, `DominanceWeightRuleUnitTest`
- **Replay divergence report** (`@Tag("report")`, `mvn test -Preport`): `ReplayValidationReportTest` -- excluded from default run
- **Benchmark tests** (`@Tag("benchmark")`, `mvn test -Pbenchmark`): `EmulatedGameBenchmarkTest`, `GameLoopBenchmarkTest`; `TickTimings` record in `AgentOrchestrator` exposes per-phase breakdown
- **Playwright E2E tests** (288): sprite counts/positions/health tinting/death; panel inspect; pixel-colour sampling for minerals, geysers, creep; fog; `window.__test` semantic API
- **Pattern classification calibration**: `PatternClassificationCalibrationTest`, `ScoutingCalibrationTest` -- accuracy >= 70% acceptance criterion
- **IEM10 multi-game validation**: `IEM10MultiGameValidationTest` -- cross-validated across 30 games

**Testing rules:**
- Never use `@QuarkusTest` for tests that can be plain JUnit
- Exception: Drools Rule Unit tests require CDI (`DataSource.createStore()`)
- WebSocket tests: `java.net.http.WebSocket` (not Tyrus -- classloader conflict with Quarkus)

## Current State

**Emulation accuracy (post-Phase 6 calibration):**
- Sub-tick train timing via `TimedIntent` with `completesAt`; `firstUnitDivergenceTick >= 80`, `maxUnitDelta <= 2`; cross-validated across 30 IEM10 games
- Per-base probe mining with three-tier saturation model
- Multi-race support: Protoss, Terran (MULE), Zerg (larva/Queen/EGG)
- Parallel training queues per building with supply reservation
- Auto-engage combat; building collision with circular footprints
- A* pathfinding with RAMP edge costs and LOS string-pulling

**Harness layer:** Layers 1-7 complete. 12 LLM agent configurations. CBR strategy routing. 58-archetype strategy taxonomy with matchup-keyed counter data. Dynamic phase resolution. Milestone-based intra-game trust scoring.

**Classifier:** Drools CEP pattern detection with confidence revision (decay, counter-indication, multi-archetype publishing). Race-guarded rules. >= 70% accuracy against IEM10 dataset.

**Coaching:** Position-based compliance verification (army movement, expansion placement, unit positioning). Trust recording for coaching effectiveness.

**Commentary:** Dual-pattern narration (reactive + narrative) with narrative context tracking.

## Design Documents

- **`ARC42STORIES.MD`** (project root) -- permanent architecture record, sections 1-13 per the Arc42Stories CaseHub Profile. Covers the harness layer only; SC2 emulation is domain-specific.
- **`docs/adr/`** -- 9 architecture decision records (INDEX.md for full list):
  - ADR-0001: Quarkus Flow placement
  - ADR-0002: Two-pass simultaneous combat resolution
  - ADR-0003: `attackingUnits` Set semantics (superseded -- removed)
  - ADR-0004: Flow single `consume()` step for economics
  - ADR-0005: `SC2Data` shared constants in `domain/`
  - ADR-0006: `EmulatedGame`/`SimulatedGame` separation
  - ADR-0007: `RaceModel` plugin seam
  - ADR-0008: `RaceModel.canProduce` readonly and MULE intent extraction
  - ADR-0009: EigenTrust skip single attestor
- **`docs/DESIGN.md`** -- comprehensive design document
- **`docs/plugin-guide.md`** -- plugin developer guide
- **`docs/running.md`** -- all run modes, REST API reference, scenarios
- **`docs/specs/`** -- design specs for individual features

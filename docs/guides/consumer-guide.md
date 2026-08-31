# quarkmind -- Consumer Guide

> StarCraft II game AI application and CaseHub agentic harness living lab.

**GitHub:** [casehubio/quarkmind](https://github.com/casehubio/quarkmind)
**Tier:** Application -- Living Lab

---

## Purpose

QuarkMind is a Quarkus application that plays StarCraft II (Protoss) as a plugin platform. It coordinates plugin agents (strategy, economics, tactics, scouting) via CaseHub's case engine and blackboard, with Drools for rule-based reasoning and Quarkus Flow for durable task execution.

The application serves as a living lab -- a testbed for CaseHub, Drools, and Quarkus Flow integration patterns in a real-time domain. The SC2 game loop is domain-specific; the CaseHub harness underneath (CaseFile blackboard, plugin coordination, adaptive agent selection, trust-weighted routing, CBR experience learning) uses the same foundation as AML, clinical, and devtown.

Its primary value in the application family is as a **proof of generality**: the same harness pattern that coordinates AML investigation specialists and clinical trial monitors also coordinates real-time game AI agents -- without changing the foundation. QuarkMind demonstrates that the harness holds outside regulated enterprise domains, across diverse timing characteristics (game AI operates at millisecond tick granularity vs days for case management).

**Note:** Transferred from `mdproctor/quarkmind` to `casehubio/quarkmind`. A personal project using the CaseHub pattern -- does not participate in the casehubio CI pipeline.

## Module Structure

QuarkMind is a single-module Quarkus application (`quarkmind-agent`). Key structural areas:

| Area | What it covers |
|------|---------------|
| `domain/` | SC2 domain model -- game state, units, buildings, actions, intents; `SC2Data` (all game constants); `StrategyArchetype` (58 archetypes across all 3 races and 3 phases); `TechTree` prerequisite graph; `PhaseResolver` |
| `agent/` | CaseHub intelligence layer -- `QuarkMindCaseFile` keys, `GameStateTranslator`, `AgentOrchestrator`, `QuarkMindCaseHub`, `TickOrchestratorWorker` |
| `agent/plugin/` | Plugin seam interfaces -- `StrategyTask`, `EconomicsTask`, `TacticsTask`, `ScoutingTask`; scouting intel types (`ScoutingIntelType`, `ScoutingIntelPayload`, `ScoutingIntelConsumer`) |
| `agent/cbr/` | CBR strategy routing -- `SC2StrategyRouterTask`, `SC2GameCbrCase`, `SC2CbrRetentionObserver`, `SC2ImplementationRoutingStrategy` |
| `plugin/` | Active plugin implementations -- Drools and Flow-based |
| `plugin/advisory/` | LLM advisory team -- `AdvisoryWorkerFactory`, `AdvisoryChannelBroker`, `QuarkMindAgentRegistrar` (12 eidos agent descriptors) |
| `plugin/coaching/` | Coach mode -- `CoachingWorkerFactory`, `CoachingTriggerBuilder`, `CoachingComplianceEvaluator`, position-based verification |
| `plugin/commentary/` | Commentator mode -- `CommentaryWorkerFactory`, `CommentaryAccumulator`, dual-pattern narration (reactive + narrative) |
| `plugin/scouting/` | Drools CEP scouting -- `DroolsScoutingTask`, `CascadingPatternClassifier`, `ConfidenceRevision`, enemy strategy detection |
| `plugin/tactics/` | GOAP planning -- `GoapPlanner`, focus fire strategies (`FocusFireStrategy`, `LowestHpFocusFireStrategy`, `OverkillRedirectFocusFireStrategy`), kite strategies (`KiteStrategy`, `DirectKiteStrategy`, `TerrainAwareKiteStrategy`) |
| `plugin/summarisation/` | Hierarchical event summarisation -- `MomentDetectionTask`, `GamePhaseSummariser`, `GameArcSummariser`; four-level temporal abstraction |
| `plugin/flow/` | Quarkus Flow economics -- `EconomicsFlow`, `FlowEconomicsTask`, `EconomicsDecisionService`, `GameStateTick` |
| `plugin/drools/` | Drools Rule Units -- `StrategyRuleUnit`, `TacticsRuleUnit`, `DominanceWeightRuleUnit`; 6 DRL rule files |
| `sc2/` | SC2 engine seam -- `SC2Engine`, `IntentQueue`, events, sealed `Intent` interface, `SC2WebSocketCodec` |
| `sc2/intent/` | Sealed `Intent` hierarchy -- `BuildIntent`, `TrainIntent`, `AttackIntent`, `MoveIntent`, `BlinkIntent`, `MuleCalldownIntent`; `TimedIntent` (intent tagged with absolute game loop) |
| `sc2/real/` | Live SC2 -- `QuarkusSC2Transport`, `RealSC2Engine`, `SC2BotAgent`, `ObservationTranslator`, `ActionTranslator` |
| `sc2/emulated/` | Full physics simulation -- `EmulatedGame`, `EmulatedEngine`, `RaceModel` plugin seam (Protoss/Terran/Zerg), `EnemyBehavior`, `VisibilityGrid` |
| `sc2/emulated/server/` | `EmulatedSC2Server` -- SC2 protocol wrapper over `EmulatedGame` with bidirectional translators (`GameStateToProtobuf`, `ProtobufToIntent`) |
| `sc2/mock/` | Mock engine -- `SimulatedGame`, `MockEngine`, scenarios |
| `sc2/replay/` | Replay engine -- `ReplayEngine`, `ReplayCommandExtractor`, `ReplayValidationHarness`, `GameEventStream`, `AbilityMapping` |
| `sc2/map/` | Map terrain extraction -- `MapDownloader`, `SC2MapCache`, `SC2MapTerrainExtractor` |
| `qa/` | QA REST endpoints -- dev/test only (`@UnlessBuildProfile("prod")`) |
| `visualizer/` | `GameStateBroadcaster` (WebSocket push), `SpriteProxyResource` (Liquipedia CORS proxy) |
| `electron/` | Electron wrapper -- `main.js` spawns Quarkus as subprocess |

## Key Consumer APIs

### Plugin Seam Interfaces

Each plugin extends QuarkMind's `TaskDefinition` interface (package `io.quarkmind.agent`):

- **`StrategyTask`** -- high-level strategic decisions (expand, attack, defend)
- **`EconomicsTask`** -- build order execution and resource management
- **`TacticsTask`** -- unit micro and combat engagement
- **`ScoutingTask`** -- intelligence gathering and threat assessment

`TaskDefinition` provides: `getId()`, `getName()`, `requires()` (CaseFile key prerequisites), `activateIf()` (CDI-injected runtime gate), `execute(CaseContext)`, `produces()` (documentation only), and `testActivation(CaseContext)`.

### Domain Types

- **`QuarkMindCaseFile`** -- all CaseFile key constants (30 keys across observation state, resource budget, agent strategy, intel, commentary, and coaching triggers); never use raw string keys
- **`Intent`** (sealed interface) -- exhaustive set of game actions: `BuildIntent`, `TrainIntent`, `AttackIntent`, `MoveIntent`, `BlinkIntent`, `MuleCalldownIntent`; switch exhaustiveness enforced at compile time
- **`TimedIntent`** -- an `Intent` tagged with its absolute game loop for sub-tick completion precision
- **`SC2Data`** -- all game constants: damage-per-tick, attack range, supply cost, shield values, building health, mineral costs, train times (calibrated from 29 AI Arena replays), build times (calibrated from 30 replays), mineral income (three-tier saturation model per base), unit costs (`UnitCosts` exhaustive `EnumMap`), building radii
- **`GameState`** -- per-tick snapshot: minerals, vespene, supply, unit lists, enemy buildings, mineral patches, geysers, game frame
- **`StrategyArchetype`** -- 58-entry enum across all 3 races (Protoss/Terran/Zerg) x 3 phases (EARLY/MID/LATE) x 6 categories (RUSH/TIMING/HARASS/MACRO/TECH/COMPOSITION)
- **`PatternAssessment`** -- archetype classification with confidence score, detection frame, and rationale
- **`DominanceScore`** -- multi-factor dominance assessment (overall score + per-factor breakdown)
- **`GamePhase`** -- enum: EARLY, MID, LATE
- **`PhaseResolver`** -- interface for game phase detection; `StateBasedPhaseResolver` uses supply, expansion count, tech tier, and time thresholds
- **`TechTree`** -- prerequisite graph for all three races; `canTrain(unit, builtSet)` and `nextRequired(unit, builtSet)`
- **`Race`** -- enum: PROTOSS, ZERG, TERRAN

### SC2 Engine Seam

`SC2Engine` is the unified CDI interface for all game modes. Methods: `connect()`, `joinGame()`, `leaveGame()`, `isConnected()`, `tick()`, `observe()` (returns `GameState`), `dispatch()`, `lastOutcome()`, `addFrameListener()`, `getMapName()`, `getMapWidth()`, `getMapHeight()`.

Four implementations:
- `MockEngine` -- `SimulatedGame`-backed; clock owned by the engine
- `EmulatedEngine` -- physics simulation with `EmulatedGame`
- `RealSC2Engine` -- `QuarkusSC2Transport` virtual-thread loop; clock owned by transport
- `ReplayEngine` -- observe-only against `.SC2Replay` files

### Scouting Intel System

`ScoutingIntelBroker` publishes intel to `quarkmind-scouting-intel` via `casehub-qhorus`. Intel types defined in `ScoutingIntelType`: enemy army composition, threat position, build order detection, pattern assessments. Consumers implement `ScoutingIntelConsumer` with `ScoutingIntelPreferences` to declare interest.

### LLM Advisory and Coaching

`QuarkMindAgentRegistrar` registers 12 `AgentDescriptor` configurations with eidos disposition traits:

- **Advisory agents (6):** Crisis (aggressive + conservative), Strategic (bold + measured), Economic (expansion + defensive)
- **Commentary agents (4):** Reactive commentator (energetic + analytical), Narrative narrator (dramatic + tactical)
- **Coaching agents (2):** Directive coach + Socratic coach

All use Claude Sonnet 4 via LangChain4j. `DispositionAwareRoutingStrategy` composes trust classification with game-context disposition scoring.

### CBR Strategy Routing

`SC2StrategyRouterTask` integrates case-based reasoning for strategy selection via `casehub-neocortex` CBR. `SC2GameCbrCase` captures game state features; `SC2CbrRetentionObserver` records outcomes for learning across games.

### Quarkus Profiles

| Profile | SC2 needed | Purpose |
|---------|-----------|---------|
| `%mock` (default) | No | Development and unit testing against `SimulatedGame`; auto-starts on boot |
| `%emulated` | No | Physics simulation with `EmulatedGame`; Three.js visualizer active |
| `%emulated-sc2` | No | Full-stack testing -- SC2 protocol over `EmulatedGame` via `EmulatedSC2Server` |
| `%replay` | No | Agent loop against a real `.SC2Replay` file; observe-only, intents recorded not applied |
| `%sc2` | Yes | Real SC2 integration via `QuarkusSC2Transport` |
| `%coach` | No | Human plays, AI observes and advises via coaching pipeline |
| `%test` | No | Same as mock; scheduler disabled |
| `%prod` | -- | QA endpoints stripped (`@UnlessBuildProfile("prod")`) |

### QA REST API

Available in all non-prod profiles. Key endpoints:

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/sc2/casefile` | GET | Current game state -- minerals, supply, units, buildings |
| `/sc2/intents/dispatched` | GET | What the agent decided this tick |
| `/sc2/intents/pending` | GET | Pending intents in the queue |
| `/sc2/frame` | GET | Frame counter and connection status |
| `/sc2/debug/scenario/{name}` | POST | Trigger a named test scenario |
| `/sc2/start` | POST | Start game loop |
| `/sc2/stop` | POST | Stop game loop |

### Quick Start

Prerequisites: Java 21+, Maven 3.9+, CaseHub installed to local Maven repo.

```bash
# Mock mode (no SC2 needed)
mvn quarkus:dev

# Replay mode
mvn quarkus:dev -Dquarkus.profile=replay

# Emulated mode (physics + visualizer)
mvn quarkus:dev -Dquarkus.profile=emulated

# Real SC2
mvn quarkus:dev -Dquarkus.profile=sc2
```

## Dependencies

```
quarkmind
  -> casehub-engine + casehub-engine-api    (CaseFile blackboard, CaseHub runtime, Worker dispatch)
  -> casehub-engine-ledger                  (TrustCandidateClassifier, trust-weighted routing)
  -> casehub-engine-planning                (WritablePanelImpl, CaseContextImpl)
  -> casehub-engine-persistence-memory      (in-memory SPIs for game-loop ticks)
  -> casehub-engine-scheduler-quartz        (JobScheduler SPI)
  -> casehub-qhorus + casehub-qhorus-api    (advisory channel for LLM observers)
  -> casehub-ledger + casehub-ledger-api    (trust-weighted strategy routing)
  -> casehub-ledger-memory                  (in-memory trust store)
  -> casehub-eidos + casehub-eidos-api      (agent descriptors, disposition traits)
  -> casehub-eidos-vocab                    (ConscientiousnessTerm vocabulary)
  -> casehub-eidos-memory                   (in-memory agent registry stubs)
  -> casehub-neocortex-memory-cbr-inmem     (in-memory CBR for strategy learning)
  -> casehub-blocks                         (reusable summarisation framework)
  -> casehub-platform + casehub-platform-api (identity, preferences)
  -> Drools 10.1.0                          (rule-based strategy, tactics, scouting, pattern classification, dominance weights)
  -> Quarkus Flow 0.7.1                     (durable economics build order execution)
  -> ocraft-s2client-protocol 0.4.21        (SC2 protobuf types)
  -> LangChain4j                            (LLM integration for advisory/commentary/coaching)
  -> Playwright 1.49.0                      (E2E visualizer testing, test scope)
```

## Chat Agency System (quarkmind-chat)

Multi-character AI orchestration for Discord-based social gameplay.

### ChatAgencyLoop

Core orchestration loop managing multi-character AI agents in Discord channels. Stateless design with `CharacterContext` per character (thread-safe via `ConcurrentHashMap.newKeySet()` for `participatedThreadIds`). Bounded buffers for message history and identity detection.

### Chat Protocol

- `ChatIntent` sealed interface + `ChatPerception` + `WakeReason` — core chat abstractions
- `AttentionClassifier`, `ChatDeltaReport`, `OutputGovernor`, `ProactiveDecisionGate` — perception pipeline
- `ChatNeedDefinitions` + `ChatChannelPacing` — configurable need thresholds and channel timing
- `ChatObservationRenderer` + `ChatWorldBridge` — Discord adapter layer

### Character Management

`ChatCharacterManager` orchestrates multiple AI characters with distinct personalities.

### Personality Evolution

Personalities evolve based on gameplay reflections:
- `ReflectionDispositionActivator` SPI + `PersonalityEvolutionPipeline`
- `LlmReflectionDispositionActivator` — async LLM-based trait classification
- `DispositionAwareReflectionSynthesizer` — intercepts insights for activation

### Memory Integration

- `ChatMemoryFacade` — recall, ingest, `scoreImportance`
- `LlmReflectionSynthesizer` — LLM-based memory consolidation from heartbeat reflections
- `IdleReflectionTrigger` — triggers reflection during idle periods
- Commentary-CBR integration for narrating learning from past games

### Blocks-UI Migration

Workbench migrated to Lit web components via blocks-ui integration. Commentary pipeline and Playwright test suite rewritten for the new component architecture.

---

## What It Does NOT Do

Everything below belongs in the foundation:

- Trust scoring computation (casehub-ledger)
- Agent disposition personality model (casehub-eidos)
- CBR case memory storage (casehub-neocortex)
- Commitment lifecycle (casehub-qhorus)
- Event summarisation framework (casehub-blocks)
- Human task inbox (casehub-work -- not applicable at game AI tick granularity)

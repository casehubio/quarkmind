# quarkmind — Contributor Guide

> Internals, architecture, and extension points for quarkmind platform contributors.

**GitHub:** [casehubio/quarkmind](https://github.com/casehubio/quarkmind)

---

## Module Structure

### What It Owns

- **SC2 domain model:** game state, units, buildings, actions, intents; `SC2Data` — all game constants (costs, timings, ranges, armour, attributes)
- **Plugin seam interfaces:** `StrategyTask`, `EconomicsTask`, `TacticsTask`, `ScoutingTask` — each extends CaseHub's `TaskDefinition`
- **Active plugin implementations:** `DroolsStrategyTask`, `FlowEconomicsTask`, `DroolsTacticsTask`, `DroolsScoutingTask`; competing strategy implementations (L6): `EarlyPressureStrategyTask`, `EconomicExpansionStrategyTask`
- **`QuarkMindCaseFile`** — all CaseFile key constants; never use raw string keys
- **SC2 engine seam:** `IntentQueue`, `GameStarted`/`GameStopped` events, sealed `Intent` interface (switch exhaustiveness at compile time)
- **Mock, emulated, replay, and real SC2 profiles**
- **`EmulatedGame`** — full physics simulation: probe-driven mining (per-base, saturation model), parallel training queues, sub-tick train timing, building cost deduction, vespene harvesting, combat (damage, armour, Hardened Shield), blink mechanics, auto-engage, enemy AI (`EnemyBehavior`, `TechTree`, `ReactiveStrategy`)
- **`ReplayValidationHarness`** — replay ground truth vs `EmulatedGame` per-tick economic divergence
- **`IEM10CommandExtractor`** — extracts `List<TimedIntent>` from SC2EGSet JSON `gameEvents` using IEM10-era abilLink constants
- **`TerrainGrid`** (HIGH/LOW/RAMP/WALL height model), `AStarPathfinder`, `MovementStrategy`
- **Three.js 3D visualiser:** 65+ unit/building sprites across all 3 races, fog of war, terrain shading, click-to-inspect panel, replay scrub control, Electron wrapper
- **Hierarchical event summarisation:** generic framework (`io.casehub.blocks.summarisation`) with four-level temporal abstraction: raw ticks, intel, moments, phases, arcs. Pre-positioned for `casehub-blocks` migration.
- **LLM advisory team:** 6 `AgentDescriptor` configurations with eidos disposition traits. `DispositionAwareRoutingStrategy`, `AdvisoryWorkerFactory`, multi-dimensional trust scoring.
- **EmulatedSC2Server:** SC2 protocol wrapper over `EmulatedGame` — bidirectional translators, walkability bitmap encoding, full `ResponseObservation` round-trip.

### Code Organisation

```
src/main/java/io/quarkmind/
  domain/              Plain Java records — no framework deps
  sc2/                 SC2Engine seam — IntentQueue, events, sealed Intent, SC2WebSocketCodec
  sc2/real/            Live SC2 — QuarkusSC2Transport, SC2BotAgent, ObservationTranslator
  sc2/emulated/server/ EmulatedSC2Server — SC2 protocol wrapper over EmulatedGame
  sc2/mock/            Mock SC2 — SimulatedGame, MockGameObserver, MockCommandDispatcher
  sc2/mock/scenario/   ScenarioLibrary — living specification of SC2 behaviour
  agent/               CaseHub intelligence layer — QuarkMindCaseFile, AgentOrchestrator
  agent/plugin/        Plugin seam interfaces (StrategyTask, EconomicsTask, TacticsTask, ScoutingTask)
  agent/cbr/           CBR — SC2GameCbrCase, SC2CbrRetentionObserver, SC2StrategyRouterTask
  plugin/              Active plugin implementations (DroolsStrategyTask, FlowEconomicsTask, etc.)
  plugin/scouting/     Drools CEP scouting — DroolsScoutingTask, event records
  plugin/tactics/      GOAP planning + CDI strategy interfaces
  plugin/coaching/     Coach mode — CoachingTriggerBuilder, CoachingWorkerFactory
  plugin/flow/         Quarkus Flow — EconomicsFlow, EconomicsDecisionService
  qa/                  QA REST endpoints — dev/test only (@UnlessBuildProfile("prod"))
```

## Internal Architecture

### Agentic Harness Structure

| Layer | CaseHub primitive | QuarkMind expression |
|-------|-------------------|---------------------|
| Agent coordination | `casehub-engine` CaseFile blackboard | `AgentOrchestrator` dispatches plugins via case engine per tick |
| Plugin tasks | `TaskDefinition` | `StrategyTask`, `EconomicsTask`, `TacticsTask`, `ScoutingTask` |
| Adaptive selection | Binding conditions | Plugin selection based on game state in CaseFile |
| Durable execution | Quarkus Flow | `FlowEconomicsTask` — build order execution with retry |
| Rule-based reasoning | Drools | `DroolsStrategyTask`, `DroolsTacticsTask`, `DroolsScoutingTask` |
| Typed advisory channel | `casehub-qhorus` | `ScoutingIntelBroker` publishes to `quarkmind-scouting-intel`; LLM advisors subscribe as `MessageObserver` |
| Trust-weighted routing | `casehub-ledger` | `StrategyTrustRouter` — four-phase Bayesian Beta maturity; `GameOutcomeRecorder` writes trust attestations |

### Layer Taxonomy

The layered structure applies to the agentic harness — not to the SC2 emulation layer, which is domain-specific.

| Layer | Adds | Status |
|-------|------|--------|
| 1 | Naive game loop — direct plugin calls, no CaseHub | complete (conceptual) |
| 2 | casehub-engine blackboard — shared state between plugins | complete |
| 3 | casehub-qhorus — typed inter-plugin communication (dual-stack) | complete |
| 4 | casehub-ledger — audit trail for agent decisions | complete |
| 5 | Adaptive plugin selection — binding conditions | complete |
| 6 | Trust routing — `StrategyTrustRouter` four-phase Bayesian Beta | complete |
| 7 | Comparison vs naive game AI | complete |

### casehub-engine Phase 2 Migration

`QuarkMindCaseHub extends CaseHub` with `signalAndAwaitSync` per tick. `TickOrchestratorWorker` chains plugins via `WorkerFunction.Sync`. `MutableMapCaseContext` provides writable context with delta tracking. `casehub-poc` dependency removed.

## Dependencies

### Depends On

```
quarkmind
  → casehub-engine            (CaseFile blackboard, TaskDefinition, adaptive plugin dispatch)
  → casehub-persistence-memory (in-memory store for fast game-loop ticks)
  → casehub-qhorus            (advisory channel for LLM observers; persistence-memory for @QuarkusTest isolation)
  → casehub-ledger            (L6: trust-weighted strategy routing — core only, not engine-ledger)
  → Drools                    (rule-based strategy, tactics, scouting)
  → Quarkus Flow              (durable economics build order execution)
```

### Depended On By

None — QuarkMind is a leaf application. No other CaseHub module depends on it.

## Current State

828 unit/integration tests passing (288 Playwright E2E excluded from default surefire run).

**Emulation accuracy (post-Phase 6 calibration):**
- Sub-tick train timing: `TimedIntent` with `completesAt` derived from `SC2Data.trainTimeInLoops` (integer-loop rounding calibrated from replays); `firstUnitDivergenceTick >= 80`, `maxUnitDelta <= 2`; cross-validated across all 30 IEM10 games
- Per-base probe mining: saturation model + per-base `miningProbesPerBase` auto-computed in `tick()`
- Vespene income: synced from ground truth for gas-unit training in `ReplayValidationHarness`
- Parallel training queues: per-building queues with supply reservation
- Auto-engage: all units fire at enemies in range without explicit `AttackIntent`
- Enemy AI: `EnemyBehavior` with `TechTree` prerequisite gating and `ReactiveStrategy`

**Visualiser:**
- Three.js 3D terrain with height shading, fog of war, mineral patches, geysers
- 65+ canvas sprites across all 3 races; directional facing, team colour decals
- Click-to-inspect unit/building panel; HP/shield bars; replay scrub control
- 288 Playwright end-to-end tests

**Harness layer:** Layers 1-7 complete. `AgentOrchestrator` dispatches plugins via `casehub-engine` CaseFile per tick.

**IEM10 JSON validation:** `IEM10CommandExtractor` enables `ReplayValidationHarness` runs across all 30 IEM10 games, providing statistical coverage across PvT, PvZ, PvP matchups.

## Design Documents

- **`ARC42STORIES.MD`** (project root) — permanent architecture record, sections 1-13 per the Arc42Stories CaseHub Profile. Covers the harness layer only; SC2 emulation is domain-specific and outside scope.
- **`docs/adr/`** — architecture decision records (ADR-0001: Quarkus Flow placement)
- **`docs/DESIGN.md`** — design document

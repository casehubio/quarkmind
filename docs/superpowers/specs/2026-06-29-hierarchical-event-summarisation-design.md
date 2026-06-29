# Hierarchical Event Summarisation — Design Spec

**Issue:** quarkmind#182
**Date:** 2026-06-29
**Status:** Approved (revised after adversarial review round 1)

## Overview

A temporal abstraction layer that promotes raw game-tick data through four levels of increasing semantic richness. The generic hierarchy framework lives in a package pre-positioned for migration to `casehub-blocks`; the SC2 bindings stay in quarkmind.

```
Level 0 — Raw tick stream (22.4/sec)
Level 1 — Intel events (existing DroolsScoutingTask CEP)
Level 2 — Moments (new — Drools CEP, event-driven)
Level 3 — Game phases (new — periodic batch summarisation)
Level 4 — Narrative arc (new — periodic batch summarisation)
```

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Scope | All four levels with pluggable `Summariser` + deterministic stubs | Proves full hierarchy shape without blocking on LLM infra |
| Package split | `io.casehub.blocks.summarisation` (generic) / `io.quarkmind.plugin.summarisation` (SC2) | Pre-positions generic layer namespace for IntelliJ-assisted migration to blocks |
| Transition interface | Single `Summariser<IN, OUT>` for L2→4 only | Drools CEP (L0→2) has a fundamentally different execution model — forcing it behind `Summariser` would be a leaky abstraction |
| Broker | New `MomentBroker` alongside existing `ScoutingIntelBroker` | Latest-value (L1) vs event-stream (L2+) are different access patterns |
| Windowing | Dual-trigger `WindowPolicy` — timestamp OR event-count | Catches burst scenarios; both thresholds optional for degenerate cases |
| Pub/sub | Callback registration in generic layer, CDI bridge in SC2 layer | Generic layer must be CDI-free for blocks (plain JUnit tests) |
| L1→L2 data flow | DroolsScoutingTask publishes change events to `EventStreamBus` alongside existing broker update | MomentDetectionTask needs the temporal stream of Level 1 transitions, not just latest values |
| Synchronous Summariser | Phase 1 constraint — `summarise()` is synchronous | Deterministic stubs are sub-millisecond. LLM swap (future) will require async interface change — documented as a known migration. |
| Clock source | `WindowPolicy` uses `long` timestamps, caller supplies current value | Application decides timestamp semantics (game frames in SC2, epoch millis elsewhere) |

## Generic Layer — `io.casehub.blocks.summarisation`

Migrates to `casehub-blocks`. No CDI, no SC2 imports, plain Java.

### Types

| Type | Kind | Role |
|------|------|------|
| `EventLevel` | Record | `String name`, `int ordinal` — identifies a level in the hierarchy |
| `LevelEvent<E>` | Record | `E payload`, `long timestamp`, `EventLevel level` — a typed event at a specific level. Timestamp semantics are application-defined (game frames, epoch millis, etc.) |
| `WindowPolicy` | Record | `long maxAge`, `int maxCount` — dual-trigger: emit when either threshold is hit relative to the current timestamp passed to `shouldEmit(long now)`. Either field zero for single-trigger degenerate cases. Uses `long` (not `Duration`) because timestamp units are application-defined. |
| `EventAccumulator<E>` | Class | Collects `LevelEvent<E>`, tracks window state. `shouldEmit(long now)` checks dual-trigger against current timestamp. `drain()` returns and clears accumulated events. `clear()` resets all state (game lifecycle). Not thread-safe — all callers execute on the single game-tick thread. |
| `Summariser<IN, OUT>` | Interface | `List<OUT> summarise(List<LevelEvent<IN>> batch)` — the batch promotion contract. Returns raw payloads; `SummarisationRunner` wraps them in `LevelEvent` with the target level and current timestamp. Synchronous in Phase 1 (deterministic stubs). LLM swap will require an async interface change — this is a known future migration, not an oversight. |
| `EventStreamBus<E>` | Class | `subscribe(Predicate<E>, Consumer<LevelEvent<E>>)`, `publish(LevelEvent<E>)`, `clear()`. Predicate-based filtering, no enum coupling. Dispatches synchronously on the caller's thread. `clear()` removes all subscriptions (game lifecycle reset). Named "Bus" to distinguish from the latest-value "Broker" pattern used by `ScoutingIntelBroker`. |
| `EventConsumer<E>` | Interface | `Predicate<E> eventFilter()` — generic subscription contract for the CDI bridge pattern. Applications extend with domain-specific convenience (e.g., set-of-enum filtering). |
| `SummarisationRunner<IN, OUT>` | Class | Wires `EventAccumulator<IN>` to `Summariser<IN, OUT>` — on `tick(long now)`: checks `shouldEmit(now)`, calls `summarise(drain())`, publishes each result to the output `EventStreamBus<OUT>`. `clear()` delegates to the accumulator (game lifecycle). |

### Design Constraints

- `EventStreamBus` uses `Predicate<E>` for subscription filtering — domain-agnostic
- `EventAccumulator` owns windowing logic — `SummarisationRunner` just asks "ready?" and "give me the batch"
- `SummarisationRunner` is the only class that calls `Summariser`
- All types are records or interfaces — no abstract classes, no inheritance hierarchies
- No thread-safety in the generic layer — the game loop is single-threaded (`@Scheduled(concurrentExecution = SKIP)`)
- `EventConsumer<E>` is the generic CDI bridge contract; domain layers extend it with type-safe convenience

## SC2 Application Layer — `io.quarkmind.plugin.summarisation`

Stays in quarkmind permanently.

### Domain Types (in `io.quarkmind.plugin.summarisation`)

These are analysis outputs (agent interpretation), not game-world primitives. They live alongside the code that produces them, not in `io.quarkmind.domain` (which holds SC2 world-model types like `Unit`, `Building`, `GameState`).

| Type | Kind | Fields |
|------|------|--------|
| `GameMomentType` | Enum | `FIRST_CONTACT`, `BATTLE_STARTED`, `BATTLE_ENDED`, `SUPPLY_BLOCK`, `ECONOMIC_CRISIS`, `BUILDING_LOST`, `NEXUS_UNDER_ATTACK`, `SCOUT_LOST`, `TECH_TRANSITION_DETECTED` |
| `GameMoment` | Record | `GameMomentType type`, `long gameFrame`, `Map<String, Object> context` |
| `GamePhase` | Record | `String phase`, `long sinceFrame`, `String rationale` — phase is a String (not enum) for LLM compatibility: stubs use known values (`EARLY_MACRO`, `EARLY_AGGRESSION`, `MID_SKIRMISH`, `LATE_PUSH`, `ECONOMIC_DOMINANCE`, `DEFENSIVE_HOLD`, `TRANSITIONING`), LLM can produce novel ones |
| `GameArc` | Record | `String narrative`, `long generatedAt` |

### Seam Interface

Following the existing plugin pattern (`StrategyTask`, `ScoutingTask`, `EconomicsTask`, `TacticsTask`):

| Type | Location | Role |
|------|----------|------|
| `MomentDetectionSeam` | `agent/plugin/MomentDetectionSeam.java` | Seam interface extending `TaskDefinition`. Declares `requires()` and `produces()` for CaseEngine ordering. |

**requires():** `game.intel.enemy.units`, `agent.intel.enemy.posture`, `agent.intel.enemy.timing` — keys produced by `DroolsScoutingTask`. This guarantees CaseEngine orders moment detection after scouting.

**produces():** `agent.intel.moments.latest` — a serialised list of `GameMoment` events from this tick, written to CaseFile for downstream plugin consumption.

**TaskRegistrar entry:** `QuarkMindTaskRegistrar` gains `@Inject @CaseType("starcraft-game") MomentDetectionSeam momentDetectionTask`.

### Plugin/CDI Classes

| Class | Role |
|-------|------|
| `MomentDetectionRuleUnit` | Drools RuleUnit for L1→2 CEP. Input: `DataStore<ScoutingIntelPayload>` (Level 1 transition events accumulated since last tick). Output: `List<GameMoment>` (detected moments). |
| `MomentDetectionTask` | Implements `MomentDetectionSeam`. Subscribes to the Level 1 `EventStreamBus<ScoutingIntelPayload>` (see L1→L2 Data Flow below). Each tick: feeds accumulated L1 transition events into `MomentDetectionRuleUnit`, publishes resulting `GameMoment` instances to the Level 2 `EventStreamBus<GameMoment>`, writes to CaseFile key `agent.intel.moments.latest`. |
| `GamePhaseSummariser` | Implements `Summariser<GameMoment, GamePhase>`. Deterministic stub: classifies phase from moment patterns (multiple `BATTLE_STARTED` in 90s → `MID_SKIRMISH`; no combat + expanding → `EARLY_MACRO`; `NEXUS_UNDER_ATTACK` + high army → `DEFENSIVE_HOLD`). |
| `GameArcSummariser` | Implements `Summariser<GamePhase, GameArc>`. Deterministic stub: template-based narrative ("Bot holds economic advantage for N minutes, converting to army"). |
| `MomentBroker` | CDI bean. Owns the Level 2 `EventStreamBus<GameMoment>` instance. Owns Qhorus channel `quarkmind-moments` (see Qhorus Integration below). CDI bridge: discovers `MomentConsumer` beans via `@Any Instance<MomentConsumer>`, auto-registers each as a predicate callback on the bus. Observes `GameStarted` to clear bus and re-register subscribers. |
| `MomentConsumer` | CDI interface extending `EventConsumer<GameMoment>`. Adds `Set<GameMomentType> subscribedMomentTypes()`. Default method: `eventFilter()` returns `m -> subscribedMomentTypes().contains(m.type())`. |
| `SummarisationLifecycle` | CDI bean (`@ApplicationScoped`). Holds the two `SummarisationRunner` instances (L2→3, L3→4) and their `EventStreamBus` instances for Level 3 and Level 4. Provides `tick(long gameFrame)` called by `GameTickExecutor`. Observes `GameStarted` to call `clear()` on both runners and buses. |

### L1→L2 Data Flow

`ScoutingIntelBroker` is a latest-value store — `update()` overwrites, `current()` reads latest. Moment detection requires the temporal stream of Level 1 transitions (e.g., "posture changed from MACRO to ALL_IN at frame 3400").

**Solution:** `DroolsScoutingTask` gains one additional call per transition: alongside the existing `broker.update(payload)`, it also calls `level1Bus.publish(new LevelEvent<>(payload, gameFrame, LEVEL_1))`. The `EventStreamBus<ScoutingIntelPayload>` is owned by `MomentBroker` (injected into DroolsScoutingTask). MomentDetectionTask subscribes to this bus and accumulates transition events for its Drools CEP session.

This is minimal change to existing code — one new field (`EventStreamBus<ScoutingIntelPayload>`) and one new call per transition in `DroolsScoutingTask`. The existing latest-value broker, Qhorus advisory dispatch, and all current consumers are untouched.

### Tick Loop Integration

Current tick sequence in `GameTickExecutor.execute()`:

```
tick() → observe() → recordTick() → createAndSolve() → dispatch()
```

Modified sequence:

```
tick() → observe() → recordTick() → createAndSolve() → summarisationLifecycle.tick(gameFrame) → dispatch()
```

**Where each component runs:**

1. **DroolsScoutingTask** — inside `createAndSolve()` (existing). Publishes L1 transitions to `level1Bus`.
2. **MomentDetectionTask** — inside `createAndSolve()` (new plugin, ordered after scouting by `requires()`). Consumes L1 events from bus, fires Drools CEP, publishes L2 moments to `momentBus`.
3. **SummarisationLifecycle.tick(gameFrame)** — after `createAndSolve()`, before `dispatch()`. Ticks both `SummarisationRunner` instances (L2→3, L3→4). Runners check `shouldEmit(gameFrame)` and call their summarisers if triggered.

The runners execute outside `createAndSolve()` because they are not CaseEngine plugins — they consume event streams, not CaseFile state. This keeps the CaseEngine dispatch clean and avoids complicating `requires()`/`produces()` with summarisation outputs that don't belong in the CaseFile.

**Tick budget:** Deterministic stubs are sub-millisecond. The runners add negligible overhead to the P99 < 400ms tick budget. When LLM summarisers replace the stubs (future), the `Summariser` interface must become async — the runner would fire-and-forget to a separate thread and publish results on the next tick that receives them.

### Integration with DroolsStrategyTask

- `DroolsStrategyTask` additionally implements `MomentConsumer`
- `subscribedMomentTypes()` returns `{BATTLE_STARTED, BATTLE_ENDED, ECONOMIC_CRISIS, NEXUS_UNDER_ATTACK}` — the moments relevant to strategic decisions
- `StrategyRuleUnit` gains two new DataStore fields:
  - `DataStore<GameMoment> momentStore` — current-tick moments
  - `DataStore<GamePhase> phaseStore` — latest phase assessment (read from Level 3 bus or CaseFile)
- New strategy rules (examples):
  - "MID_SKIRMISH phase + ECONOMIC_DOMINANCE → accelerate army buildup" (salience 180)
  - "EARLY_AGGRESSION phase + NEXUS_UNDER_ATTACK → immediate DEFEND" (salience 220)
- Existing Level 1 rules (POSTURE, TIMING_ALERT) are untouched — additive only

### Game Lifecycle Management

All stateful components observe `@Observes GameStarted` and reset:

| Component | Reset action |
|-----------|-------------|
| `MomentBroker` | Calls `level1Bus.clear()` and `momentBus.clear()`, re-registers CDI-discovered consumers |
| `MomentDetectionTask` | Clears internal L1 event buffer, resets Drools session state |
| `SummarisationLifecycle` | Calls `clear()` on both `SummarisationRunner` instances (which clear their `EventAccumulator`) and on Level 3/4 `EventStreamBus` instances |

This prevents cross-game state leakage. Follows the same pattern as `ScoutingIntelBroker.onGameStarted()` and `DroolsScoutingTask`'s frame-backwards detection.

### Qhorus Channel Integration

| Property | Value |
|----------|-------|
| Channel name | `quarkmind-moments` |
| `ChannelSemantic` | `APPEND` (event history timeline) |
| `MessageType` | `STATUS` (carries content payload — `EVENT` forces null content per GE-20260607-d051f2) |
| Scope | Single channel for all summarisation levels (L2, L3, L4). Each message includes a `level` field in its JSON payload for filtering. |
| Sender | `summarisation.moment-broker` |
| `ActorType` | `AGENT` |

Channel creation follows the existing pattern: `@PostConstruct` with `QuarkusTransaction.requiringNew()` and idempotent `findByName()` + create fallback (same as `ScoutingIntelBroker` and `PluginDispatchBroker`).

### Scope Boundary

LLM advisory team (#180) and Commentator (#181) are out of scope for implementation. This issue delivers the broker infrastructure and `MomentConsumer` interface; #180 and #181 provide their own consumer implementations.

The #182 issue acceptance criteria that reference #180/#181 integration ("LLM advisors receive Level 2/3 context", "Commentator triggers on Level 2 moments") will be updated on the issue to defer those criteria to their respective issues. The remaining criteria — Level 2 moment production, Level 3/4 summarisation, DroolsStrategyTask integration, and the blog entry — are fully in scope.

## Testing Strategy

### Unit Tests (plain JUnit, no CDI)

| Test | Coverage |
|------|----------|
| `EventAccumulatorTest` | Window policy: timestamp trigger, count trigger, dual trigger (whichever first), drain clears buffer, clear resets state, shouldEmit with caller-supplied timestamp |
| `SummarisationRunnerTest` | Wiring: tick → shouldEmit → summarise → publish. No call when window not met. Clear delegates to accumulator. |
| `EventStreamBusTest` | Subscribe with predicate, publish matches, non-matches filtered, multiple subscribers, clear removes all subscriptions |
| `GamePhaseSummariserTest` | Deterministic stub: moment patterns → correct phase classification. Scenarios: battle-heavy → MID_SKIRMISH, quiet expansion → EARLY_MACRO, under attack → DEFENSIVE_HOLD |
| `GameArcSummariserTest` | Deterministic stub: phase sequences → correct narrative templates |
| `MomentDetectionTaskTest` | Drools CEP L1→2: feed Level 1 transition events, assert correct GameMoment output. Follows existing DroolsScoutingTaskTest pattern (plain JUnit, construct directly). |

### Integration Tests (`@QuarkusTest`)

| Test | Coverage |
|------|----------|
| `MomentBrokerIT` | CDI discovery of `MomentConsumer` beans, auto-registration on bus, Qhorus channel publication, GameStarted reset |
| `SummarisationPipelineIT` | Full tick: DroolsScoutingTask → level1Bus → MomentDetectionTask → momentBus → SummarisationRunner L2→3 → SummarisationRunner L3→4. Verifies end-to-end through the orchestrator including lifecycle reset between games. |

Generic layer tests use simple `String`/`Integer` event types — no SC2 dependencies.

## Package Layout

```
src/main/java/
  io/casehub/blocks/summarisation/       ← generic (migrates to blocks)
    EventLevel.java
    LevelEvent.java
    WindowPolicy.java
    EventAccumulator.java
    Summariser.java
    EventStreamBus.java
    EventConsumer.java
    SummarisationRunner.java

  io/quarkmind/agent/plugin/             ← seam interface (existing package)
    MomentDetectionSeam.java

  io/quarkmind/plugin/summarisation/     ← SC2 application layer + domain types
    GameMomentType.java
    GameMoment.java
    GamePhase.java
    GameArc.java
    MomentDetectionTask.java
    MomentDetectionRuleUnit.java
    GamePhaseSummariser.java
    GameArcSummariser.java
    MomentBroker.java
    MomentConsumer.java
    SummarisationLifecycle.java

src/main/resources/
  io/quarkmind/plugin/summarisation/
    MomentDetectionTask.drl              ← Drools rules for L1→2

src/test/java/
  io/casehub/blocks/summarisation/       ← generic tests (plain JUnit)
    EventAccumulatorTest.java
    SummarisationRunnerTest.java
    EventStreamBusTest.java

  io/quarkmind/plugin/summarisation/     ← SC2 tests
    MomentDetectionTaskTest.java         ← plain JUnit
    GamePhaseSummariserTest.java         ← plain JUnit
    GameArcSummariserTest.java           ← plain JUnit
    MomentBrokerIT.java                  ← @QuarkusTest
    SummarisationPipelineIT.java         ← @QuarkusTest
```

## Migration Story

When the generic layer moves to `casehub-blocks`:
1. IntelliJ moves `io.casehub.blocks.summarisation` package (8 files) and tests (3 files)
2. Quarkmind adds `casehub-blocks` as a compile dependency
3. SC2 layer imports don't change — already reference `io.casehub.blocks.summarisation`
4. Drools rules, domain types, CDI bridge, seam interface, and integration tests stay in quarkmind untouched
5. Specs tagged `[generic]` move with the code; `[sc2]` specs stay
6. Blog entries tagged with `#casehub-blocks` are cross-referenced from both projects

## Review Issues Addressed

| Issue | Resolution |
|-------|------------|
| R1-02: No seam interface/TaskRegistrar/requires/produces | Added `MomentDetectionSeam` in `agent/plugin/`, specified requires/produces keys, TaskRegistrar entry |
| R1-03: SummarisationRunner tick loop integration undefined | Added explicit tick sequence diagram, `SummarisationLifecycle` CDI bean, positioned after `createAndSolve()` |
| R1-04: L1→L2 latest-value vs event stream | DroolsScoutingTask publishes transitions to `EventStreamBus<ScoutingIntelPayload>` alongside existing broker update |
| R1-05: WindowPolicy clock source | Changed to `long` timestamps, caller supplies current value via `shouldEmit(long now)` |
| R1-06: Synchronous Summariser blocks LLM path | Documented as Phase 1 constraint with known future migration path |
| R1-07: Acceptance criteria scope mismatch | #180/#181 criteria deferred to their respective issues; issue body to be updated |
| R1-08: No game lifecycle management | All stateful components observe `GameStarted` and reset; table of reset actions |
| R1-09: DroolsStrategyTask integration missing | Specified DataStore fields, subscribed moment types, example rules |
| R1-10: EventStreamBroker naming confusion | Renamed to `EventStreamBus` |
| R1-11: MomentConsumer proliferates consumer pattern | Added generic `EventConsumer<E>` in blocks layer; `MomentConsumer` extends it |
| R1-12: Domain types in wrong package | Moved from `io.quarkmind.domain` to `io.quarkmind.plugin.summarisation` |
| R1-13: Thread safety unnecessary | Dropped; single-threaded tick loop documented |
| R1-14: Qhorus channel undefined | Channel name, semantic, message type, scope, sender all specified |

# DECLINE Speech Act — Design Spec
**Issue:** #199 | **Date:** 2026-06-17 | **Quality Goal:** QG2

---

## Problem

When a plugin's `activateIf()` gate returns false, the game loop silently skips it. No observable record exists: external observers (LLM advisors, Commentator) cannot distinguish "plugin ran and found nothing to do" from "plugin was structurally out of scope." QG2 requires that a plugin outside its scope declares it formally — no silent no-op.

---

## Context and Constraints

**No COMMAND infrastructure exists today.** `GameTickExecutor.execute()` calls `caseEngine.createAndSolve()` directly. The qhorus DECLINE speech act requires a prior COMMAND (`inReplyTo` + `correlationId`). To have DECLINE, COMMAND must be introduced at the QuarkMind layer.

**`createAndSolve()` is async.** It submits `runControlLoop()` to a cached thread pool and returns the CaseFile before plugins execute. Evaluating activation *after* `createAndSolve()` returns means reading a CaseFile the worker thread is concurrently writing — a real data race. Pre-engine evaluation from the immutable `caseData` snapshot (the exact map the engine will use to populate the CaseFile) eliminates this entirely.

**Signal frequency: transitions only.** Per-tick COMMAND+DECLINE is semantically wrong (DECLINE is a one-time refusal, not a continuous broadcast) and expensive. Signals fire only when a plugin's activation state changes. The first tick after `GameStarted` is treated as a transition from unknown → active/inactive, establishing the baseline.

**Three competing StrategyTask implementations.** All three are registered with `TaskDefinitionRegistry` by `QuarkMindTaskRegistrar`. Exactly one wins per tick (via `StrategySelector`). The others emit DECLINE — this is correct and observable.

---

## Phase 1 Semantic Compromise: DONE Before Execution

In qhorus, DONE signals that a commitment was fulfilled — work completed. But `recordTick()` fires **before** `createAndSolve()`, so DONE is dispatched before the plugin executes. This is a deliberate Phase 1 compromise:

> In Phase 1, DONE is dispatched before the plugin executes. The semantic is "accepted for dispatch, treated as committed" rather than "completed." Phase 2 corrects this: `SequenceWorker` (engine#484) emits DONE post-execution from within the engine's plugin lifecycle, after the plugin's `execute()` has returned.

The alternative — STATUS (acknowledge, keep commitment open) before the engine, then DONE after `awaitCompletion()` — requires blocking the scheduler thread. With `@Scheduled(concurrentExecution=SKIP)`, a blocked scheduler thread causes tick skipping when plugin execution takes longer than the tick interval. The game loop's async design is intentional; adding a blocking await regresses it. DONE-as-accepted is the right Phase 1 tradeoff.

Without this statement, a reader following qhorus protocol will flag DONE as wrong. They are not wrong about the semantics; the spec intentionally trades precision for architectural correctness in Phase 1.

---

## New Classes

### `MapCaseContext` — `io.quarkmind.agent`

A `CaseContext` backed by `Map<String, Object>`. Used wherever a `CaseContext` is needed from a raw map, without constructing a CaseFile or hitting the database.

**Implements:**
- `contains(key)` → `data.containsKey(key)` — **required**: `DroolsScoutingTask.activateIf()` calls `ctx.contains(READY)`, so `MapCaseContext` must implement this correctly for `activateIf()` calls during `recordTick()`
- `get(key)` → `data.get(key)`
- `getAs(key, type)` → type-safe cast or null
- `getOrDefault(key, default)` → map's value or default
- `getList(key, elementType)` → cast to List or empty
- `getKeys()` → `Set.copyOf(data.keySet())`
- `size()`, `isEmpty()`
- All write methods and unsupported operations → `UnsupportedOperationException`

**Why a named class (not anonymous):** The `activateIf()` predicate contract requires a real `CaseContext`. `MapCaseContext` is the honest, robust implementation — correct `contains()` and `get()` from the tick snapshot, robust for future plugins that read context keys in `activateIf()`. It also simplifies tests: replaces `new CaseFileContext(new InMemoryCaseFileRepository().create(...))` with `new MapCaseContext(Map.of(...))`.

---

### `PluginDispatchBroker` — `io.quarkmind.agent`

`@ApplicationScoped` CDI bean. Owns the `quarkmind-plugin-dispatch` commitment channel and all COMMAND/DONE/DECLINE dispatch logic.

**Constants:**
```java
public static final String CHANNEL_NAME = "quarkmind-plugin-dispatch";
```

**Injection strategy:** `@Inject` constructor (not field injection). Field injection (like `ScoutingIntelBroker`) prevents clean test constructors — once any explicit constructor is defined, Java no longer generates a no-arg constructor, breaking CDI field injection. The `@Inject` constructor pattern (like `DroolsStrategyTask`) lets CDI use one constructor and tests use another.

**Constructors:**
```java
@Inject
public PluginDispatchBroker(TaskDefinitionRegistry registry,
                             MessageService messageService,
                             ChannelService channelService) {
    this.registry       = registry;
    this.messageService = messageService;
    this.channelService = channelService;
}

/** Package-private — unit tests only; bypasses @PostConstruct channel setup. channelId must be non-null. */
PluginDispatchBroker(TaskDefinitionRegistry registry, MessageService messageService, UUID channelId) {
    this.registry       = registry;
    this.messageService = messageService;
    this.channelId      = channelId;
    // channelService = null — only used in @PostConstruct, not needed for tests
}
```

`priorActivation` and `lastDispatchedId` are initialized at field declaration (`= new ConcurrentHashMap<>()` and `= 0L`) — both constructors benefit without repeating initialization.

**Channel setup (`@PostConstruct`):**

Must use `QuarkusTransaction.requiringNew()` (GE-20260529-88b7b6: `@Transactional` on `@PostConstruct` is not intercepted by Arc during bean creation; `ChannelService.create()` is not idempotent — `findByName()` first):

```java
channelId = QuarkusTransaction.requiringNew().call(() ->
    channelService.findByName(CHANNEL_NAME)
        .map(c -> c.id)
        .orElseGet(() -> channelService.create(
            new ChannelCreateRequest(
                CHANNEL_NAME,
                "Plugin activation commitment dispatch",
                ChannelSemantic.APPEND,
                null, null, null, null, null,
                Set.of(MessageType.COMMAND, MessageType.DONE, MessageType.DECLINE),
                null, null, null, null, null
            )
        ).id)
);
```

`ChannelCreateRequest` has 14 positional arguments; `allowedTypes` is argument 9 (same pattern as `ScoutingIntelBroker.init()`).

**State:**
```java
ConcurrentHashMap<String, Boolean> priorActivation
volatile long lastDispatchedId = 0L
```
`ConcurrentHashMap` required: `recordTick()` runs on the scheduler thread; `onGameStarted()` fires via CDI sync event from the REST/test thread. These can race during game initialisation.

`lastDispatchedId`: message ID of the last DONE/DECLINE dispatch. Updated in the collect-then-apply block of `recordTick()` after all dispatches succeed — never inside `sendCommitmentSignal()`, which returns the ID to the caller. Used by tests as a cursor for `pollAfter()` to isolate only the current test's messages. `volatile` because test threads read it concurrently with the scheduler writing it.

**Exposed accessors:**
```java
public UUID channelId()        { return channelId; }        // for IT test channel queries
public long lastDispatchedId() { return lastDispatchedId; } // for IT test cursor isolation
```

**`@Observes GameStarted`:** `priorActivation.clear()`. The first `recordTick()` after game start treats all plugins as unseen, establishing the baseline. `lastDispatchedId` is NOT reset — it is a monotonically increasing DB cursor; preserving it across games ensures `pollAfter(channelId, lastDispatchedId, N)` correctly returns only the new game's signals.

**`@Transactional recordTick(Map<String, Object> caseData)` — `TxType.REQUIRED`:**

Called from `GameTickExecutor` *before* `createAndSolve()`. The scheduler thread has no active transaction; `REQUIRED` starts a new one. All `MessageService.dispatch()` calls within this method join the same transaction.

To keep `priorActivation` consistent with the committed DB state on rollback, updates are collected first and applied only after all dispatches succeed:

```
CaseContext          evalCtx    = new MapCaseContext(caseData)
Set<String>          toRemove   = new HashSet<>()
Map<String, Boolean> toUpdate   = new LinkedHashMap<>()
Long                 lastReplyId = null   // highest reply ID from this batch; null if no transitions

for each TaskDefinition td in registry.getForCaseType("starcraft-game"):
    cast to io.quarkmind.agent.TaskDefinition qmTd (skip if not)

    inScope = qmTd.requires().stream().allMatch(caseData::containsKey)
    if not inScope:
        toRemove.add(qmTd.getId())   // will clear on apply; re-baselines on scope re-entry
        continue

    nowActive = qmTd.activateIf().test(evalCtx)   // NOT testActivation() — requires() already confirmed
    wasActive = priorActivation.get(qmTd.getId())  // null = never seen

    if wasActive == null OR wasActive != nowActive:
        lastReplyId = sendCommitmentSignal(qmTd.getId(), nowActive)   // DB dispatch — may throw; returns reply message ID
        toUpdate.put(qmTd.getId(), nowActive)

// Apply in-memory state only after all dispatches succeeded.
// If sendCommitmentSignal() throws → transaction rolls back → these lines never execute
// → priorActivation unchanged → next tick re-detects and re-emits. Zero missed signals.
// lastDispatchedId updated here for the same reason: updating it inline (mid-loop) would
// advance the cursor past rolled-back message IDs, causing pollAfter() to skip legitimately
// committed messages on the retry.
toRemove.forEach(priorActivation::remove)
toUpdate.forEach(priorActivation::put)
if lastReplyId != null: lastDispatchedId = lastReplyId
```

**Why `activateIf().test(evalCtx)` and not `testActivation(evalCtx)`:**
`testActivation()` evaluates `requires()` AND `activateIf()`. The `inScope` guard already confirmed all `requires()` keys are present. Calling `testActivation()` re-evaluates them redundantly and obscures the two-gate structure. After the guard, the only remaining question is the `activateIf()` CDI-state gate.

**`private Long sendCommitmentSignal(pluginId, activating)` — returns reply message ID:**

Returns the DONE/DECLINE message ID so `recordTick()` can update `lastDispatchedId` in the apply block (consistent with collect-then-apply — never updated mid-loop).

```java
String correlationId = UUID.randomUUID().toString();

// COMMAND — issued by orchestrator on behalf of the game loop dispatch
DispatchResult commandResult = messageService.dispatch(
    MessageDispatch.builder()
        .channelId(channelId)
        .sender("agent.orchestrator")
        .type(MessageType.COMMAND)
        .correlationId(correlationId)
        .content(pluginId)
        .target("plugin:" + pluginId)   // ":" prefix bypasses obligor trust check
        .actorType(ActorType.SYSTEM)
        .build()
);

// DONE or DECLINE — issued by orchestrator on behalf of the plugin
DispatchResult replyResult = messageService.dispatch(
    MessageDispatch.builder()
        .channelId(channelId)
        .sender("plugin:" + pluginId)
        .type(activating ? MessageType.DONE : MessageType.DECLINE)
        .correlationId(correlationId)
        .inReplyTo(commandResult.messageId())   // Long — the DB ID of the COMMAND message
        .actorType(ActorType.SYSTEM)
        .build()
);
return replyResult.messageId();   // caller (recordTick) updates lastDispatchedId in apply block
```

**Target format `"plugin:" + pluginId`:** `MessageService.dispatch()` skips the obligor trust check when `target` contains ":". The `"plugin:"` prefix is also used as the sender for DONE/DECLINE, creating a consistent naming scheme visible in the message store.

---

## `GameTickExecutor` and `TickTimings` Changes

### Timing

The current `TickTimings` record has three fields: `physicsMs`, `pluginsMs`, `dispatchMs`. `recordTick()` contains DB transactions (2 per transitioning plugin) — distinct from physics and AI dispatch. It must be timed separately.

**`TickTimings` record change** (`AgentOrchestrator`):
```java
// Before:
public record TickTimings(long physicsMs, long pluginsMs, long dispatchMs) {
    public long totalMs() { return physicsMs + pluginsMs + dispatchMs; }
}

// After:
public record TickTimings(long physicsMs, long pluginsMs, long dispatchMs, long brokerMs) {
    public long totalMs() { return physicsMs + pluginsMs + dispatchMs + brokerMs; }
}
```

`brokerMs` covers `translator.toMap()` + `pluginDispatchBroker.recordTick()`. This narrows `pluginsMs` to `createAndSolve()` only (previously included `toMap()`). Benchmark baselines for `pluginsMs` will change; re-run `mvn test -Pbenchmark` and update `docs/benchmarks/`.

Only one `TickTimings` constructor call site exists (`GameTickExecutor`).

**`GameLoopBenchmarkTest` changes:**

`gameLoopSmokeTimings()` — add `brokerMs` array and read:
```java
long[] physicsMs  = new long[MEASURE_TICKS];
long[] brokerMs   = new long[MEASURE_TICKS];   // NEW
long[] pluginsMs  = new long[MEASURE_TICKS];
long[] dispatchMs = new long[MEASURE_TICKS];
long[] totalMs    = new long[MEASURE_TICKS];

for (int i = 0; i < MEASURE_TICKS; i++) {
    orchestrator.gameTick();
    AgentOrchestrator.TickTimings t = orchestrator.getLastTickTimings();
    physicsMs [i] = t.physicsMs();
    brokerMs  [i] = t.brokerMs();    // NEW
    pluginsMs [i] = t.pluginsMs();
    dispatchMs[i] = t.dispatchMs();
    totalMs   [i] = t.totalMs();
}

String report = formatReport(physicsMs, brokerMs, pluginsMs, dispatchMs, totalMs);  // brokerMs added
```

`formatReport()` — add `long[] broker` parameter and "commit signals" row:
```java
private static String formatReport(long[] physics, long[] broker,
                                    long[] plugins, long[] dispatch, long[] total) {
    // format string adds broker row between physics and plugins:
    // engine.tick()      %4dms   %4dms   %4dms
    // engine.observe()   (included in physics above)
    // commit signals     %4dms   %4dms   %4dms   ← NEW
    // caseEngine plugins %4dms   %4dms   %4dms
    // engine.dispatch()  %4dms   %4dms   %4dms
    // ────────────────────────────────────────
    // Total gameTick()   %4dms   %4dms   %4dms
    //
    // Arguments order: mean/p95/max for physics, then broker, then plugins, dispatch, total
}
```

`brokerMs` is NOT zero on steady-state ticks — `toMap()` and the `recordTick()` evaluation loop always run. What is zero on steady-state ticks is the DB overhead: no `MessageService.dispatch()` calls when no activation transitions occur. The benchmark runs 5 warmup + 30 measured ticks; warmup ticks capture initial transition overhead, measured ticks show the low-but-non-zero steady-state cost (~1ms for `toMap()` + loop). This makes DB-write overhead visible in contrast to the steady baseline.

### `GameTickExecutor.execute()` — full revised timing sequence

```java
long t0 = System.currentTimeMillis();
engine.tick();
var gameState = engine.observe();
long t1 = System.currentTimeMillis();        // physics end: engine.tick + observe

Map<String, Object> caseData = translator.toMap(gameState);
pluginDispatchBroker.recordTick(caseData);   // commitment signals before engine
long t1b = System.currentTimeMillis();       // broker end: toMap + recordTick

CaseFile caseFile = null;
try {
    caseFile = caseEngine.createAndSolve("starcraft-game", caseData, Duration.ofSeconds(5));
} catch (Exception e) {
    log.errorf("CaseEngine decision cycle failed at frame %d: %s",
               gameState.gameFrame(), e.getMessage());
}
long t2 = System.currentTimeMillis();        // plugins end: createAndSolve

// dispatch() reads IntentQueue (plugin-populated), not CaseFile — safe even on failed solve
engine.dispatch();
long t3 = System.currentTimeMillis();        // dispatch end

var timings = new AgentOrchestrator.TickTimings(t1 - t0, t2 - t1b, t3 - t2, t1b - t1);
log.debugf("Tick %d — physics=%dms broker=%dms plugins=%dms dispatch=%dms total=%dms | ...",
    gameState.gameFrame(), timings.physicsMs(), timings.brokerMs(),
    timings.pluginsMs(), timings.dispatchMs(), timings.totalMs(), ...);
```

Bucket semantics:
- `physicsMs = t1 - t0` — SC2 physics (engine.tick + observe)
- `brokerMs = t1b - t1` — `toMap()` + `recordTick()` evaluation loop always run (~1ms); `MessageService.dispatch()` DB writes only on activation transitions; DB overhead is zero on steady-state ticks
- `pluginsMs = t2 - t1b` — AI decision cycle (createAndSolve only)
- `dispatchMs = t3 - t2` — intent dispatch to SC2

---

## Why Pre-Engine Evaluation Is Correct

The CaseEngine evaluates `entryCriteria()` (= `requires()`) and `canActivate()` (= `testActivation()`) on its worker thread. Our pre-engine evaluation uses the same `caseData` map — same logical state. Both evaluations agree by construction.

One-tick lag on intra-tick state changes: if `EnemyPostureClassifiedEvent` fires during tick N (changing `StrategySelector` mid-tick), the pre-engine evaluation saw the old selection. The updated DECLINE/DONE signal emits on tick N+1. This is the same lag the CDI event system introduces throughout the codebase.

Consistency is guaranteed by `@Scheduled(concurrentExecution=SKIP)`: only one tick runs at a time, so `StrategySelector` and `ScoutingIntelBroker` are stable between `recordTick()` and the engine's internal `canActivate()` call within the same tick.

---

## First-Tick State (IT test baseline)

After `orchestrator.startGame()` (fires `GameStarted` → `priorActivation.clear()`, trust routing selects `"strategy.drools"` as fallback with no prior game data):

| Plugin | In scope? | activateIf() | Signal |
|---|---|---|---|
| `scouting.drools-cep` | ✓ {READY} present | `ctx.contains(READY)` → true | **DONE** |
| `economics.flow` | ✓ {READY} present | default `ctx→true` | **DONE** |
| `tactics.drools` | ✗ STRATEGY absent | — | **OUT OF SCOPE** |
| `strategy.drools` | ✗ ENEMY_ARMY_SIZE absent | — | **OUT OF SCOPE** |
| `strategy.early-pressure` | ✓ {READY} present | `isSelected(...)` → false | **DECLINE** |
| `strategy.economic-expansion` | ✓ {READY} present | `isSelected(...)` → false | **DECLINE** |

ENEMY_ARMY_SIZE and STRATEGY are absent from `caseData` on the first tick — they are written by plugins during execution, not by `GameStateTranslator`.

First tick emits **8 messages**: 4 COMANDs, 2 DONEs, 2 DECLINEs.

---

## Testing

### `MapCaseContextTest` (unit, no CDI)
- `contains()` returns true/false for present/absent keys
- `get()` returns value or null
- `getOrDefault()` returns default when absent
- `getList()` returns empty list when absent
- Write methods throw `UnsupportedOperationException`

### `PluginDispatchBrokerTest` (unit, no CDI)

Uses stub `io.quarkmind.agent.TaskDefinition` implementations with configurable `requires()` and `activateIf()`. Stubs `MessageService` to capture dispatches. Tests target `recordTick()` logic directly via constructor injection.

- **First tick, plugin activates:** COMMAND + DONE emitted; `toUpdate` applied to `priorActivation`
- **First tick, in-scope but `activateIf()=false`:** COMMAND + DECLINE emitted
- **Repeated tick, same activation state:** no dispatch
- **State change active→inactive:** COMMAND + DECLINE on the tick of change
- **State change inactive→active:** COMMAND + DONE on the tick of change
- **Out-of-scope plugin (requires() key absent):** no dispatch; `toRemove` clears prior state
- **Scope re-entry:** next in-scope tick treats plugin as first-seen → emits
- **`onGameStarted()` clears state:** next tick re-emits for all in-scope plugins as if first-seen
- **Rollback simulation:** if `MessageService.dispatch()` throws, `priorActivation` is unchanged; next call re-detects and re-emits

### `AdaptivePluginSelectionIT` (existing `@QuarkusTest`, extended)

**Injections needed:**
```java
@Inject PluginDispatchBroker dispatchBroker;
@Inject MessageService        messageService;
```

**Cross-test isolation:** the `quarkmind-plugin-dispatch` channel is persistent (APPEND) and `@QuarkusTest` shares an in-memory DB across tests in the same class. `pollAfter(channelId, null, N)` would return messages from all prior tests. Instead, capture the cursor before each test:

```java
private long afterId;

@BeforeEach
void setUp() {
    // ... existing setup (simulatedGame.reset, startGame, intentQueue.drainAll, broker.clearLatest) ...
    afterId = dispatchBroker.lastDispatchedId();   // cursor: ignore messages from prior tests
}
```

**Retrieval and assertions** (`Message` is `io.casehub.qhorus.runtime.message.Message` — all fields public):

```java
@Test
void firstTickEmitsCorrectDeclineSignals() {
    orchestrator.gameTick();

    // pollAfter(channelId, afterId, limit): returns messages with id > afterId, excluding EVENTs
    // afterId == 0 (no prior messages) → pass null to get all; afterId > 0 → pass afterId
    List<Message> delta = messageService.pollAfter(
        dispatchBroker.channelId(),
        afterId > 0 ? afterId : null,
        20);

    List<Message> commands  = delta.stream().filter(m -> m.messageType == MessageType.COMMAND).toList();
    List<Message> dones     = delta.stream().filter(m -> m.messageType == MessageType.DONE).toList();
    List<Message> declines  = delta.stream().filter(m -> m.messageType == MessageType.DECLINE).toList();

    assertThat(commands).hasSize(4);
    assertThat(dones).hasSize(2);
    assertThat(declines).hasSize(2);

    assertThat(dones.stream().map(m -> m.sender).toList())
        .containsExactlyInAnyOrder("plugin:scouting.drools-cep", "plugin:economics.flow");

    assertThat(declines.stream().map(m -> m.sender).toList())
        .containsExactlyInAnyOrder("plugin:strategy.early-pressure", "plugin:strategy.economic-expansion");
}
```

**Why these exact counts (derivable from first-tick state):**
- `tactics.drools` and `strategy.drools` are out of scope (STRATEGY and ENEMY_ARMY_SIZE absent from `caseData`) → no signal
- `scouting.drools-cep` and `economics.flow` have `activateIf()` → true → DONE
- `strategy.early-pressure` and `strategy.economic-expansion` have `activateIf()` = `isSelected(...)` → false (trust routing picks `"strategy.drools"` as fallback) → DECLINE

This assertion catches regressions in `requires()` changes, `activateIf()` logic, trust routing fallback, and `GameStateTranslator` key additions.

---

## ARC42STORIES.MD Update

C2 known-limitation entry: "Quality Goal 2 (Formal DECLINE) not closed: DECLINE speech act is platform-defined in casehub-qhorus but not wired in QuarkMind game-loop dispatch (#199)" → updated to mark resolved.

---

## GameStopped Behaviour

No `@Observes GameStopped` handler. The channel is persistent (APPEND semantic); open commitments from the final tick (if any) remain in the message store. The commit-then-apply pattern means COMMAND+DONE/DECLINE pairs are always closed within the same `recordTick()` transaction — there are no dangling open commitments from the broker itself.

---

## What This Does Not Do

- Does not modify foundation (CaseEngine, qhorus internals)
- Does not send per-tick signals — only on activation state transitions
- Does not send DECLINE for out-of-scope plugins (requires() keys absent) — that is "not applicable," not "refusing"
- Does not close the Phase 1 DONE semantic gap — Phase 2 `SequenceWorker` (engine#484) corrects this by emitting DONE post-execution from within the engine's plugin lifecycle

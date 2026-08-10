# Adaptive Plugin Selection — Layer 5 Design

**Issue:** #157  
**Date:** 2026-06-03  
**Status:** Approved (post-review revision)

---

## Goal

Implement Layer 5 of the QuarkMind harness: adaptive plugin dispatch via CaseHub binding
conditions. Currently all four plugins activate unconditionally every tick (`entryCriteria
= {READY}`). L5 declares meaningful conditions so the platform gates and orders plugins
based on actual CaseFile state.

**Scope of "adaptive" in L5:** conditional scheduling within a single tick based on key
presence/absence. This is distinct from L6's adaptive mechanism (trust-weighted selection
across ticks based on historical performance). L5 decides *whether* a plugin runs this
tick; L6 will decide *which implementation* to prefer over time.

---

## Current State

All four plugins:

```java
@Override public Set<String> entryCriteria() { return Set.of(QuarkMindCaseFile.READY); }
```

`AgentOrchestrator.gameTick()` calls `caseEngine.createAndSolve()` and discards the
returned `CaseFile`. Tests call `orchestrator.gameTick()` directly (scheduler disabled in
`%test`), with no handle on the solve result.

---

## How CaseHub Entry Criteria Work

`createAndSolve()` starts with a CaseFile pre-populated by `GameStateTranslator` (READY,
MINERALS, ARMY, ENEMY_UNITS, etc. — raw game state only; no plugin-written keys). The
CaseEngine then runs a re-evaluation loop:

1. `evaluateAndCreatePlanItems` checks `caseFile.contains(key)` for every key in each
   task's `entryCriteria()`. Any missing key → task skipped this round.
2. Eligible tasks execute; each may write new keys via `caseFile.put()`.
3. Re-evaluation runs again. Newly written keys may unlock previously blocked tasks.
4. Loop repeats to quiescence (no more eligible tasks → `WAITING` status → solve complete).

This mechanism makes `entryCriteria()` serve two distinct purposes depending on whether
the required key is always written or conditionally written:

- **Ordering dependency**: key is always written by an earlier task (e.g.
  `ENEMY_ARMY_SIZE` is always written by Scouting, even when 0). The criterion ensures
  the dependent task runs *after* its prerequisite, but it is never actually skipped.
- **Conditional gate**: key is written only when specific game state exists (e.g.
  `NEAREST_THREAT` is written only when enemies are visible). The criterion causes genuine
  plugin skipping when the condition is absent.

L5 uses both. They look identical in code; the design notes below distinguish them.

---

## Dependency Graph After L5

```
Scouting   {READY}                            → writes ENEMY_ARMY_SIZE, NEAREST_THREAT, ...
Economics  {READY}                            → independent of all plugin output
Strategy   {READY, ENEMY_ARMY_SIZE}   [O]     → writes STRATEGY
Tactics    {READY, STRATEGY, NEAREST_THREAT}  → [O] for STRATEGY, [G] for NEAREST_THREAT
```

`[O]` = ordering dependency (key always present; ensures sequencing)  
`[G]` = conditional gate (key conditionally absent; causes genuine plugin skip)

**Load-bearing invariant:** `NEAREST_THREAT ∈ CaseFile ↔ enemies.size() > 0 at scouting
execution time.` Both `BasicScoutingTask` (line 74) and `DroolsScoutingTask` (line 103)
write `NEAREST_THREAT` only inside `if (!enemies.isEmpty())`. This invariant is what
makes the Tactics gate semantically correct: tactics is meaningless without a located
threat.

**Scouting and Economics** both have `{READY}` — they are independent and their relative
execution order within a tick is determined by `PlanItem` priority internals. This has no
bearing on L5 correctness since they do not exchange data.

**Strategy** gets `{READY, ENEMY_ARMY_SIZE}` as an ordering dependency. `ENEMY_ARMY_SIZE`
is always written by Scouting (unconditionally, even as 0), so Strategy is never actually
skipped — it always runs after Scouting. `DroolsStrategyTask.execute()` still feeds raw
`ENEMY_UNITS` to Drools for structural unit-object matching (see C2 stub below); the
`ENEMY_ARMY_SIZE` read in `execute()` is a telemetry feed that makes the declared
dependency honest. Full migration to scouting-derived intel is tracked as #169.

**Tactics** gets `{READY, STRATEGY, NEAREST_THREAT}`:
- `STRATEGY` is an ordering dependency — Strategy always writes it, so this ensures
  Tactics runs after Strategy within the tick.
- `NEAREST_THREAT` is a conditional gate — when no enemies are visible, Scouting does not
  write `NEAREST_THREAT`, and Tactics is skipped entirely.

In the early game (no enemy contact), Tactics is never scheduled. The `threat != null ?
threat : MAP_CENTER` guard inside `DroolsTacticsTask.dispatch()` becomes dead code once
the gate is in place — `threat` is always non-null when `execute()` is reached. Tracked
for cleanup as a follow-on item.

---

## Architecture Change: GameTickExecutor + TickResult

`AgentOrchestrator.gameTick()` currently mixes scheduling control and tick execution.
The `CaseFile` returned by `createAndSolve()` is currently discarded; tests have no
handle on solve results. L5 exposes this result through the existing orchestrator facade.

### `GameTickExecutor` (new, package-private in `agent/`)

Internal implementation class; no code outside `io.quarkmind.agent` depends on it
directly. Package-private — never injected by tests.

```java
@ApplicationScoped
class GameTickExecutor {

    @Inject SC2Engine engine;
    @Inject GameStateTranslator translator;
    @Inject CaseEngine caseEngine;

    AgentOrchestrator.TickResult execute() {
        long t0 = System.currentTimeMillis();
        engine.tick();
        var gameState = engine.observe();
        long t1 = System.currentTimeMillis();

        Map<String, Object> caseData = translator.toMap(gameState);
        CaseFile caseFile = null;
        try {
            caseFile = caseEngine.createAndSolve("starcraft-game", caseData,
                                                 Duration.ofSeconds(5));
        } catch (Exception e) {
            log.errorf("CaseEngine decision cycle failed at frame %d: %s",
                       gameState.gameFrame(), e.getMessage());
        }
        long t2 = System.currentTimeMillis();

        engine.dispatch();
        long t3 = System.currentTimeMillis();

        return new AgentOrchestrator.TickResult(
            caseFile, new AgentOrchestrator.TickTimings(t1 - t0, t2 - t1, t3 - t2));
    }
}
```

### `AgentOrchestrator` (updated)

Nested public types are declared here — this is the test-facing facade.

```java
// Declared inside AgentOrchestrator (unchanged location):
public record TickTimings(long physicsMs, long pluginsMs, long dispatchMs) {
    public long totalMs() { return physicsMs + pluginsMs + dispatchMs; }
}

// New nested type:
public record TickResult(CaseFile caseFile, TickTimings timings) {
    public boolean solveSucceeded() { return caseFile != null; }
}
```

`TickResult` holds the live `CaseFile` post-solve. No further engine writes occur after
`createAndSolve()` returns (the CaseEngine removes the CaseFile from its active maps at
that point); the reference is stable for test assertions.

```java
@Inject GameTickExecutor tickExecutor;
private final AtomicReference<TickResult> lastTickResult = new AtomicReference<>();

@Scheduled(every = "${starcraft.tick.interval:500ms}", ...)
void gameTick() {
    if (schedulerPaused || !engine.isConnected()) return;
    lastTickResult.set(tickExecutor.execute());
}

public TickResult getLastTickResult() { return lastTickResult.get(); }

// Backward compatibility for GameLoopBenchmarkTest:
public TickTimings getLastTickTimings() {
    TickResult r = lastTickResult.get();
    return r != null ? r.timings() : null;
}
```

`TickTimings` stays declared inside `AgentOrchestrator`. `GameLoopBenchmarkTest` already
references `AgentOrchestrator.TickTimings` — no change required there.

---

## Criteria Changes

### `DroolsStrategyTask` and `BasicStrategyTask`

```java
@Override public Set<String> entryCriteria() {
    return Set.of(QuarkMindCaseFile.READY, QuarkMindCaseFile.ENEMY_ARMY_SIZE);
}
```

`BasicStrategyTask` has no CDI annotations (`@ApplicationScoped`, `@CaseType`) — it is a
plain Java class for direct-instantiation tests, not a live CDI bean. This change has no
runtime effect. It is updated for **interface parity**: `BasicStrategyTaskTest` tests
`entryCriteria()` directly, and the test should exercise the same contract as the live
Drools bean.

C2 stub read in `DroolsStrategyTask.execute()`:

```java
// C2 stub (tracked as #169): ENEMY_ARMY_SIZE establishes scouting → strategy ordering.
// When C2 lands: replace enemies DataStore feed with ENEMY_POSTURE + ENEMY_BUILD_ORDER.
int enemyCount = caseFile.get(QuarkMindCaseFile.ENEMY_ARMY_SIZE, Integer.class).orElse(0);
// ... existing Drools logic unchanged ...
log.debugf("[DROOLS-STRATEGY] %s | stalkers=%d | enemies(scouted)=%d | builds=%s | %s",
           strategy, ..., enemyCount, ...);
```

`BasicStrategyTask.execute()` gets the same stub read for interface parity.

### `DroolsTacticsTask` and `BasicTacticsTask`

```java
@Override public Set<String> entryCriteria() {
    return Set.of(QuarkMindCaseFile.READY,
                  QuarkMindCaseFile.STRATEGY,
                  QuarkMindCaseFile.NEAREST_THREAT);
}
```

`BasicTacticsTask` carries the same caveat as `BasicStrategyTask`: no CDI annotations,
plain Java, interface-parity change only.

No change to `execute()` in either class. Existing internal guards (`if (army.isEmpty())
return;`) stay — they handle edge cases within a tick when tactics has activated but army
is empty.

---

## Tests

### `AdaptivePluginSelectionIT` (new `@QuarkusTest`)

Tests use `orchestrator.gameTick()` + `orchestrator.getLastTickResult()` — same entry
point as production, no cross-package visibility issues.

```java
@Inject AgentOrchestrator orchestrator;
@Inject SimulatedGame simulatedGame;
@Inject IntentQueue intentQueue;
@Inject ScenarioRunner scenarioRunner;

@BeforeEach
void setUp() {
    simulatedGame.reset();
    orchestrator.startGame();
    intentQueue.drainAll(); // clear any residual intents from prior tests
}

@Test
void tacticsSkippedWhenNoEnemiesVisible() {
    // Default reset state: no enemies → scouting never writes NEAREST_THREAT
    orchestrator.gameTick();
    AgentOrchestrator.TickResult result = orchestrator.getLastTickResult();

    assertThat(result.solveSucceeded()).isTrue();
    assertThat(result.caseFile().contains(QuarkMindCaseFile.NEAREST_THREAT)).isFalse();
    // Gate not met → no tactical intents
    assertThat(intentQueue.drainAll())
        .noneMatch(i -> i instanceof AttackIntent || i instanceof BlinkIntent);
}

@Test
void scoutingRunsBeforeStrategyInOrderedChain() {
    // ENEMY_ARMY_SIZE ordering dependency: scouting always runs first,
    // then strategy produces STRATEGY. Both keys must be present after any tick.
    orchestrator.gameTick();
    AgentOrchestrator.TickResult result = orchestrator.getLastTickResult();

    assertThat(result.solveSucceeded()).isTrue();
    assertThat(result.caseFile().contains(QuarkMindCaseFile.ENEMY_ARMY_SIZE)).isTrue();
    assertThat(result.caseFile().contains(QuarkMindCaseFile.STRATEGY)).isTrue();
}

@Test
void tacticsGateMetWhenEnemyPresent() {
    // Enemies visible → scouting writes NEAREST_THREAT → tactics gate met
    scenarioRunner.run("spawn-enemy-attack");
    orchestrator.gameTick();
    AgentOrchestrator.TickResult result = orchestrator.getLastTickResult();

    assertThat(result.solveSucceeded()).isTrue();
    assertThat(result.caseFile().contains(QuarkMindCaseFile.NEAREST_THREAT)).isTrue();
    assertThat(result.caseFile().contains(QuarkMindCaseFile.STRATEGY)).isTrue();
    // Gate met — CaseHub scheduled tactics. Intent consequences depend on
    // army state and strategy outcome (DEFEND → MoveIntent, ATTACK → AttackIntent).
}
```

The third test verifies the gate was met, not the specific intents. Intent-level
assertions are fragile: strategy outputs `DEFEND` when enemies are visible (not `ATTACK`),
so `AttackIntent` would not appear; and `MoveIntent` could come from scouting's active
scout dispatch. CaseFile state is the correct assertion level for a binding-condition test.

### `FullMockPipelineIT` (unchanged)

`orchestrator.gameTick()` still exists and still works after the refactor. The three
existing tests do not need `TickResult`; their call sites are unchanged.

---

## C2 Stub and Follow-on Issue (#169)

The `{READY, ENEMY_ARMY_SIZE}` criterion on StrategyTask establishes the scouting →
strategy ordering as an architectural commitment. The stub read in `execute()` (`enemyCount`
fed to telemetry) makes the declared dependency honest. The full migration (C2, #169)
replaces `DataStore<Unit> enemies` in `StrategyRuleUnit` with scouting-derived summaries
(`ENEMY_POSTURE`, `ENEMY_BUILD_ORDER`), requiring Drools rules to be rewritten from
structural unit matching to intel-summary matching. That is out of scope for L5.

---

## Out of Scope

- Drools rule changes (C2, tracked as #169)
- New `QuarkMindCaseFile` constants — all keys used here are already declared
- `ScoutingTask` criteria (already `{READY}`, correct — reads raw game state only)
- `EconomicsTask` criteria (already `{READY}`, correct — reads raw game state only)
- `CRISIS` key: declared in `QuarkMindCaseFile` but has no writer or reader; ownership
  deferred; not addressed in L5
- Dead code cleanup (#170): `threat != null ? threat : MAP_CENTER` guard in
  `DroolsTacticsTask.dispatch()` becomes unreachable once the `NEAREST_THREAT` gate is
  in place. Flagged as a follow-on cleanup item.
- Negative ordering test (remove scouting, verify STRATEGY absent): invasive, deferred
- LAYER-LOG.md entry (written at work-end per existing convention)

---

## Files Touched

| File | Change |
|------|--------|
| `agent/GameTickExecutor.java` | New class (package-private) |
| `agent/AgentOrchestrator.java` | Add `TickResult` nested record; delegate to `GameTickExecutor`; expose `getLastTickResult()`; `getLastTickTimings()` forwards |
| `plugin/DroolsStrategyTask.java` | criteria + C2 stub read + telemetry log |
| `plugin/BasicStrategyTask.java` | criteria + stub read (interface parity) |
| `plugin/DroolsTacticsTask.java` | criteria only |
| `plugin/BasicTacticsTask.java` | criteria only (interface parity) |
| `sc2/mock/AdaptivePluginSelectionIT.java` | New test class (3 tests) |

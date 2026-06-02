# Adaptive Plugin Selection — Layer 5 Design

**Issue:** #157  
**Date:** 2026-06-03  
**Status:** Approved

---

## Goal

Implement Layer 5 of the QuarkMind harness: adaptive plugin dispatch via CaseHub binding
conditions. Currently all four plugins activate unconditionally every tick (`entryCriteria
= {READY}`). L5 declares meaningful conditions so the platform gates and orders plugins
based on actual CaseFile state.

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

## Dependency Graph After L5

```
Scouting   {READY}                          → writes ENEMY_ARMY_SIZE, NEAREST_THREAT, ...
Economics  {READY}                          → writes nothing plugin-dependent
Strategy   {READY, ENEMY_ARMY_SIZE}         → writes STRATEGY
Tactics    {READY, STRATEGY, NEAREST_THREAT}
```

**Scouting** always writes `ENEMY_ARMY_SIZE` (even 0). It only writes `NEAREST_THREAT`
when `!enemies.isEmpty()` — verified in both `BasicScoutingTask` and
`DroolsScoutingTask`. This makes `NEAREST_THREAT` a genuine presence gate.

**Strategy** gets `{READY, ENEMY_ARMY_SIZE}` as an ordering commitment: scouting runs
before strategy within each tick. `DroolsStrategyTask.execute()` still reads raw
`ENEMY_UNITS` for Drools structural matching (see C2 stub below). The criterion is
honest: `execute()` reads `ENEMY_ARMY_SIZE` for telemetry, making the declared
dependency real. Full migration to scouting-derived intel is tracked as a follow-on issue
(C2).

**Tactics** gets `{READY, STRATEGY, NEAREST_THREAT}`: activates only when strategy has
set a course of action AND scouting has identified a threat location. In the early game
(no enemy contact), tactics is never scheduled — the MAP_CENTER fallback in execute() is
never reached. This is the primary adaptive binding demonstrated by L5.

**Economics** keeps `{READY}` — it reads only raw game state (MINERALS, WORKERS,
MY_BUILDINGS) and has no plugin data dependencies.

---

## Architecture Change: GameTickExecutor

`AgentOrchestrator.gameTick()` currently mixes scheduling control and tick execution.
L5 extracts tick execution into a separate class so tests can get the `CaseFile` result
directly.

### `GameTickExecutor` (new, `agent/`)

```java
@ApplicationScoped
class GameTickExecutor {
    @Inject SC2Engine engine;
    @Inject GameStateTranslator translator;
    @Inject CaseEngine caseEngine;

    record TickResult(CaseFile caseFile, TickTimings timings) {
        boolean solveSucceeded() { return caseFile != null; }
    }

    TickResult execute() {
        long t0 = System.currentTimeMillis();
        engine.tick();
        var gameState = engine.observe();
        long t1 = System.currentTimeMillis();

        Map<String, Object> caseData = translator.toMap(gameState);
        CaseFile caseFile = null;
        try {
            caseFile = caseEngine.createAndSolve("starcraft-game", caseData, Duration.ofSeconds(5));
        } catch (Exception e) {
            log.errorf("CaseEngine decision cycle failed at frame %d: %s",
                       gameState.gameFrame(), e.getMessage());
        }
        long t2 = System.currentTimeMillis();

        engine.dispatch();
        long t3 = System.currentTimeMillis();

        return new TickResult(caseFile, new TickTimings(t1 - t0, t2 - t1, t3 - t2));
    }
}
```

### `AgentOrchestrator` (updated)

Becomes a pure scheduling/lifecycle coordinator:

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

---

## Criteria Changes

### `DroolsStrategyTask` and `BasicStrategyTask`

```java
@Override public Set<String> entryCriteria() {
    return Set.of(QuarkMindCaseFile.READY, QuarkMindCaseFile.ENEMY_ARMY_SIZE);
}
```

Stub read in `DroolsStrategyTask.execute()`:

```java
// C2 stub: ENEMY_ARMY_SIZE establishes scouting → strategy ordering.
// When C2 lands: replace enemies DataStore feed with ENEMY_POSTURE + ENEMY_BUILD_ORDER.
int enemyCount = caseFile.get(QuarkMindCaseFile.ENEMY_ARMY_SIZE, Integer.class).orElse(0);
// ... existing logic unchanged ...
log.debugf("[DROOLS-STRATEGY] %s | stalkers=%d | enemies(scouted)=%d | ...",
           strategy, ..., enemyCount, ...);
```

`BasicStrategyTask.execute()` gets the same stub read (it's `@Alternative` but must stay
consistent).

### `DroolsTacticsTask` and `BasicTacticsTask`

```java
@Override public Set<String> entryCriteria() {
    return Set.of(QuarkMindCaseFile.READY,
                  QuarkMindCaseFile.STRATEGY,
                  QuarkMindCaseFile.NEAREST_THREAT);
}
```

No change to `execute()`. Existing internal guards (`if (army.isEmpty()) return;`) stay.

---

## Tests

### `AdaptivePluginSelectionIT` (new `@QuarkusTest`)

```java
@Inject GameTickExecutor tickExecutor;
@Inject SimulatedGame simulatedGame;
@Inject AgentOrchestrator orchestrator;
@Inject IntentQueue intentQueue;
@Inject ScenarioRunner scenarioRunner;

@BeforeEach
void setUp() {
    simulatedGame.reset();
    orchestrator.startGame();
}

@Test
void tacticsSkippedWhenNoEnemiesVisible() {
    // Default state: no enemies → scouting never writes NEAREST_THREAT
    GameTickExecutor.TickResult result = tickExecutor.execute();
    assertThat(result.solveSucceeded()).isTrue();

    assertThat(result.caseFile().contains(QuarkMindCaseFile.NEAREST_THREAT)).isFalse();
    assertThat(intentQueue.drainAll())
        .noneMatch(i -> i instanceof AttackIntent || i instanceof BlinkIntent);
}

@Test
void tacticsActivatesWhenEnemyPresent() {
    scenarioRunner.run("spawn-enemy-attack");
    GameTickExecutor.TickResult result = tickExecutor.execute();

    assertThat(result.caseFile().contains(QuarkMindCaseFile.NEAREST_THREAT)).isTrue();
    assertThat(intentQueue.drainAll())
        .anyMatch(i -> i instanceof AttackIntent);
}
```

Two assertion layers: CaseFile state proves the gate condition was or wasn't met; IntentQueue
proves the platform-level consequence. Tests the binding at the CaseHub level, not at mock
level.

### `FullMockPipelineIT` (updated)

Three existing tests switch from `orchestrator.gameTick()` to `tickExecutor.execute()`.
Assert behaviour is unchanged; no test logic changes beyond the call site.

---

## C2 Stub and Follow-on Issue

The `{READY, ENEMY_ARMY_SIZE}` criterion on StrategyTask is an ordering commitment
with an honest stub: `execute()` reads `ENEMY_ARMY_SIZE` and includes it in telemetry.
The full migration (C2) replaces the `DataStore<Unit> enemies` feed in `StrategyRuleUnit`
with scouting-derived intel (`ENEMY_POSTURE`, `ENEMY_BUILD_ORDER`). C2 requires rewriting
the Drools strategy rules from structural unit-object matching to intel-summary matching,
which is a separate scope.

C2 is tracked as #169.

---

## Out of Scope

- Drools rule changes (Approach C2)
- New `QuarkMindCaseFile` constants — all required keys (`ENEMY_ARMY_SIZE`, `STRATEGY`,
  `NEAREST_THREAT`) are already declared
- `ScoutingTask` criteria (already `{READY}`, correct)
- `EconomicsTask` criteria (already `{READY}`, correct)
- LAYER-LOG.md entry (written at work-end)

---

## Files Touched

| File | Change |
|------|--------|
| `agent/GameTickExecutor.java` | New class |
| `agent/AgentOrchestrator.java` | Delegate to GameTickExecutor, store TickResult |
| `plugin/DroolsStrategyTask.java` | criteria + stub read |
| `plugin/BasicStrategyTask.java` | criteria + stub read |
| `plugin/DroolsTacticsTask.java` | criteria only |
| `plugin/BasicTacticsTask.java` | criteria only |
| `sc2/mock/FullMockPipelineIT.java` | call-site update (gameTick → execute) |
| `sc2/mock/AdaptivePluginSelectionIT.java` | New test class |

# QuarkMind Agentic Harness — Layer Log

Structured record of what was built at each layer, optimised for LLM consumption. Each entry is the raw material needed to reproduce the layer in a different domain harness. Correlates with blog entries in the workspace `blog/`, git history, and GitHub issues.

Entries are ordered for learning, not chronology. Each entry is complete when the layer closes — no placeholders.

Cross-references:
- Blog entries: workspace `blog/` (staged; published via `publish-blog`)
- Design specs: workspace `specs/` and promoted to `docs/superpowers/specs/`
- Tutorial teaching objectives: `quarkmind.md §Tutorial Layers` in casehub-parent
- AML reference implementation: `../aml/LAYER-LOG.md` (Layers 1–3 complete)
- Platform harness pattern: `https://raw.githubusercontent.com/casehubio/parent/main/docs/AGENTIC-HARNESS-GUIDE.md`

**Domain note:** QuarkMind uses a single-module Quarkus app (no `api/`/`app/` split). There are no downstream JPA consumers. CDI displacement works at the plugin level via `@CaseType` qualifier — each layer adds a new plugin implementation that takes priority over the prior one. The `NaiveXxxService @DefaultBean` pattern from AML applies at the plugin seam level here, not at a separate service class.

---

## Layer 1 — Naive Game Loop (direct plugin calls, no CaseHub)

**Status:** Conceptual baseline — this layer was never a deployed phase in QuarkMind. The first `AgentOrchestrator` already used `CaseEngine` (Layer 2). The entry documents the pattern Layer 2 replaced.
**Issue:** #28 (CaseHub integration commit — Layer 2 was the first production game loop)

**Key files (Layer 2 superseded these gaps, not separate naive files):**
- `agent/AgentOrchestrator.java` — the harness controller; remove CaseEngine to get the naive form
- `agent/plugin/StrategyTask.java`, `EconomicsTask.java`, `TacticsTask.java`, `ScoutingTask.java` — plugin seam interfaces
- `plugin/` — active plugin implementations; these would be called directly in the naive form

### What it shows

A Quarkus `@Scheduled` game loop that ticks the SC2 engine, observes game state, and calls each plugin in turn with the raw `GameState` object. No shared blackboard — each plugin sees only the snapshot the orchestrator passes it. Plugins cannot read each other's outputs within the same tick. No adaptive routing — all plugins fire every tick regardless of whether they have relevant input.

This is the anti-pattern baseline. The gap comments below are the teaching mechanism; they name the specific coordination requirements that each subsequent layer closes.

### The gap comments

```java
// LAYER 1 GAP: no shared state — each plugin receives only the raw game state.
// StrategyTask's current intent is not visible to TacticsTask in the same tick.
// Inter-plugin communication requires another data structure outside the loop.

// LAYER 1 GAP: no adaptive routing — all four plugins fire every tick.
// TacticsTask runs even when there are no enemy units. ScoutingTask runs even
// when visibility is complete. No binding conditions. No capability-based selection.

// LAYER 1 GAP: no outcome tracking — no record of which plugin made which decision,
// what game state produced it, or whether the resulting intent improved the outcome.
// No basis for trust scoring or plugin performance comparison.

// LAYER 1 GAP: no resilience — if EconomicsTask throws, the tick fails with no record
// of partial work. No retry logic. No SLA. No formal task lifecycle.
```

### Key wiring

```java
// Hypothetical naive orchestrator — the pattern Layer 2 replaced
@Scheduled(every = "${starcraft.tick.interval:500ms}")
void gameTick() {
    engine.tick();
    GameState state = engine.observe();   // raw SC2 snapshot, no blackboard enrichment

    strategyTask.solve(state);            // call order is arbitrary — no declared dependencies
    scoutingTask.solve(state);            // cannot read strategyTask's output this tick
    tacticsTask.solve(state);             // same snapshot for all; no per-tick state sharing
    economicsTask.solve(state);

    engine.dispatch();
}
```

**Single-module note.** AML uses `api/`/`app/` and `@DefaultBean` on the naive service. QuarkMind uses a single module — there is no separate API module and no `NaiveAgentOrchestrator` class. The conceptual equivalent is `AgentOrchestrator` without `CaseEngine` injection.

### Gotchas

None specific to this layer. No framework dependencies, no CDI, no shared state. If Layer 1 feels complex, domain logic has leaked into the wrong place.

### Pattern to replicate (in another domain)

1. SC2 engine seam: `SC2Engine.tick()`, `observe()` → `DomainState`, `dispatch()`
2. Plugin seam interfaces: one per agent concern (`StrategyTask`, `EconomicsTask`, `TacticsTask`, `ScoutingTask`)
3. Naive loop: tick → observe → call each plugin with `DomainState` → dispatch
4. Add gap comments naming the coordination requirement each gap represents — these are the tutorial's teaching mechanism
5. Write unit tests for each plugin independently (no CDI needed — plain `new`)
6. Do NOT add inter-plugin communication at this layer; that is the gap Layer 2 closes

---

## Layer 2 — + casehub-engine blackboard (CaseFile per-tick shared state)

**Completed:** 2026-04-06 (initial CaseHub integration, commit 69dee90); plugin seam interfaces added 2026-04-08 (commit 7429255); formally documented 2026-05-25 (#139)
**Issue:** #28 (CaseHub integration)

**Key files:**
- `agent/AgentOrchestrator.java` — `@Scheduled gameTick()`: tick → translate → `caseEngine.createAndSolve()` → dispatch
- `agent/GameStateTranslator.java` — maps `GameState` observation into `Map<String, Object>` CaseFile entries
- `agent/QuarkMindCaseFile.java` — all CaseFile key constants (`game.*` for observation, `agent.*` for plugin-written reasoning)
- `agent/QuarkMindTaskRegistrar.java` — injects all four plugin seam interfaces to keep Arc beans alive and registers them with `TaskDefinitionRegistry`
- `agent/plugin/StrategyTask.java`, `EconomicsTask.java`, `TacticsTask.java`, `ScoutingTask.java` — plugin seam interfaces extending `TaskDefinition`
- `plugin/DroolsStrategyTask.java`, `FlowEconomicsTask.java`, `DroolsTacticsTask.java`, `BasicScoutingTask.java` — active implementations

### What it shows

The CaseFile blackboard replaces direct state passing. Each tick:
1. `GameStateTranslator.toMap(gameState)` populates the CaseFile with `game.*` keys (minerals, supply, units, enemy intel)
2. `caseEngine.createAndSolve("starcraft-game", caseData, timeout)` dispatches all registered plugins against the blackboard
3. Plugins read from and write to the CaseFile — StrategyTask writes `agent.strategy.current`; TacticsTask reads it to select targets
4. `engine.dispatch()` flushes the `IntentQueue` that plugins populated

Plugins no longer receive a `GameState` argument directly. They read their inputs from the CaseFile and write their outputs back. This enables within-tick sharing: StrategyTask runs first and writes its intent; TacticsTask reads it in the same `createAndSolve` cycle.

### Key wiring

```java
// AgentOrchestrator.gameTick() — the Layer 2 harness control loop
@Scheduled(every = "${starcraft.tick.interval:500ms}", concurrentExecution = SKIP)
public void gameTick() {
    engine.tick();
    var gameState = engine.observe();

    Map<String, Object> caseData = translator.toMap(gameState);  // blackboard population
    caseEngine.createAndSolve("starcraft-game", caseData, Duration.ofSeconds(5)); // plugin dispatch
    engine.dispatch();  // flush IntentQueue
}
```

```java
// QuarkMindCaseFile — all keys in one place; never use raw strings elsewhere
public static final String MINERALS     = "game.resources.minerals";
public static final String STRATEGY     = "agent.strategy.current";  // written by StrategyTask
public static final String CRISIS       = "agent.intent.crisis";     // written by TacticsTask
```

```java
// Plugin seam interface — all four are identical; one per agent concern
public interface StrategyTask extends TaskDefinition {}

// Active implementation — @CaseType("starcraft-game") makes CaseEngine select it
@ApplicationScoped
@CaseType("starcraft-game")
public class DroolsStrategyTask implements StrategyTask {
    // reads game.* keys from CaseFile; writes agent.strategy.current
}
```

**`QuarkMindTaskRegistrar` is required to keep CDI beans alive.** Quarkus Arc removes `@CaseType` beans as "unused" unless something injects them. The registrar injects each plugin via its typed seam interface, preventing pruning and registering the task with `TaskDefinitionRegistry` so `CaseEngine` discovers it.

**`@Scheduled(concurrentExecution = SKIP)` prevents overlapping ticks.** Without this, a slow plugin in tick N can still be executing when tick N+1 fires. In a 500ms game loop, a plugin timeout or slow Drools evaluation will stall the tick; `SKIP` ensures each tick completes before the next one fires rather than spawning concurrent executions.

**Key namespace convention:** `game.*` keys are written only by `GameStateTranslator` (observation state). `agent.*` keys are written only by plugins (reasoning state). Never cross these namespaces — a plugin writing to `game.*` or the translator writing to `agent.*` is a design violation.

### Gotchas

- **Arc bean pruning.** Injecting `@CaseType` beans only through the typed seam interface (`@Inject @CaseType(...) StrategyTask strategyTask`) prevents Arc from removing them. Do not rely on instance discovery alone without an explicit injection point.
- **`ConcurrentExecution.SKIP` is mandatory at game-loop timing.** At 500ms ticks, Drools rule evaluation or Flow execution can exceed the tick interval. Without `SKIP`, the scheduler accumulates concurrent executions that cascade into an unbounded thread pool.
- **CaseFile keys are the plugin contract.** A plugin that reads an undefined key gets null silently. Document every key in `QuarkMindCaseFile` with a comment naming which plugin writes it and which reads it — this is the closest thing to a typed contract at the blackboard layer.

### Pattern to replicate (in another domain)

1. Add `casehub-engine` and `casehub-persistence-memory` dependencies
2. Create a `DomainCaseFile` class — one constant per CaseFile key; namespace by `domain.*` (observation) and `agent.*` (reasoning)
3. Create a `DomainStateTranslator` — maps your domain observation type to `Map<String, Object>` using CaseFile keys
4. Create plugin seam interfaces extending `TaskDefinition` — one per agent concern
5. Implement each plugin with `@ApplicationScoped @CaseType("your-case-type")` — reads from CaseFile, writes to CaseFile, no direct inter-plugin calls
6. Create a `DomainTaskRegistrar` that injects all plugin seam interfaces and registers them with `TaskDefinitionRegistry` at startup
7. Wire `AgentOrchestrator`: tick → translate → `caseEngine.createAndSolve("your-case-type", caseData, timeout)` → dispatch
8. Add `@Scheduled(concurrentExecution = SKIP)` if your domain has a fixed tick interval
9. Write `@QuarkusTest` that calls `orchestrator.gameTick()` directly with the scheduler disabled — verifies full plugin dispatch without timing coupling

---

## Layer 5 — Adaptive Plugin Selection (entryCriteria binding conditions)

**Completed:** 2026-06-03 (#157, commit 2d1dce8)
**Issue:** #157

**Key files:**
- `agent/AgentOrchestrator.java` — added `TickResult(CaseFile, TickTimings)` nested public record; `getLastTickResult()` exposes post-tick CaseFile; delegates execution to `GameTickExecutor`
- `agent/GameTickExecutor.java` — new package-private class; captures `CaseFile` returned by `createAndSolve()` (previously discarded); returns `TickResult` to orchestrator
- `plugin/DroolsTacticsTask.java` — `entryCriteria()` → `{READY, STRATEGY, NEAREST_THREAT}`; explicit `canActivate()` override
- `plugin/DroolsStrategyTask.java` — `entryCriteria()` → `{READY, ENEMY_ARMY_SIZE}`; C2 stub read; explicit `canActivate()` override
- `plugin/BasicTacticsTask.java`, `plugin/BasicStrategyTask.java` — same criteria changes for interface parity (non-CDI, direct-instantiation tests only)
- `sc2/mock/AdaptivePluginSelectionIT.java` — @QuarkusTest: 4 integration tests covering TickResult API, tactics gate (negative + positive), strategy ordering

### What it shows

`entryCriteria()` on each `TaskDefinition` declares CaseFile keys that must be present before the plugin activates. The CaseEngine evaluates criteria in a re-evaluation loop: after each task runs, any keys it wrote may unlock other tasks blocked on those keys. This creates a dependency ordering system within a single `createAndSolve()` call.

Two semantically distinct uses:

**Ordering dependency (`ENEMY_ARMY_SIZE` on StrategyTask):** scouting always writes this key (including 0 when no enemies are visible). Strategy is never actually skipped; it merely waits for scouting to run first in the re-evaluation cycle. The criterion formalises the intended data-flow order and prepares the criteria for C2 (#169), when StrategyTask will be refactored to read scouting-derived intel (`ENEMY_POSTURE`, `ENEMY_BUILD_ORDER`) rather than raw `ENEMY_UNITS`.

**Conditional gate (`NEAREST_THREAT` on TacticsTask):** scouting writes this key only when `!enemies.isEmpty()`. When no enemies are visible, the key is absent and TacticsTask is never scheduled — genuine adaptive skipping. In the early game, tactics never runs.

### Notable finding

`TaskDefinition.canActivate(CaseFile)` in the installed `casehub-core` snapshot unconditionally returns `true` — the default ignores `entryCriteria()`. All four affected plugin classes explicitly override `canActivate()`:

```java
// casehub-core TaskDefinition.canActivate() defaults to 'return true', ignoring
// entryCriteria(). Override required until the foundation corrects the default.
@Override
public boolean canActivate(CaseFile caseFile) {
    return entryCriteria().stream().allMatch(caseFile::contains);
}
```

Also: `CaseEngine.createAndSolve()` returns the pre-solve CaseFile (translator-written keys only; plugin-written keys are absent). Integration tests call `canActivate()` on injected CDI beans to verify gate semantics without depending on post-solve CaseFile content.

### Key wiring

```java
// entryCriteria() — what changed from L2
// Before L5 (all plugins):
@Override public Set<String> entryCriteria() { return Set.of(QuarkMindCaseFile.READY); }

// After L5 — StrategyTask (ordering dependency):
@Override public Set<String> entryCriteria() {
    return Set.of(QuarkMindCaseFile.READY, QuarkMindCaseFile.ENEMY_ARMY_SIZE);
}

// After L5 — TacticsTask (conditional gate):
@Override public Set<String> entryCriteria() {
    return Set.of(QuarkMindCaseFile.READY,
                  QuarkMindCaseFile.STRATEGY,
                  QuarkMindCaseFile.NEAREST_THREAT);
}

// canActivate() override — required on all four plugins (casehub-core default broken)
@Override
public boolean canActivate(CaseFile caseFile) {
    return entryCriteria().stream().allMatch(caseFile::contains);
}
```

```java
// TickResult — new test API surface on AgentOrchestrator
public record TickResult(CaseFile caseFile, TickTimings timings) {
    public boolean solveSucceeded() { return caseFile != null; }
}
public TickResult getLastTickResult() { return lastTickResult.get(); }

// Integration test pattern
orchestrator.gameTick();
AgentOrchestrator.TickResult result = orchestrator.getLastTickResult();
assertThat(result.caseFile().contains(QuarkMindCaseFile.NEAREST_THREAT)).isFalse();
assertThat(tacticsTask.canActivate(result.caseFile())).isFalse();
```

### Gotchas

- **`canActivate()` default is broken in casehub-core snapshot.** The default unconditionally returns `true`. Updating `entryCriteria()` alone has no runtime effect — an explicit `canActivate()` override is required per class. Verified by bytecode (`iconst_1; ireturn`).
- **`createAndSolve()` returns pre-solve CaseFile.** The returned `CaseFile` reflects translator-written keys only; plugin-written keys (`NEAREST_THREAT`, `STRATEGY`, `ENEMY_ARMY_SIZE`) are absent. Do not assert on post-solve plugin output through the returned CaseFile reference.
- **`NEAREST_THREAT` write is conditional in scouting.** `producedKeys()` on both scouting implementations declares `NEAREST_THREAT`, but the key is only written inside `if (!enemies.isEmpty())`. The key is absent — not empty — when no enemies are visible. Downstream criteria that gate on this key will genuinely block when the key is absent.

### Pattern to replicate (in another domain)

1. For each plugin, decide: is the gate a conditional (only relevant in some states) or an ordering constraint (always relevant, just sequenced)?
2. For ordering: add the key produced by the prerequisite plugin to `entryCriteria()`. The re-evaluation loop handles sequencing automatically.
3. For conditional gating: ensure the prerequisite plugin writes the key *only* when the condition is met — never writes it as a sentinel value. Absence (not a null value) is the gate mechanism.
4. Override `canActivate()` explicitly until the casehub-core default is fixed.
5. Extract tick execution into a testable unit (`GameTickExecutor` pattern) to surface the `CaseFile` for test assertions without depending on async plugin output.

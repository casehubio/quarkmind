# QuarkMind → casehub-engine Migration Design

**Date:** 2026-06-13
**Status:** Approved
**Replaces:** casehub-poc dependency (`casehub-core:1.0.0-SNAPSHOT`, `casehub-persistence-memory:1.0.0-SNAPSHOT`)
**Engine epic:** casehubio/engine#490

---

## Context

QuarkMind currently depends on `casehub-poc` (`mdproctor/casehub`) — a retired proof-of-concept that predates the production `casehub-engine`. The poc provided `TaskDefinition`, `CaseEngine.createAndSolve()`, `CaseFile`, and `@CaseType`. The production engine has a fundamentally different (and richer) API.

This migration removes the poc dependency entirely and re-integrates QuarkMind against the production engine. It is also an opportunity: QuarkMind's game-loop granularity drove the design of four new engine capabilities that are broadly useful beyond QuarkMind (engine#490 epic).

---

## What casehub-poc actually did in QuarkMind

Before designing the migration it is worth being precise about what the poc was actually used for. `CaseEngine.createAndSolve()` was called every 500ms (game tick). Each call:

1. Created a fresh `CaseFile` with the current tick's game state as initial data
2. Ran scouting → strategy → tactics → economics in dependency order (via `entryCriteria()` chaining)
3. Returned the populated `CaseFile` with plugin results (intents, strategy decisions, scouting intel)
4. Discarded the `CaseFile` — no state carried across ticks

The poc was used purely as a **dependency-ordered task executor**. Stages, Milestones, child CaseFiles, and the PoisonPill/DLQ resilience stack were never used. The architecture supported far more; QuarkMind used almost none of it.

---

## Design

### 1. Execution model

**Root case per game.** `QuarkMindCaseHub` (`@ApplicationScoped`, extends `CaseHub`) manages one durable case per game:

```java
// At game start (AgentOrchestrator.startGame):
UUID gameSessionId = quarkMindCaseHub.startCase(initialGameData).toCompletableFuture().get();

// At game end (AgentOrchestrator.stopGame):
quarkMindCaseHub.cancelCase(gameSessionId);
```

The game session case is persistent in-memory across ticks. The `CaseContext` IS the game blackboard — plugin results written in tick N are visible in tick N+1 without re-injection.

**Per-tick execution via `signalAndAwait()` (engine#483).** `GameTickExecutor` changes from:

```java
// Before (casehub-poc)
CaseFile caseFile = caseEngine.createAndSolve("rts-game", caseData, Duration.ofSeconds(5));

// After (casehub-engine)
CaseContext ctx = quarkMindCaseHub.signalAndAwaitSync(
    gameSessionId, "game.state", gameState, Duration.ofSeconds(5));
engine.dispatch(ctx.getAs(QuarkMindCaseFile.INTENT_QUEUE, IntentQueue.class));
```

`signalAndAwait()` signals the context change and blocks until all workers triggered by that signal have settled (quiescence), then returns the updated `CaseContext`.

**Persistent blackboard benefit.** Scouting intel, trust scores, and strategy state accumulate in `CaseContext` across ticks. The `ENEMY_ARMY_SIZE` ordering hack that existed because there was no cross-tick memory is no longer needed.

### 2. Per-tick plugin ordering via `SequenceWorker` (engine#484)

Plugin ordering previously relied on `entryCriteria()` dependency chains (the `ENEMY_ARMY_SIZE` hack). In the new model, ordering is explicit via `SequenceWorker`:

```java
Worker tickDecision = SequenceWorker.of(
    scoutingWorker,    // DroolsScoutingTask
    strategyWorker,    // DroolsStrategyTask
    tacticsWorker,     // DroolsTacticsTask
    economicsWorker    // FlowEconomicsTask
);
```

The `QuarkMindCaseHub.getDefinition()` binds this sequence to `ContextChangeTrigger("game.state")`:

```java
CaseDefinition.builder()
    .namespace("quarkmind").name("rts-game").version("1.0")
    .workers(tickDecision)
    .binding(Binding.on(new ContextChangeTrigger("game.state")).target(tickDecision))
    .build();
```

Each tick's `signalAndAwait("game.state", ...)` activates the sequence. The sequence runs inline — scouting always precedes strategy, strategy precedes tactics. Clean, structural, no hacks.

### 3. `TaskDefinition` sugar and `@CaseType`

The engine gains a `TaskDefinition` interface in `casehub-engine-api` as migration sugar (and a permanent ergonomic API for Java-native plugin authors):

```java
public interface TaskDefinition {
    String getId();
    default Set<String> requires() { return Set.of(); }              // entry conditions
    default Predicate<CaseContext> activateIf() { return ctx -> true; } // additional gate
    void execute(CaseContext ctx);
    default Set<String> produces() { return Set.of(); }              // documentation only

    default Worker toWorker() { ... }
    default Binding toBinding(String trigger) { ... }
}
```

QuarkMind plugin seam interfaces (`StrategyTask`, `ScoutingTask`, etc.) extend `TaskDefinition`. Existing plugin implementations change minimally:

| Old | New |
|-----|-----|
| `implements StrategyTask` | unchanged |
| `CaseFile` parameter | `CaseContext` parameter |
| `entryCriteria()` | `requires()` |
| `canActivate(CaseFile)` | `activateIf()` returning `Predicate<CaseContext>` |
| `execute(CaseFile)` | `execute(CaseContext)` |

The redundant `entryCriteria().stream().allMatch(caseFile::contains)` re-check inside `canActivate()` is deleted — the engine evaluates `requires()` and `activateIf()` as separate clean gates.

`@CaseType` becomes a plain metadata annotation (not a CDI qualifier — that was the poc's approach):

```java
@Target(TYPE) @Retention(RUNTIME)
public @interface CaseType { String value(); }
```

Plugin implementations keep their `@CaseType("rts-game")` annotation. `QuarkMindCaseHub` uses CDI `Instance<TaskDefinition>` to collect them:

```java
@ApplicationScoped
public class QuarkMindCaseHub extends CaseHub {
    @Inject @Any Instance<TaskDefinition> allTaskDefs;

    @Override
    public CaseDefinition getDefinition() {
        var plugins = allTaskDefs.stream()
            .filter(td -> hasAnnotation(td, "rts-game"))
            .toList();
        Worker tickDecision = SequenceWorker.of(
            find(plugins, "scouting"), find(plugins, "strategy"),
            find(plugins, "tactics"),  find(plugins, "economics"));
        return CaseDefinition.builder()
            .namespace("quarkmind").name("rts-game").version("1.0")
            .workers(tickDecision)
            .binding(Binding.on(new ContextChangeTrigger("game.state")).target(tickDecision))
            .build();
    }
}
```

`QuarkMindTaskRegistrar` is deleted. `TaskDefinitionRegistry` and `CircularDependencyException` are deleted.

### 4. Multi-tick objectives as sub-cases

Workers that commit to a strategic objective spawn sub-cases via `WorkerRuntime` (engine#485):

```java
// Inside a strategy worker that decides to expand:
void execute(CaseContext ctx, WorkerRuntime runtime) {
    if (shouldExpand(ctx)) {
        runtime.spawn("economy-expansion", ctx.getData());
        ctx.set("agent.objective", "economy-expansion");
    }
}
```

The sub-case runs asynchronously across ticks. When it completes, `SubCaseCompletionService` merges its result into the root `CaseContext` atomically (`PlanItemCompletionApplier`, `@Transactional`). The next tick's SequenceWorker sees the result without explicit wiring.

Multi-step objectives use `SequenceWorker` at the sub-case level:

```java
Worker expansionPlan = SequenceWorker.of(
    Step.worker(buildPylonWorker),
    Step.subCase("nexus-construction"),   // spawns sub-case, awaits completion
    Step.worker(economicRampWorker)
);
```

This is the lightweight alternative to Quarkus Flow for linear plans that do not require durability, compensation, or branching.

**CBR anchor.** Sub-case UUIDs are linked to the root game session case via the parent-child graph. At game end, `GameOutcomeRecorder` writes a ledger attestation on the root case. CBR retrieval can walk the case hierarchy to reconstruct which objectives were pursued in past games with similar feature vectors (casehubio/quarkmind#192).

### 5. Repeatable Stage (engine#482)

Per-tick execution via `signalAndAwait()` (Section 1) is the immediate migration target. Once engine#482 lands, each tick can optionally be a **repeatable Stage** — a named, observable Stage instance in the event log, re-activated on each `ContextChangeTrigger("game.state")`:

```java
Stage.builder()
    .name("tick-decision")
    .on(new ContextChangeTrigger("game.state"))
    .repeatable(true)
    .autocomplete(true)
    .workers(tickDecision)
    .build()
```

Each tick then appears in the event log as `STAGE_ACTIVATED(tick-decision, instance=N)` → `STAGE_COMPLETED(tick-decision, instance=N)`. This is the CBR anchor for per-tick state retrieval. Adopt this once engine#482 is available; `signalAndAwait()` alone is sufficient for initial migration.

---

## Migration path

### Phase 1 — Plugin API migration (independent of engine#490)

Plugin implementations can be migrated before the engine issues land. The changes are mechanical:

1. Replace `CaseFile` with `CaseContext` in all plugin execute methods and test helpers
2. Replace `entryCriteria()` with `requires()`
3. Replace `canActivate(CaseFile)` with `activateIf()`
4. Replace `PropagationContext` import from `io.casehub.coordination` → `io.casehub.api.context`
5. Update test helpers: `new InMemoryCaseFile(...)` → engine's in-memory `CaseContext` test factory

### Phase 2 — Wire `QuarkMindCaseHub` (requires engine#483, #484)

1. Implement `QuarkMindCaseHub extends CaseHub`
2. Replace `QuarkMindTaskRegistrar` with `QuarkMindCaseHub.getDefinition()`
3. Replace `caseEngine.createAndSolve()` in `GameTickExecutor` with `signalAndAwaitSync()`
4. Add `startCase()` to `AgentOrchestrator.startGame()`, `cancelCase()` to `stopGame()`

### Phase 3 — Sub-case objectives (requires engine#485)

1. Wire `WorkerRuntime` into strategy worker for objective spawning
2. Implement first sub-case type: `economy-expansion`
3. Verify `SubCaseCompletionService` merge back into root context

### Phase 4 — Repeatable Stage (requires engine#482, optional)

Upgrade per-tick binding to use repeatable Stage for full event log observability. Not a migration blocker — Phase 2 is fully functional without it.

---

## Files deleted

| File | Reason |
|---|---|
| `QuarkMindTaskRegistrar.java` | Replaced by `QuarkMindCaseHub.getDefinition()` |

## Dependencies removed from pom.xml

| Artifact | Replaced by |
|---|---|
| `casehub-core:1.0.0-SNAPSHOT` | `casehub-engine-api`, `casehub-engine-blackboard` |
| `casehub-persistence-memory:1.0.0-SNAPSHOT` | Engine's in-memory persistence (included transitively) |

## Engine issues required

| Issue | Needed for |
|---|---|
| engine#483 `signalAndAwait()` | Phase 2 — `GameTickExecutor` |
| engine#484 `SequenceWorker` | Phase 2 — tick ordering |
| engine#485 `WorkerRuntime` | Phase 3 — sub-case objective spawning |
| engine#482 Repeatable Stage | Phase 4 — full event log observability (optional) |

All four are children of engine#490.

---

## Case type string

The spec uses `"rts-game"` as the case type name throughout. The current codebase uses `"starcraft-game"`. Renaming the string is **deferred to quarkmind#74** (genericise unit/building definitions — trademark removal). This migration keeps `"starcraft-game"` as-is; quarkmind#74 renames it as part of the broader trademark cleanup.

---

## What is not in scope

- Genericising `UnitType` enum and SC2-specific types (including case type string rename) → quarkmind#74
- CBR reference implementation (`CaseMemoryStore`, `CaseRetriever`) → quarkmind#192
- LLM advisory team, Commentator/Coach → quarkmind#180–183

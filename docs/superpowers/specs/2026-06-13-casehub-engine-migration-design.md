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

`CaseEngine.createAndSolve()` was called every 500ms (game tick). Each call created a fresh `CaseFile` with the current tick's game state, ran scouting → strategy → tactics → economics in dependency order, returned the populated `CaseFile`, then discarded it. No state carried across ticks. The poc was used purely as a **dependency-ordered task executor**. Stages, Milestones, child CaseFiles, and the resilience stack were never used.

---

## Design

### 1. Execution model

**Root case per game.** `QuarkMindCaseHub` (`@ApplicationScoped`, extends `CaseHub`) manages one durable case per game:

```java
// At game start (AgentOrchestrator.startGame):
UUID gameSessionId = quarkMindCaseHub.startCase(initialData)
    .toCompletableFuture().get(5, SECONDS);

// At game end (AgentOrchestrator.stopGame):
quarkMindCaseHub.cancelCase(gameSessionId);
```

The `CaseContext` is the persistent blackboard — it accumulates state across ticks (agent intel, strategy state, scouting output) without re-injection each tick.

**Per-tick execution via bulk `signalAndAwait()` (engine#483).** The `signal(UUID, String, Object)` API sets a single path. QuarkMind needs to update ~12 flat keys each tick and trigger evaluation exactly once. Engine#483 must therefore include a bulk variant:

```java
// New API on CaseHubRuntime:
CaseContext signalAndAwaitSync(UUID caseId, Map<String,Object> updates, Duration timeout);
```

This atomically applies `ctx.setAll(updates)` (writing all flat keys) then fires exactly one `CaseContextChangedEvent`, blocks until settlement, and returns the updated context. `GameTickExecutor` becomes:

```java
// Each tick:
Map<String, Object> updates = translator.toMap(gameState);   // flat key map
caseHub.signalAndAwaitSync(gameSessionId, updates, Duration.ofSeconds(5));
engine.dispatch();  // drains CDI-injected IntentQueue directly
```

`GameStateTranslator` is **unchanged**. `QuarkMindCaseFile` constants (`"game.resources.minerals"` etc.) are **unchanged** — `CaseContext.getAs(key, Class)` accesses flat string keys at the top level, compatible with the existing dotted naming convention. `IntentQueue` remains a CDI bean and is drained directly by `engine.dispatch()` — it is never stored in `CaseContext`.

**Settlement detection in engine#483.** `signalAndAwait()` cannot simply fire a signal and wait for quiescence — the worker execution is async and there is no built-in correlation between a signal invocation and the plan items it creates. Engine#483 must introduce a generation counter: each `CaseContextChangedEvent` carries a generation tag; plan items created during that evaluation carry the same tag; `signalAndAwait()` resolves when all plan items of the triggered generation have completed. This must be specified in engine#483.

**Concurrent-tick safety.** If tick N's plan items are still RUNNING when tick N+1 fires, `addPlanItemIfAbsent` rejects the new plan item (RUNNING blocks re-addition; COMPLETED does not). At 500ms intervals with in-process lambda workers this should not occur. `AgentOrchestrator.gameTick()` carries `@Scheduled(concurrentExecution = SKIP)` which prevents concurrent invocation — this is the structural guard. The spec notes this constraint: `signalAndAwait()` must complete before the next tick signal is sent.

**ContextChangeTrigger expression.** The `ContextChangeTrigger(String filter)` constructor takes a JQ expression, not a key name. The expression `"game.state"` selects the value at that path — truthy when non-null, which means it fires on every context write after tick 1, not just per-tick signals. The correct expression for the per-tick binding is:

```
.["game.frame"] | . != null
```

`game.frame` is always included in the update map and increments every tick. `applyAndDiff` detects the change → fires `CaseContextChangedEvent`. Agent writes (scouting intel, strategy keys) do not change `game.frame` → do not re-trigger the binding.

### 2. Plugin ordering via `SequenceWorker` (engine#484)

Plugin ordering previously relied on the `ENEMY_ARMY_SIZE` entryCriteria hack. In the new model, ordering is explicit via `SequenceWorker`. The sequence must include **all three** strategy implementations (trust-weighted selection), with `StrategyTrustRouter` promoted to an explicit sequence step that runs before them:

```java
Worker tickDecision = SequenceWorker.of(
    scoutingWorker,                     // DroolsScoutingTask
    trustRoutingWorker,                 // StrategyTrustRouter — writes selected strategy to context
    earlyPressureStrategyWorker,        // activateIf() checks selected strategy id
    economicExpansionStrategyWorker,    // activateIf() checks selected strategy id
    droolsStrategyWorker,               // activateIf() checks selected strategy id (default)
    tacticsWorker,                      // DroolsTacticsTask
    economicsWorker                     // FlowEconomicsTask
);
```

**SequenceWorker skip-and-continue semantics (required in engine#484).** Before calling `execute()` on each step, SequenceWorker evaluates `activateIf()`. If the predicate returns false, the step is skipped and execution continues to the next step. It does NOT halt. This is the mechanism by which exactly one strategy implementation runs per tick: `StrategyTrustRouter` writes the selected strategy ID to context, then each strategy plugin's `activateIf()` returns true only for the selected ID.

This replaces the implicit CDI observer pattern (`StrategyTrustObserver`) with an explicit, ordered, event-log-visible step. Trust routing becomes structural, not hidden.

**CDI injection in workers.** `TaskDefinition.toWorker()` creates a lambda `(ctx) -> { this.execute(ctx); return Map.of(); }` where `this` is the CDI proxy obtained via `Instance<TaskDefinition>`. When the engine invokes this lambda, the CDI proxy dispatches to the `@ApplicationScoped` scoped instance with all `@Inject` fields populated (StrategySelector, ScoutingIntelBroker, Event<PluginDecisionEvent>, etc.). CDI context is intact because the lambda closure captures the proxy, not the raw bean instance.

The returned `Map.of()` is empty — correct for void-returning plugins that write their output to `CaseContext` directly via `ctx.set()` during `execute()`. The engine's `WorkflowExecutionCompletedHandler` applies the returned map to context; an empty map produces an empty diff and no additional write.

### 3. `TaskDefinition` sugar and `@CaseType`

`TaskDefinition` lives in **QuarkMind**, not `casehub-engine-api`. The engine's `Worker(name, caps, Function<CaseContext, Map>)` is already Java-native and sufficient for engine consumers. `TaskDefinition` is a QuarkMind migration convenience; it lacks the cross-domain evidence needed to be a platform API. If AML, clinical, or devtown find it useful, it is promoted then.

```java
// In io.quarkmind.agent — QuarkMind's own interface
public interface TaskDefinition {
    String getId();
    default Set<String> requires() { return Set.of(); }
    default Predicate<CaseContext> activateIf() { return ctx -> true; }
    void execute(CaseContext ctx);
    default Set<String> produces() { return Set.of(); }       // documentation only
    default Worker toWorker() { /* lambda over execute() */ }
    default Binding toBinding(String triggerExpression) { /* wraps requires() + activateIf() */ }
}
```

`@CaseType` similarly stays in QuarkMind as a plain (non-CDI-qualifier) metadata annotation. `QuarkMindCaseHub` collects plugins via `Instance<TaskDefinition>` and assembles the `CaseDefinition` programmatically.

**Complete @CaseType CDI qualifier removal.** Every injection point `@Inject @CaseType("starcraft-game") X x` must be updated. The full list:

| File | Pattern | Replacement |
|---|---|---|
| `QuarkMindTaskRegistrar` | `@CaseType` qualified injection × 4 | **Deleted** — replaced by `QuarkMindCaseHub.getDefinition()` |
| `DroolsTacticsTaskIT` | `@Inject @CaseType TacticsTask` | Inject concrete type `DroolsTacticsTask` |
| `DroolsScoutingTaskIT` | `@Inject @CaseType ScoutingTask`, `DroolsScoutingTask` | Inject concrete types |
| `ScoutingConfigResource` | `@Inject @CaseType DroolsScoutingTask` | Inject `DroolsScoutingTask` directly (no qualifier) |
| `TrustWeightedStrategyIT` and related | `@Inject @CaseType StrategyTask` | Inject `DroolsStrategyTask` directly (specific impl is the subject under test — CLAUDE.md already documents this for L6 tests) |
| `AdaptivePluginSelectionIT` | `@CaseType` injection | Inject concrete types |

All plugin implementations retain `@CaseType("starcraft-game")` as a plain metadata annotation (for `QuarkMindCaseHub` discovery). They lose CDI qualifier semantics — the annotation is no longer interpreted by Arc.

Arc bean pruning note (from LAYER-LOG.md): Arc removes unused `@ApplicationScoped` beans. `QuarkMindTaskRegistrar` existed to keep plugin beans alive via injection. Its replacement: `QuarkMindCaseHub.getDefinition()` iterates `Instance<TaskDefinition>` which activates all discovered beans, preventing pruning.

### 4. Key structural changes to QuarkMindCaseHub

```java
@ApplicationScoped
public class QuarkMindCaseHub extends CaseHub {
    @Inject @Any Instance<TaskDefinition> allTaskDefs;

    @Override
    public CaseDefinition getDefinition() {
        List<TaskDefinition> plugins = allTaskDefs.stream()
            .filter(td -> hasAnnotation(td, "starcraft-game"))
            .toList();

        Worker tickDecision = SequenceWorker.of(
            find(plugins, "scouting"),
            find(plugins, "trust-routing"),
            find(plugins, "strategy-early-pressure"),
            find(plugins, "strategy-economic-expansion"),
            find(plugins, "strategy-drools"),
            find(plugins, "tactics"),
            find(plugins, "economics")
        );

        return CaseDefinition.builder()
            .namespace("quarkmind").name("starcraft-game").version("1.0")
            .workers(tickDecision)
            .binding(Binding.builder()
                .name("tick-decision")
                .on(new ContextChangeTrigger(".[\\"game.frame\\"] | . != null"))
                .target(new CapabilityTarget(tickDecision.getCapabilities().get(0)))
                .build())
            .build();
    }
}
```

`QuarkMindTaskRegistrar` is deleted. `TaskDefinitionRegistry` and `CircularDependencyException` are deleted.

---

## Migration path

### Phase 1 — Plugin API migration (independent of engine#490)

Mechanical changes, implementable now against existing engine API:

1. Replace `io.casehub.core.CaseFile` with `io.casehub.api.context.CaseContext` in all plugin execute methods and test helpers. Use `MapCaseFile` (the engine's existing migration shim in `casehub-engine-blackboard`) in tests where poc-compatible `put(key, value)` semantics are needed — it is already available in the engine jar.
2. Replace `entryCriteria()` with `requires()` on `TaskDefinition`
3. Replace `canActivate(CaseFile)` with `activateIf()` returning `Predicate<CaseContext>`
4. Replace `PropagationContext` import from `io.casehub.coordination` → `io.casehub.api.context` (same semantics, API superset)
5. `StrategyTrustRouter` becomes a `TaskDefinition` implementation with `getId()` = `"trust-routing"`, `execute()` writing the selected strategy ID to context

### Phase 2 — Wire QuarkMindCaseHub (requires engine#483 bulk variant + engine#484 skip-and-continue)

1. Implement `QuarkMindCaseHub extends CaseHub` with `getDefinition()` as above
2. Delete `QuarkMindTaskRegistrar`
3. Replace `GameTickExecutor.caseEngine.createAndSolve()` with `caseHub.signalAndAwaitSync(gameSessionId, translator.toMap(gameState), timeout)`
4. Add `startCase()` to `AgentOrchestrator.startGame()`, `cancelCase()` to `stopGame()`
5. Update all `@Inject @CaseType(...)` injection points per the table in Section 3
6. Update tests: inject concrete types rather than using CDI qualifier

**Phase 2 dependencies:** engine#483 must include the bulk `signalAndAwaitSync(UUID, Map, Duration)` variant AND the generation-counter settlement mechanism. Engine#484 must include skip-and-continue step semantics. Without both, Phase 2 cannot complete.

Phase 2 does NOT require engine#482 (Repeatable Stage). The plan item lifecycle already supports per-tick re-firing via `addPlanItemIfAbsent` (COMPLETED items do not block re-addition).

---

## Future architecture (pending separate design specs)

The following capabilities are architecturally sound but depend on engine issues that have no concrete API yet. They are NOT part of this migration. Each requires its own design spec once the engine API is settled.

### Future Phase A — Sub-case objectives (pending engine#485)

Workers that commit to a strategic objective (expand, timing attack) spawn a sub-case via `WorkerRuntime`. The sub-case runs asynchronously across ticks. When complete, its result is merged back into the root CaseContext via `SubCaseCompletionService` (already `@Transactional`). Multi-step objectives use `SequenceWorker` at the sub-case level:

```java
Worker expansionPlan = SequenceWorker.of(
    Step.worker(buildPylonWorker),
    Step.subCase("nexus-construction"),   // spawns sub-case, awaits
    Step.worker(economicRampWorker)
);
```

Design spec required once engine#485 has a concrete API surface.

### Future Phase B — Repeatable Stage event log anchoring (pending engine#482)

Per-tick execution via bulk `signalAndAwait()` (Phase 2) is fully functional without Repeatable Stage. Once engine#482 lands, each tick can optionally be a named Stage instance in the event log — valuable for CBR retrieval of similar past game states. Not a migration blocker.

---

## Files deleted

| File | Reason |
|---|---|
| `QuarkMindTaskRegistrar.java` | Replaced by `QuarkMindCaseHub.getDefinition()` |

## Dependencies removed from pom.xml

| Artifact | Replaced by |
|---|---|
| `casehub-core:1.0.0-SNAPSHOT` | `casehub-engine-api`, `casehub-engine-blackboard` |
| `casehub-persistence-memory:1.0.0-SNAPSHOT` | Engine's in-memory persistence (transitively included) |

## Engine issues required for Phase 2

| Issue | Required capability |
|---|---|
| engine#483 | Bulk `signalAndAwaitSync(UUID, Map, Duration)` + generation-counter settlement |
| engine#484 | `SequenceWorker` + skip-and-continue step evaluation |

engine#482 and engine#485 are future architecture, not Phase 2 blockers.

---

## What is not in scope

- Genericising `UnitType` enum, `SC2Data` switches, and the `"starcraft-game"` case type string → quarkmind#74 (trademark removal)
- CBR reference implementation (`CaseMemoryStore`, `CaseRetriever`) → quarkmind#192
- LLM advisory team, Commentator/Coach → quarkmind#180–183

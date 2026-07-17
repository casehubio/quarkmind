# CBR Reference Implementation — Design Spec

**Issue:** #192
**Date:** 2026-07-17
**Branch:** issue-192-cbr-reference-impl

## Summary

Implement the full CBR (Case-Based Reasoning) cycle for strategy selection in QuarkMind: Retain (store game outcomes as structured cases), Retrieve (find similar past games), Reuse (use past experience to inform strategy routing). Replaces the application-layer trust routing workaround (`StrategyTrustRouter`, `StrategyTrustObserver`, `StrategySelector`) with engine SPI integration and CaseFile-mediated state.

## Design Approach: In-Chain Router with CaseFile State

### Root insight

Routing (which strategy?) and execution (run the selected strategy) are separate concerns. Routing happens infrequently (game start, archetype change). Execution happens every tick. The engine's `ImplementationRoutingStrategy` defines the routing contract. The tick chain handles execution. The CaseFile mediates between them.

A single `SC2StrategyRouterTask` replaces three classes by sitting in the tick chain between scouting and strategy phases. It detects archetype changes, retrieves CBR cases, calls the routing strategy, and writes the decision to the CaseContext. Strategy tasks read the decision via `activateIf()`. No mutable app-layer state — the CaseFile is the complete source of truth.

### Why not engine-managed routing?

The engine's plan-creation pipeline is async and capability-scoped. Strategy selection must be synchronous and mid-tick (after scouting, before strategy execution). Forcing strategy into the engine's capability model creates timing races and execution model changes. Instead, we use the engine's `ImplementationRoutingStrategy` SPI as the routing contract — demonstrating the interface — while the tick chain handles the lifecycle.

## Components

### 1. SC2GameCbrCase

Domain `CbrCase` implementation capturing a strategy game case.

```java
record SC2GameCbrCase(
    String problem,                        // "vs ZERG_ROACH_RUSH (PvZ)"
    String solution,                       // "strategy.early-pressure"
    String outcome,                        // "WIN" / "LOSS" / null
    Double confidence,                     // pattern assessment confidence
    Map<String, FeatureValue> features
) implements CbrCase
```

`cbrType()` returns `"sc2-strategy"`.

Feature schema (registered at startup via `SC2CbrSchemaRegistrar`):

| Feature | Type | Weight | Source |
|---------|------|--------|--------|
| `enemy_archetype` | Categorical | 0.5 | `EnemyPatternAssessment.archetype()` |
| `enemy_race` | Categorical | 0.15 | `EnemyArchetype.race()` |
| `matchup` | Categorical | 0.15 | derived (our race vs enemy race) |
| `assessment_confidence` | Numeric [0,1] | 0.2 | `EnemyPatternAssessment.confidence()` |

**Evolution from #192:** Issue #192 sketched the feature vector using `opponent_posture` (0.5) + `enemy_build_order` (0.35) + `opponent_context` (0.15). Since then, `EnemyArchetype` was introduced — each enum value (e.g., `ZERG_ROACH_RUSH`) encodes race, build order, and implied posture in a single discriminator. The spec uses archetype as the primary feature with race and matchup as secondary facets, and adds `assessment_confidence` as a retrieval-quality signal. `army_size_ratio` (tactical context that varies within a game) was dropped in favor of `assessment_confidence` (a cross-game discriminator measuring classification reliability).

Package: `io.quarkmind.agent.cbr`

### 2. SC2CbrRetentionObserver

`implements CaseOutcomeObserver` — engine calls `onOutcome(CaseOutcomeEvent)` at case close.

- Extracts from `caseFileSnapshot()` using well-known CaseFile keys:
  - `STRATEGY_SELECTED_ID` → `String` — the strategy that was selected
  - `STRATEGY_ROUTED_ARCHETYPE` → `String` — archetype name at routing time (written by `SC2StrategyRouterTask`)
  - `STRATEGY_ROUTED_CONFIDENCE` → `Double` — assessment confidence at routing time
  - Derives `enemy_race` from `EnemyArchetype.valueOf(archetype).race()`
  - Derives `matchup` from archetype race vs. Protoss (our fixed race)
- Builds `SC2GameCbrCase` with features and outcome set (`withOutcome()`)
- Stores and records outcome in sequence:
  ```java
  String storedCaseId = cbrStore.store(
      cbrCase,                                      // the SC2GameCbrCase
      event.tenancyId(),                             // tenantId
      event.caseId().toString(),                     // entityId — the game case
      new MemoryDomain("quarkmind"),                 // domain
      "sc2-cbr-retention",                           // sourceId
      "game",                                        // scope
      Path.of("quarkmind", "strategy", "cases"));    // path
  cbrStore.recordOutcome(storedCaseId, "sc2-strategy", outcome);
  ```
- Outcome mapping:
  - WIN → `CbrOutcome.of(1.0, "WIN", event.closedAt())`
  - LOSS → `CbrOutcome.of(0.0, "LOSS", event.closedAt())`
  - TIE → `CbrOutcome.of(0.5, "TIE", event.closedAt())`
  - UNKNOWN → skip (no case stored, no `recordOutcome()`)

Package: `io.quarkmind.agent.cbr`

### 3. SC2CbrSchemaRegistrar

`@Startup` bean. Calls `cbrCaseMemoryStore.registerSchema()` with the four-field `CbrFeatureSchema` for case type `"sc2-strategy"`.

Package: `io.quarkmind.agent.cbr`

### 4. SC2ImplementationRoutingStrategy

`implements ImplementationRoutingStrategy` — trust + CBR blending. Not CDI-managed — manually constructed by `SC2StrategyRouterTask`:

```java
this.routingStrategy = new SC2ImplementationRoutingStrategy(classifier, scoreSource, policyProvider);
```

Constructor parameters (CDI-injected into `SC2StrategyRouterTask`, passed through):
- `TrustCandidateClassifier classifier` — engine's trust phase classifier (reused, not reimplemented)
- `TrustScoreSource scoreSource` — engine's trust score data source
- `TrustRoutingPolicyProvider policyProvider` — QuarkMind's capability-specific policy provider

`id()` returns `"sc2-cbr-routing"`.

`select(ImplementationRoutingContext context, List<ImplementationCandidate> candidates)` returns `Uni<ImplementationSelection>`:

1. **Trust classification** — delegates to `TrustCandidateClassifier.classify()`:
   - Converts `ImplementationCandidate` list to `AgentCandidate` list (same mapping as `TrustWeightedImplementationRoutingStrategy`: `new AgentCandidate(c.workerName(), Set.of(c.capabilityName()), 0, AgentHealth.READY, null, null)`)
   - Classifies each into BOOTSTRAP, QUALIFIED, BORDERLINE, EXCLUDED_PHASE2B, or EXCLUDED_PHASE3

2. **Trust score** from classification (matching engine's scoring model):
   - BOOTSTRAP → workload score
   - QUALIFIED → `trustScore * blendFactor + workloadScore * (1 - blendFactor)`
   - BORDERLINE → 0.01 for fallback binding, 0.0 otherwise
   - EXCLUDED_PHASE2B / EXCLUDED_PHASE3 → 0.0

3. **CBR experience weight** from `context.experiences()`:
   - Group by `solution` (binding name = strategy task ID)
   - Per candidate: `experienceWeight = Σ(similarityScore × outcomeValue) / Σ(similarityScore)`
   - outcomeValue: WIN=1.0, LOSS=0.0, TIE=0.5
   - No matching experiences → 0.5 (neutral)

4. **Blend**: `finalScore = (1 - cbrWeight) × trustScore + cbrWeight × experienceWeight`
   - `cbrWeight` read from `TrustRoutingPolicy.cbrWeight()` — set to 0.4 in `QuarkMindTrustRoutingPolicyProvider` for the `"strategy"` capability
   - Cold start (no experiences) → effectively trust-only (experienceWeight = 0.5 neutral)

5. **Select**: highest `finalScore` → `ImplementationSelection.Selected(List.of(winner.bindingName()))`
   - Ties → fallback binding from policy (`"strategy.drools"`)
   - All-zero → fallback

Package: `io.quarkmind.agent.cbr`

### 5. SC2StrategyRouterTask

`implements TaskDefinition` — ID `"strategy-routing.cbr"`. Sits in the tick chain between scouting and strategy phases.

Dependencies (CDI-injected):
- `@Inject @Any Instance<StrategyTask> strategyTasks` — discovered strategy candidates
- `@Inject TrustCandidateClassifier classifier`
- `@Inject TrustScoreSource scoreSource`
- `@Inject TrustRoutingPolicyProvider policyProvider`
- `@Inject CbrCaseMemoryStore cbrStore`
- `@Inject ScoutingIntelBroker broker`
- `@ConfigProperty(name = "quarkmind.strategy.routing.confidence-threshold", defaultValue = "0.6") double confidenceThreshold`
- `@ConfigProperty(name = "quarkmind.strategy.routing.max-pivots", defaultValue = "1") int maxPivots`

The `SC2ImplementationRoutingStrategy` is constructed once in `@PostConstruct`:
```java
this.routingStrategy = new SC2ImplementationRoutingStrategy(classifier, scoreSource, policyProvider);
```

Per-tick logic:
1. Read `STRATEGY_ROUTED_CONTEXT` from ctx (persisted from prior tick via CaseFile)
2. Read enemy archetype from broker (`ScoutingIntelType.PATTERN_ASSESSMENT`)
3. If no archetype yet → write `STRATEGY_SELECTED_ID` = fallback (`strategy.drools`), return
4. **Confidence gate:** If `assessment.confidence() < confidenceThreshold` → return (keep current selection, don't route on uncertain classification)
5. Build context key from archetype + race + matchup
6. If context key == `STRATEGY_ROUTED_CONTEXT` → return (no change, selection persists)
7. **Pivot limit:** Read `STRATEGY_PIVOT_COUNT` from ctx (default 0). If `pivotCount >= maxPivots` → return (strategy locked for remainder of game)
8. Retrieve top-5 similar past games from `CbrCaseMemoryStore` via `CbrQuery`
9. Convert `ScoredCbrCase` results to `List<RetrievedExperience>`
10. Build `ImplementationCandidate` list from discovered `StrategyTask` beans:
    ```java
    strategyTasks.stream()
        .map(t -> new ImplementationCandidate(t.getId(), t.getId(), "strategy"))
        .toList()
    ```
11. Build `ImplementationRoutingContext` with caseId, `"strategy"`, caseContext JSON, tenancyId, experiences
12. Call `routingStrategy.select(context, candidates).await().indefinitely()` — safe: local strategy completes synchronously without IO
13. Extract winning binding name from `ImplementationSelection.Selected`
14. Write `STRATEGY_SELECTED_ID` = winner to ctx
15. Write `STRATEGY_ROUTED_CONTEXT` = context key to ctx
16. Write `STRATEGY_ROUTED_ARCHETYPE` = archetype name to ctx
17. Write `STRATEGY_ROUTED_CONFIDENCE` = confidence to ctx
18. Increment and write `STRATEGY_PIVOT_COUNT` to ctx

All keys persist in CaseFile via engine mutation merge. Zero mutable app-layer state.

CBR query construction:
```java
CbrQuery.of(tenantId, domain, scope, "sc2-strategy", features, 5)
    .withWeights(Map.of(
        "enemy_archetype", 0.5,
        "enemy_race", 0.15,
        "matchup", 0.15,
        "assessment_confidence", 0.2))
    .withMinSimilarity(0.3)
    .withRetrievalMode(RetrievalMode.HYBRID)
```

Package: `io.quarkmind.agent.cbr`

### 6. PHASE_ORDER Update

In `QuarkMindCaseHub`:
```java
private static final List<String> PHASE_ORDER = List.of(
    "scouting.",           // Phase 1: observe
    "strategy-routing.",   // Phase 2a: route (select which strategy)
    "strategy.",           // Phase 2b: decide (selected strategy executes)
    "tactics.",            // Phase 3: act
    "economics.",          // Phase 4: build
    "summarisation."       // Phase 5: reflect
);
```

### 7. Strategy activateIf() Migration

All three strategy tasks change to:
```java
return ctx -> getId().equals(
    ctx.get(QuarkMindCaseFile.STRATEGY_SELECTED_ID, String.class).orElse(""));
```

Remove `@Inject StrategySelector` from `DroolsStrategyTask`, `EarlyPressureStrategyTask`, `EconomicExpansionStrategyTask`.

`DroolsStrategyTask.activateIf()` currently also checks `broker.current(ScoutingIntelType.POSTURE).isPresent()`. This guard is intentionally removed: (1) tick-chain phase ordering (`scouting.` → `strategy-routing.` → `strategy.`) guarantees scouting runs before strategy, (2) `SC2StrategyRouterTask` handles the no-archetype case by selecting the fallback, and (3) `DroolsStrategyTask.execute()` already handles absent posture via `broker.current(...).orElse("UNKNOWN")`. Running Drools with conservative defaults on the first tick is preferable to skipping strategy entirely.

### 8. QuarkMindTrustRoutingPolicyProvider Update

Add strategy routing policy:
```java
case "strategy" -> new TrustRoutingPolicy(
    0.65, 10, 0.08, 0.6, Map.of(), false, "strategy.drools", Set.of(), 0.4);
```

The `cbrWeight` is set to 0.4 — `SC2ImplementationRoutingStrategy` reads this from `TrustRoutingPolicy.cbrWeight()` for trust/CBR blending. The `fallbackBinding` is `"strategy.drools"` — the designated fallback.

## Deletions

| File | Lines | Replaced by |
|------|-------|------------|
| `StrategyTrustRouter.java` | ~150 | `SC2ImplementationRoutingStrategy` + `SC2StrategyRouterTask` |
| `StrategyTrustObserver.java` | ~70 | Eliminated — routing in tick chain |
| `StrategySelector.java` | ~44 | Eliminated — CaseFile state |
| `StrategyTrustRouterTest.java` | — | `SC2ImplementationRoutingStrategyTest` |
| `StrategySelectorTest.java` | — | Deleted (no replacement needed) |

## New CaseFile Keys

| Key | Constant | Type | Written by | Read by |
|-----|----------|------|-----------|---------|
| `agent.strategy.selected.id` | `STRATEGY_SELECTED_ID` | String | `SC2StrategyRouterTask` | Strategy tasks' `activateIf()` |
| `agent.strategy.routed.context` | `STRATEGY_ROUTED_CONTEXT` | String | `SC2StrategyRouterTask` | `SC2StrategyRouterTask` (change detection) |
| `agent.strategy.routed.archetype` | `STRATEGY_ROUTED_ARCHETYPE` | String | `SC2StrategyRouterTask` | `SC2CbrRetentionObserver` |
| `agent.strategy.routed.confidence` | `STRATEGY_ROUTED_CONFIDENCE` | Double | `SC2StrategyRouterTask` | `SC2CbrRetentionObserver` |
| `agent.strategy.pivot.count` | `STRATEGY_PIVOT_COUNT` | Integer | `SC2StrategyRouterTask` | `SC2StrategyRouterTask` (oscillation limit) |

`STRATEGY_SELECTED_ID` already exists in `QuarkMindCaseFile.ALL_KEYS`. All new keys (`STRATEGY_ROUTED_CONTEXT`, `STRATEGY_ROUTED_ARCHETYPE`, `STRATEGY_ROUTED_CONFIDENCE`, `STRATEGY_PIVOT_COUNT`) must be added to `ALL_KEYS`.

## Testing

### Unit tests (plain JUnit)

| Test | Coverage |
|------|----------|
| `SC2GameCbrCaseTest` | Record construction, `withOutcome()`, `withFeatures()`, `cbrType()` |
| `SC2ImplementationRoutingStrategyTest` | Trust-only (delegates to `TrustCandidateClassifier`), CBR-only, blended, cold start, all-zero fallback, tie-breaking, `ImplementationSelection.Selected` return type |
| `SC2StrategyRouterTaskTest` | First-tick routing, archetype-unchanged skip, re-route on change, no-archetype fallback, confidence threshold gate, pivot limit enforcement, context key writes, `ImplementationCandidate` mapping |
| `SC2CbrRetentionObserverTest` | WIN/LOSS/TIE mapping, UNKNOWN skip, feature extraction from snapshot keys, `store()` parameter passing, `storedCaseId` → `recordOutcome()` flow |

### Integration tests (@QuarkusTest)

| Test | Coverage |
|------|----------|
| `SC2CbrRetentionIT` | Full lifecycle: game → stop → verify case stored with features + outcome |
| `SC2CbrRoutingIT` | Store past cases → new game → verify CBR influences selection |

### Existing test migration

- `TrustWeightedStrategyIT`, `AdaptivePluginSelectionIT`, `DroolsStrategyTaskTest` — replace `StrategySelector` injection with CaseContext setup
- `StrategyCheckpointIT`, `StrategyOutcomeRecordIT` — verify pass with tick-chain routing
- `DispositionAwareRoutingStrategyTest`, `PluginDispatchBrokerTest` — check for `StrategySelector` references

### Benchmarks

Run `GameLoopBenchmarkTest` before/after — verify in-memory CBR retrieval adds no measurable latency to the tick loop.

## Deferred — engine-level CBR blending

`TrustRoutingPolicy` already carries `cbrWeight` and `ImplementationRoutingContext` carries `List<RetrievedExperience> experiences`, but `TrustWeightedImplementationRoutingStrategy` does not use either field. Extending it to blend CBR experience scores when `cbrWeight > 0` would benefit all CaseHub applications, not just QuarkMind. Out of scope for this issue — tracked separately against `casehub-engine-ledger`.

## Garden references

- **GE-20260612-bd3b4d** — Degenerate CBR diagnostic (the gap this fills)
- **GE-20260716-986cd1** — InMemoryCbrCaseMemoryStore test isolation (`.withNotBefore()`)
- **GE-20260605-e7c2e9** — Trust routing mixed-pool gap

## Protocol references

- **PP-20260610-3c3e89** — Inject concrete StrategyTask type in @QuarkusTest
- **PP-20260610-bd14ab** — Trust routing requires three `casehub.ledger.trust-score.*` flags

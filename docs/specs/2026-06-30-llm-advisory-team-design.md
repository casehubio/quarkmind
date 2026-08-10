# LLM Advisory Team on Scouting Intel Channel — Design Spec

**Date:** 2026-06-30
**Issue:** quarkmind#180
**Status:** Approved
**Prerequisites:** quarkmind#207 (engine migration Phase 2), engine#625 (TrustWeightedImplementationRoutingStrategy)
**Depends on:** quarkmind#182 (hierarchical event summarisation — shipped)

---

## Context

The summarisation hierarchy (#182) produces structured event levels: L2 moments (game-significant events), L3 phases (strategic context shifts), L4 arcs (narrative summaries). These are designed as LLM-ready inputs — rich enough for advisory reasoning, infrequent enough to avoid overwhelming an LLM with raw tick data.

This issue adds the first LLM consumers of those event levels: a pool of specialised advisory Workers, trust-scored on multiple dimensions, with the engine's `AgentRoutingStrategy` selecting which advisor configuration handles each advisory request.

QuarkMind's game loop is ported to the production casehub-engine Worker model as a prerequisite (quarkmind#207). Advisory Workers are dispatched by the engine alongside the game loop — not as a separate advisory framework.

---

## Prerequisite: Engine Port (quarkmind#207)

### Execution model

One durable case per game. `QuarkMindCaseHub extends CaseHub` defines the case programmatically.

```
startGame() → caseHub.startCase(initialGameState) → UUID gameSessionId
gameTick()  → caseHub.signalAndAwaitSync(gameSessionId, translator.toMap(), 5s)
              caseHub.signal(gameSessionId, ...) // selective advisory triggers
stopGame()  → caseHub.cancelCase(gameSessionId)
```

### Tick orchestration

A single `TickOrchestratorWorker` chains plugin execution via `WorkerRuntime.execute()`:

```
scouting → strategy → tactics → economics
```

Each plugin remains a `TaskDefinition` (QuarkMind's own interface). The orchestrator wraps them as `WorkerFunction.Sync` calls via the existing `toWorker()` bridge. The live `CaseContext` passes through — plugins read game state and write agent state within the same context.

### Strategy implementation routing

Three competing strategy implementations (DroolsStrategyTask, EarlyPressureStrategyTask, EconomicExpansionStrategyTask) registered as separate Workers for the same `"strategy"` capability. `ImplementationRoutingStrategy` (engine#476) selects which one runs. The trust-weighted implementation (engine#625) applies the four-phase maturity model.

### Deletions

| Class | Lines | Replaced by |
|---|---|---|
| `StrategyTrustRouter` | ~150 | engine `ImplementationRoutingStrategy` |
| `StrategySelector` | ~43 | Engine dispatch state |
| `StrategyTrustObserver` | ~72 | Engine binding dispatch |
| `QuarkMindTaskRegistrar` | ~72 | `QuarkMindCaseHub.getDefinition()` |

### Dependencies removed

| Artifact | Replaced by |
|---|---|
| `casehub-core:1.0.0-SNAPSHOT` | `casehub-engine-api`, `casehub-engine-blackboard` |
| `casehub-persistence-memory:1.0.0-SNAPSHOT` | Engine's in-memory persistence |

---

## Advisory Architecture

### Two-signal pattern

`signalAndAwaitSync()` blocks until all triggered Workers settle. If advisory bindings triggered within the tick settlement, the tick would block for 1-5 seconds waiting for LLM responses — breaking the 500ms tick budget.

Solution: the tick orchestrator completes synchronously via `signalAndAwaitSync()`. Subsequent fire-and-forget `signal()` calls propagate advisory triggers selectively based on detected L2/L3 events:

```java
// GameTickExecutor per tick:
CaseContext ctx = caseHub.signalAndAwaitSync(gameSessionId, gameState, Duration.ofSeconds(5));

// Selective advisory triggers — Map-based signal for flat key storage
// (consistent with how signalAndAwaitSync stores tick data via setAll())
Map<String, Object> advisoryTriggers = new LinkedHashMap<>();
if (hasCrisisMoment(ctx)) {
    advisoryTriggers.put("game.advisory.trigger.crisis", buildCrisisPayload(ctx, currentFrame));
}
if (hasStrategicTransition(ctx)) {
    advisoryTriggers.put("game.advisory.trigger.strategic", buildStrategicPayload(ctx, currentFrame));
}
if (hasEconomicMoment(ctx)) {
    advisoryTriggers.put("game.advisory.trigger.economic", buildEconomicPayload(ctx, currentFrame));
}
if (!advisoryTriggers.isEmpty()) {
    caseHub.signal(gameSessionId, advisoryTriggers)
        .exceptionally(ex -> { log.warn("Advisory trigger failed", ex); return null; });
}
```

Advisory Workers start asynchronously. Results appear in CaseContext whenever the LLM responds — potentially multiple ticks later. Strategy plugins read advisory output on subsequent ticks if available.

**Thread safety:** `CaseContext` is thread-safe for this concurrent access pattern. `WritablePanelImpl` uses `ReentrantReadWriteLock` — advisory Workers acquire write locks when setting results, tick plugins acquire read locks when checking for advisory output. Both `signal()` (async) and `signalAndAwaitSync()` (sync) share the same `CaseContext` instance with proper lock protection. No additional synchronisation needed.

**Error handling policy:** advisory dispatch is fire-and-forget with log-and-continue. `signal()` returns `CompletionStage<Void>`; the `exceptionally()` handler logs failures without propagating. Advisory is explicitly additive — if dispatch fails, the tick loop continues and strategy runs on Drools rules alone.

### Advisory capabilities and bindings

Three advisory capabilities, each triggered by different context changes:

| Capability | Trigger key | Fires on | Latency profile |
|---|---|---|---|
| `advisory-crisis` | `game.advisory.trigger.crisis` | L2: NEXUS_UNDER_ATTACK, BATTLE_STARTED | Fast — sub-2s preferred |
| `advisory-strategic` | `game.advisory.trigger.strategic` | L3 phase transitions | Moderate — 2-5s acceptable |
| `advisory-economic` | `game.advisory.trigger.economic` | L2: ECONOMIC_CRISIS, SUPPLY_BLOCK | Moderate — 2-5s acceptable |

### L2/L3 to trigger key mapping

The tick orchestrator's MomentDetectionTask writes L2 moments to CaseContext. The post-tick signal logic maps detected events to advisory trigger keys:

| Event type | Trigger key | Detection source |
|---|---|---|
| `NEXUS_UNDER_ATTACK` | `game.advisory.trigger.crisis` | L2 moment |
| `BATTLE_STARTED` | `game.advisory.trigger.crisis` | L2 moment |
| `ECONOMIC_CRISIS` | `game.advisory.trigger.economic` | L2 moment |
| `SUPPLY_BLOCK` | `game.advisory.trigger.economic` | L2 moment |
| Phase transition | `game.advisory.trigger.strategic` | L3 phase change |

Each trigger payload includes `gameFrame` as a monotonic component, ensuring `ContextChangeTrigger` always detects a change via `applyAndDiff` even if the same event type recurs on consecutive ticks.

Advisory bindings fire via `ContextChangeTrigger` on the corresponding keys:

### Multiple advisors per capability

Each advisory capability has multiple competing Worker implementations — different LLM configurations (model + prompt + disposition). `DispositionAwareRoutingStrategy` (a quarkmind-specific `AgentRoutingStrategy` composing trust classification with disposition scoring) selects which advisor handles each request.

### Advisory response format

Workers write structured output to CaseContext:

```
agent.advisory.{role}.recommendation   → "DEFEND" | "COUNTERATTACK" | "RETREAT" | "EXPAND" | ...
agent.advisory.{role}.reasoning        → free-text explanation
agent.advisory.{role}.confidence       → 0.0-1.0
agent.advisory.{role}.timestamp        → gameFrame at advisory time
```

Strategy plugins check `currentFrame - advisory.timestamp > STALENESS_THRESHOLD` before consuming. Advisory is additive signal — never required. If no advisory has arrived, strategy runs on Drools rules alone.

### Worker execution

Advisory Workers use `AgentWorkerFunction` — the engine's native LLM-calling variant of `WorkerFunction`. Each advisory Worker wraps an `Agent` instance constructed via `AgentBuilder`:

```java
Agent crisisAgent = Agent.builder()
    .model(chatModel)                        // LangChain4j ChatModel (injected)
    .systemPrompt(systemTemplate)            // From SystemPromptRenderer
    .userMessage(userTemplate)               // Game state + L2 moments
    .responseSchema(advisoryResponseSchema)  // JSON Schema constraining LLM structured output
    .outputTransformer(json -> {             // Prefix raw LLM keys for CaseContext storage
        ObjectNode prefixed = MAPPER.createObjectNode();
        json.fields().forEachRemaining(e ->
            prefixed.set("agent.advisory." + role + "." + e.getKey(), e.getValue()));
        return prefixed;
    })
    .build();

WorkerFunction fn = new AgentWorkerFunction(crisisAgent);
```

The `outputTransformer` bridges between the LLM's raw response fields (`recommendation`, `confidence`, `reasoning`, `timestamp`) and the namespaced CaseContext keys (`agent.advisory.{role}.*`). Without this transformation, bare keys from multiple advisory roles would collide in CaseContext's flat key storage. Note: `responseSchema(JsonSchema)` constrains the LLM's output format; `outputSchema(String)` is a separate JQ-based output transformer — they serve different purposes.

`SyncAgentWorkerFunctionHandler` executes advisory Workers on virtual threads. The execution path: `Agent.execute(inputData)` → render prompts from templates → `ChatModel.chat(request)` → parse structured response → apply `outputTransformer` (key-prefixing) → `WorkerResult`.

`ChatModel` is backed by `AgentProviderChatModel`, which bridges from LangChain4j's `ChatModel` interface to the platform's `AgentProvider.invoke()` SPI. This allows any configured provider (Anthropic, OpenAI) to serve advisory requests without advisory-specific provider code.

### CaseDefinition structure

```java
CaseDefinition.builder()
    .namespace("quarkmind").name("starcraft-game").version("2.0")
    .capabilities(
        tickDecision,
        advisoryCrisis, advisoryStrategic, advisoryEconomic)
    .workers(
        tickOrchestrator,                          // → tick-decision
        crisisAdvisorAggressive,                   // → advisory-crisis
        crisisAdvisorConservative,                 // → advisory-crisis
        strategicAdvisorBold,                      // → advisory-strategic
        strategicAdvisorMeasured,                  // → advisory-strategic
        economicAdvisorExpansion,                   // → advisory-economic
        economicAdvisorDefensive)                   // → advisory-economic
    .bindings(
        Binding.builder()
            .name("tick-decision")
            .on(new ContextChangeTrigger(".working[\"game.frame\"] | . != null"))
            .capability(tickDecision)
            .build(),
        Binding.builder()
            .name("advisory-crisis")
            .on(new ContextChangeTrigger(
                ".working[\"game.advisory.trigger.crisis\"] | . != null"))
            .capability(advisoryCrisis)
            .build(),
        Binding.builder()
            .name("advisory-strategic")
            .on(new ContextChangeTrigger(
                ".working[\"game.advisory.trigger.strategic\"] | . != null"))
            .capability(advisoryStrategic)
            .build(),
        Binding.builder()
            .name("advisory-economic")
            .on(new ContextChangeTrigger(
                ".working[\"game.advisory.trigger.economic\"] | . != null"))
            .capability(advisoryEconomic)
            .build())
    .build();
```

---

## eidos Integration

### Advisor configurations as AgentDescriptors

Each advisor configuration is a full `AgentDescriptor` — model + prompt + disposition as a single unit. Registered at startup via `AgentDescriptorRegistrar` SPI.

```java
AgentDescriptor.builder()
    .agentId("claude:crisis-aggressive@v1")
    .name("Aggressive Crisis Responder")
    .provider("anthropic").modelFamily("claude").modelVersion("sonnet-4")
    .slot("crisis-responder")
    .slotVocabulary("urn:casehub:vocab:conscientiousness")
    .disposition(new AgentDisposition(
        "collaborative", "flexible", "bold", "semi-autonomous", "compete", false))
    .capabilities(List.of(
        AgentCapability.builder()
            .name("advisory-crisis")
            .latencyHintP50Ms(1500L)
            .qualityHint(0.7)
            .tags(List.of("starcraft.advisory.crisis"))
            .build()))
    .tenancyId(TenancyConstants.DEFAULT_TENANT_ID)
    .build()
```

### Disposition matching

Advisor selection considers disposition fit for the current game context:

| Game context | Preferred disposition | Rationale |
|---|---|---|
| Enemy AGGRESSIVE | `riskAppetite: conservative` | Defensive counsel under pressure |
| Enemy ECONOMIC | `riskAppetite: bold` | Exploit the opponent's greed window |
| Early game | `ruleFollowing: strict` | Follow established build orders |
| Late game | `ruleFollowing: flexible` | Adapt to evolving situation |

Disposition matching is implemented as `DispositionAwareRoutingStrategy` — a quarkmind-specific `AgentRoutingStrategy` that composes trust classification with disposition scoring. It injects `TrustCandidateClassifier` and `TrustRoutingPolicyProvider` directly (both available from `casehub-engine-ledger`) rather than decorating `TrustWeightedAgentStrategy`, because the trust strategy's per-candidate scoring is internal (`private`) and inaccessible from a decorator.

```java
public class DispositionAwareRoutingStrategy implements AgentRoutingStrategy {
    @Inject TrustCandidateClassifier classifier;
    @Inject TrustRoutingPolicyProvider policyProvider;
    @Inject TrustScoreSource scoreSource;

    @Override
    public Uni<AgentAssignment> select(AgentRoutingContext context, List<AgentCandidate> candidates) {
        String capability = context.capabilityName();
        TrustRoutingPolicy policy = policyProvider.forCapability(capability);
        List<ClassifiedCandidate> classified = classifier.classify(
            candidates, capability, policy, scoreSource);

        JsonNode gameState = context.caseContext();
        DispositionPreference pref = resolvePreference(gameState);

        List<ScoredCandidate> scored = classified.stream()
            .filter(cc -> !cc.isExcluded())
            .map(cc -> new ScoredCandidate(cc,
                trustScore(cc, policy) * dispositionMultiplier(cc.candidate(), pref)))
            .sorted(comparing(ScoredCandidate::score).reversed())
            .toList();

        // Delegate decision logic to classifier — handles escalation for
        // all-borderline pools and bootstrapEscalationRequired guard
        return Uni.createFrom().item(classifier.decide(classified, scored, capability));
    }

    private double dispositionMultiplier(AgentCandidate c, DispositionPreference pref) {
        AgentDisposition disp = c.agentDescriptor().disposition();
        return pref.computeMultiplier(disp);
    }
}
```

Disposition matching uses **soft preference** via score multipliers (0.8–1.2) — never hard exclusion. An advisor with the "wrong" disposition but high trust score can still be selected. `ConscientiousnessTerm` values are compared against `AgentDescriptor.disposition()` fields via the descriptor's `slotVocabulary`.

`TrustRoutingPolicyProvider` remains static per capability — it provides dimension weights and quality floors via `forCapability(String capabilityName)`. Game-state-dependent routing lives in `DispositionAwareRoutingStrategy`, cleanly separated from the policy provider.

### System prompt rendering

Each advisor's system prompt generated via eidos `SystemPromptRenderer.render(descriptor, promptContext)`. `AgentPromptContext` carries:
- Game state summary as `situationalContext`
- Triggering event (L2 moment or L3 phase) as `GoalContext`
- SC2 domain instructions as system prompt prefix

The rendered prompt combines disposition traits with game context — a "bold, flexible" advisor gets a prompt encouraging aggressive recommendations; a "conservative, strict" advisor favours safe plays.

---

## Trust Scoring

### Three evaluation dimensions

Each mapped to a `CAPABILITY_DIMENSION` score in casehub-ledger.

| Dimension key | Measures | Timing | Scoring |
|---|---|---|---|
| `response-latency` | Dispatch-to-response time | Immediate | `1.0 - (actualMs / maxAcceptableMs)`, clamped [0,1]. Per-role max: crisis=2000ms, strategic=5000ms, economic=4000ms |
| `recommendation-quality` | Game state delta after advisory | Deferred: N frames after consumption | Metric comparison (resource advantage, army ratio) at advisory time vs N frames later. Positive → ENDORSED, negative → CHALLENGED |
| `game-outcome` | Win/loss attribution | Game end | Flat per-advisor: each invoked advisor gets one OutcomeRecord with weight 1.0 |

### Quality floors

```properties
quarkmind.advisory.trust.quality-floors.response-latency=0.3
quarkmind.advisory.trust.quality-floors.recommendation-quality=0.2
quarkmind.advisory.trust.quality-floors.game-outcome=0.2
```

Quality floors are enforced during `TrustCandidateClassifier` phase classification as an additive filter alongside the four-phase model:

1. **Four-phase model** (BOOTSTRAP → QUALIFIED/BORDERLINE/EXCLUDED): operates on aggregate capability trust scores against `TrustRoutingPolicy.threshold`
2. **Quality floors**: per-dimension minimum thresholds from `TrustRoutingPolicy.qualityFloors`; failing ANY floor → EXCLUDED (Phase 3)

These are applied in sequence during classification. Quality floors are NOT evaluated during BOOTSTRAP phase — no dimension data exists yet. Once an advisor crosses `minimumObservations`, both mechanisms apply.

### Attestation flow

```
Advisory Worker completes
  → Latency measured
  → OutcomeRecord written: (advisorId, capability, "response-latency", score, 1.0)
  → IncrementalTrustUpdateObserver fires (AFTER_SUCCESS)
  → ActorTrustScoreRepository.upsert() materialises CAPABILITY_DIMENSION score

N frames later
  → Deferred evaluator checks game state delta
  → OutcomeRecord written: (advisorId, capability, "recommendation-quality", verdict, 0.7)

Game ends
  → Per-advisor game-outcome attestation (flat weight 1.0 per invoked advisor)
```

### Deferred evaluation mechanism

Pending evaluations are tracked in CaseContext under `agent.advisory.pending-evaluations` as a list of evaluation records:

```java
record PendingEvaluation(
    String advisorId,
    String capability,
    long advisoryFrame,
    String recommendation,
    double confidence,
    Map<String, Double> gameStateSnapshot
)
```

When an advisory Worker completes, a `PendingEvaluation` is appended with a snapshot of key game state metrics at advisory time (mineral/gas income rate, army supply, worker count, army value ratio).

Deferred evaluation runs **post-tick** — after `signalAndAwaitSync()` returns but before the next tick fires — to avoid adding I/O latency to the synchronous tick budget:

```java
// Post-tick callback (outside signalAndAwaitSync):
List<PendingEvaluation> pending = ctx.getAs("agent.advisory.pending-evaluations", LIST_TYPE);
if (pending == null || pending.isEmpty()) return;

long currentFrame = ctx.getAs("game.frame", Long.class);
List<PendingEvaluation> remaining = new ArrayList<>(pending.size());
List<OutcomeRecord> records = new ArrayList<>();

for (PendingEvaluation eval : pending) {
    if (currentFrame - eval.advisoryFrame() >= EVALUATION_DELAY_FRAMES) {
        Map<String, Double> currentState = snapshotMetrics(ctx);
        double delta = computeWeightedDelta(eval.gameStateSnapshot(), currentState);
        AttestationVerdict verdict = delta > 0 ? ENDORSED : CHALLENGED;
        records.add(OutcomeRecord.of(
            eval.advisorId(), eval.capability(),
            "recommendation-quality", verdict, 0.7));
    } else {
        remaining.add(eval);
    }
}
// Persist cleaned list back to CaseContext (getAs returns a deserialized copy)
ctx.set("agent.advisory.pending-evaluations", remaining);
// Batch-write OutcomeRecords outside tick loop
records.forEach(outcomeRecorder::record);
```

`EVALUATION_DELAY_FRAMES` defaults to 200 (~17 seconds at fastest game speed). This allows sufficient time for an advisory recommendation to have observable impact on game state.

### Advisory completion lifecycle

When an advisory Worker completes, the engine fires a generic Worker completion event. `AdvisoryCompletionObserver` — a quarkmind-specific CDI observer — identifies advisory completions by checking the capability name prefix and fires a domain-specific event:

```java
public record AdvisoryCompleted(
    String advisorId, String capability, long gameFrame,
    String recommendation, double confidence, long latencyMs
) {}

@ApplicationScoped
public class AdvisoryCompletionObserver {
    @Inject Event<AdvisoryCompleted> advisoryCompleted;
    @Inject CaseInstanceCache caseInstanceCache;

    void onWorkerDecision(@ObservesAsync WorkerDecisionEvent event) {
        if (!event.capabilityTag().startsWith("advisory-")) return;

        // WorkerDecisionEvent carries (caseId, tenancyId, workerId, capabilityTag, traceId)
        // Advisory output is already in CaseContext by the time this event fires
        CaseContext ctx = caseInstanceCache.get(event.caseId()).getCaseContext();
        String role = event.capabilityTag().substring("advisory-".length());
        String recommendation = ctx.getAs("agent.advisory." + role + ".recommendation", String.class);
        Double confidence = ctx.getAs("agent.advisory." + role + ".confidence", Double.class);
        Long frame = ctx.getAs("agent.advisory." + role + ".timestamp", Long.class);

        // Latency: advisory Worker records start time in CaseContext before LLM call,
        // completion time after — observer computes the delta
        Long startNanos = ctx.getAs("agent.advisory." + role + ".startNanos", Long.class);
        long latencyMs = startNanos != null
            ? (System.nanoTime() - startNanos) / 1_000_000
            : -1;

        advisoryCompleted.fire(new AdvisoryCompleted(
            event.workerId(), event.capabilityTag(), frame != null ? frame : 0,
            recommendation, confidence != null ? confidence : 0.0, latencyMs));
    }
}
```

This observer is the single point for post-advisory logic: it fires `AdvisoryCompleted`, which triggers the invocation counter, latency recording, and pending evaluation creation. Latency measurement is in-Worker: the advisory `AgentWorkerFunction` records `System.nanoTime()` to CaseContext before the LLM call, and the observer computes the delta.

### Game-outcome invocation counter

`AdvisoryInvocationCounter` tracks which advisors were invoked within a game session:

```java
@ApplicationScoped
public class AdvisoryInvocationCounter {
    private final Set<String> invokedAdvisors = ConcurrentHashMap.newKeySet();

    void onGameStarted(@Observes GameStarted event) { invokedAdvisors.clear(); }
    void onAdvisoryCompleted(@Observes AdvisoryCompleted event) {
        invokedAdvisors.add(event.advisorId());
    }

    public Set<String> snapshot() { return Set.copyOf(invokedAdvisors); }
}
```

At game end, `AdvisoryGameOutcomeRecorder` (analogous to the existing `GameOutcomeRecorder`) observes `GameStopped`, iterates the invoked advisors set, and writes one `OutcomeRecord` per advisor with **flat weight (1.0)** — consistent with how `GameOutcomeRecorder` attributes game outcome to the strategy. Each advisor that participated in the game receives equal game-outcome attribution regardless of invocation frequency, because a single critical advisory at a pivotal moment can be more influential than many routine ones.

### Redeployment

No explicit redeployment mechanism needed. `TrustWeightedAgentStrategy` naturally stops selecting underperforming advisors (BORDERLINE/EXCLUDED phase). New configurations start in BOOTSTRAP and get a fair trial. The trust model IS the redeployment mechanism.

For observability, `AdvisorTrustPhaseObserver` fires a CDI event when an advisor transitions to EXCLUDED:

```java
public record AdvisorExcludedEvent(String advisorId, String capability, String reason) {}
```

This satisfies issue #180's acceptance criterion for "low-trust advisor flagged" — the event is observable by monitoring infrastructure without requiring explicit replacement logic.

### OutcomeEvaluator SPI

The evaluation logic is designed for future extraction to casehub-blocks:

```java
public record AdvisoryResponse(
    String advisorId,
    String capability,
    String recommendation,
    double confidence,
    long gameFrame,
    long latencyMs,
    Map<String, Object> metadata
) {}

public record GameEvaluationContext(
    Map<String, Double> advisoryTimeSnapshot,
    Map<String, Double> evaluationTimeSnapshot,
    long advisoryFrame,
    long evaluationFrame,
) {}

public interface OutcomeEvaluator<C> {
    Map<String, AttestationVerdict> evaluate(AdvisoryResponse response, C context);
}
```

SC2 implementation: `GameStateOutcomeEvaluator implements OutcomeEvaluator<GameEvaluationContext>`. Each harness provides its own evaluator. The trust infrastructure (ledger, attestations, scoring) is already domain-agnostic.

---

## Consumption Modes

Advisory Workers write results to CaseContext AND dispatch to a `quarkmind-advisory` Qhorus channel. Three consumption modes, same output.

### Mode 1: In-loop

Strategy plugins read advisory CaseContext keys during tick execution:

```java
// DroolsStrategyTask.execute(ctx):
String rec = ctx.getAs("agent.advisory.crisis.recommendation", String.class);
Long frame = ctx.getAs("agent.advisory.crisis.timestamp", Long.class);
if (rec != null && currentFrame - frame < STALENESS_THRESHOLD) {
    ruleUnit.getAdvisoryStore().add(new AdvisoryFact(rec, confidence));
}
```

Advisory is additive — absent advisory means strategy runs on Drools rules alone.

### Mode 2: Post-game evaluation

The `quarkmind-advisory` Qhorus channel (`ChannelSemantic.APPEND`) accumulates all dispatches and responses as STATUS messages. Post-game analysis reads channel history via `MessageService.pollAfter()` to correlate advisory with game events, compare recommendations against outcomes, and generate trust reports.

Channel retention is managed by Qhorus platform infrastructure — `QhorusConfig.dataRetentionDays()` (default 7 days) purges old messages automatically. In-game accumulation is bounded by game duration (20 min max) and advisory firing frequency.

### Mode 3: HIL coaching

A `ChannelBackend` (per-channel, not cross-cutting) registers on `quarkmind-advisory` and pushes advisory messages to the visualizer's WebSocket feed. The backend implements `ChannelBackend.post()` to forward each advisory message to a dedicated `/ws/advisory` endpoint, following the same infrastructure pattern as the existing `GameStateSocket` at `/ws/gamestate`. The visualizer renders recommendations as overlay text.

### Channel message format

```json
{
  "role": "crisis",
  "advisorId": "claude:crisis-aggressive@v1",
  "trigger": {"type": "NEXUS_UNDER_ATTACK", "frame": 4200},
  "recommendation": "DEFEND",
  "reasoning": "Enemy army at natural expansion, supply advantage suggests hold",
  "confidence": 0.82,
  "latencyMs": 1340,
  "gameFrame": 4200
}
```

Sender: advisor's agentId. Type: `MessageType.STATUS`. Channel: `quarkmind-advisory`, `ChannelSemantic.APPEND`.

---

## Dependencies

### Required artifacts (introduced by quarkmind#207)

| Required artifact | For | Notes |
|---|---|---|
| `casehub-engine` (full runtime) | `CaseHubRuntime`, Worker dispatch, `SyncAgentWorkerFunctionHandler` | Replaces casehub-core |
| `casehub-engine-blackboard` | `WritablePanelImpl`, `CaseContext` | CDI beans require engine SPIs on classpath |
| `casehub-engine-persistence-memory` | In-memory engine SPIs for `@QuarkusTest` | Test scope |
| `casehub-eidos-api` | `AgentDescriptor`, `AgentCapability`, `AgentDisposition` | Compile scope |
| `casehub-eidos-vocab` | `ConscientiousnessTerm` | Compile scope |
| `casehub-eidos` (runtime) | `AgentDescriptorRegistrar`, `SystemPromptRenderer`, `AgentRegistry` | Runtime scope |
| `casehub-engine-ledger` | `TrustWeightedAgentStrategy`, `TrustCandidateClassifier` | Runtime scope |

The engine migration (#207) is the prerequisite that brings these dependencies onto the classpath. The CDI wiring challenges noted in the current pom.xml (engine-blackboard CDI beans requiring `EventLogRepository`, `JobScheduler` etc.) are resolved by #207 bringing the full engine runtime — those SPIs are satisfied by engine's own CDI producers.

---

## Platform Coherence

| Concern | Status |
|---|---|
| PLATFORM.md line 494: no implementation routing in application repos | ✅ engine#625 files it in engine-ledger |
| PLATFORM.md line 67: trust cold-start four-phase model | ✅ BOOTSTRAP phase for new advisors |
| PLATFORM.md line 482: ChannelBackend vs MessageObserver | ✅ HIL coaching uses ChannelBackend (per-channel) |
| PLATFORM.md line 474: no trust scoring in work/engine | ✅ Trust in ledger; consumed via TrustWeightedAgentStrategy |
| PLATFORM.md line 591: agent identity format | ✅ `{model-family}:{persona}@{major}` |
| Protocol PP-20260522-3b1ccd: platform-api scope | ✅ No new types added to casehub-platform-api |
| Protocol PP-20260521-1ca0c8: trust maturity model | ✅ Four-phase with fallback, never blocks |
| Protocol PP-20260520-88dbbd: Qhorus consumer integration | ✅ Advisory channel consumption via ChannelBackend follows consumer integration pattern |

---

## Testing Strategy

### Unit tests

- **Advisory trigger mapping**: verify L2/L3 events map to correct trigger keys
- **Deferred evaluator**: verify metric snapshot, delta computation, and verdict assignment with synthetic game states
- **Invocation counter**: verify lifecycle (reset on GameStarted, increment on AdvisoryCompleted, snapshot accuracy)
- **Disposition matching**: verify score multiplier computation for all disposition × game-context combinations

### Integration tests

- **Advisory Worker dispatch**: mock `AgentProvider` returning deterministic responses, verify advisory output written to CaseContext with correct keys
- **Two-signal pattern**: verify tick settlement completes synchronously while advisory triggers fire asynchronously
- **Trust scoring pipeline**: verify OutcomeRecord → IncrementalTrustUpdateObserver → ActorTrustScoreRepository flow using `StrategyOutcomeRecordIT` pattern
- **End-to-end advisory flow**: tick → L2 moment detection → advisory trigger → Worker dispatch → CaseContext write → strategy consumption in subsequent tick

### Multi-game trust tests

- **Trust convergence**: run N games with a mix of good and bad advisors, verify trust scores converge (good advisors reach QUALIFIED, bad advisors reach EXCLUDED)
- **BOOTSTRAP fairness**: verify new advisors get equal opportunity during BOOTSTRAP phase regardless of incumbent scores

---

## Dependency Chain

```
engine#625 (TrustWeightedImplementationRoutingStrategy)
    ↓ blocks
quarkmind#207 (Engine migration Phase 2)
    ↓ blocks
quarkmind#180 (LLM advisory team) ← this issue
```

---

## Out of Scope

| Item | Tracked by |
|---|---|
| Commentator/Coach LLM | quarkmind#181 — uses advisory infrastructure |
| Enemy strategy classifier | quarkmind#183 — uses advisory infrastructure |
| CBR reference implementation | quarkmind#192 — post-game analysis feeding case memory |
| Genericise unit/building definitions | quarkmind#74 |
| Blog entry: narrated game session | Deferred from #182 — no issue filed (writing task) |

---

## Acceptance Criteria

### Engine port (quarkmind#207)

- [ ] `QuarkMindCaseHub extends CaseHub` with programmatic `getDefinition()`
- [ ] `TickOrchestratorWorker` chains scouting → strategy → tactics → economics via `WorkerRuntime.execute()`
- [ ] `signalAndAwaitSync()` replaces `CaseEngine.createAndSolve()` per tick
- [ ] `ImplementationRoutingStrategy` selects among competing strategy implementations
- [ ] `StrategyTrustRouter`, `StrategySelector`, `StrategyTrustObserver`, `QuarkMindTaskRegistrar` deleted
- [ ] casehub-poc dependency removed (`casehub-core`, `casehub-persistence-memory`)
- [ ] `mvn test` passes (all unit and integration tests green)

### LLM advisory (quarkmind#180)

- [ ] At least two advisor configurations per advisory role, registered as eidos AgentDescriptors
- [ ] Advisory Workers dispatched via `DispositionAwareRoutingStrategy` (composing `TrustCandidateClassifier` with disposition scoring)
- [ ] Two-signal pattern: tick settles synchronously, advisory fires asynchronously via selective `signal()` calls
- [ ] Advisory Workers use `AgentWorkerFunction` with `Agent` → `ChatModel` → `AgentProviderChatModel` execution path
- [ ] Latency measured per advisory response, written as CAPABILITY_DIMENSION trust score
- [ ] Deferred outcome evaluation: recommendation quality scored N frames later via `PendingEvaluation` mechanism
- [ ] Game-level outcome attestation: flat per-advisor weight (1.0) for each invoked advisor
- [ ] Advisory results readable in CaseContext by strategy plugins (in-loop consumption)
- [ ] Advisory results persisted to `quarkmind-advisory` Qhorus channel (post-game + HIL)
- [ ] `ConscientiousnessTerm` disposition traits factored into routing via `DispositionAwareRoutingStrategy`
- [ ] `OutcomeEvaluator` SPI designed for future casehub-blocks extraction with defined `AdvisoryResponse` and `GameEvaluationContext` types
- [ ] `AdvisorExcludedEvent` CDI event fired for observability when advisor enters EXCLUDED phase
- [ ] Blog entry documenting a game where multiple advisor configurations were evaluated

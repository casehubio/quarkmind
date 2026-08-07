# LLM-Evaluated Compliance Verification for Coaching

**Issue:** #245 (child of epic #250)
**Date:** 2026-08-06
**Branch:** `issue-245-llm-compliance-verification`

## Problem

Some coaching advice is too nuanced for structured predicate verification: "improve your macro", "apply more pressure on their natural", "be more aggressive with your army." Today these produce `CoachingAdvice` with `verification == null`, which always resolves as NEUTRAL — no compliance signal, no trust learning.

An LLM can compare game state snapshots before and after the advice window and judge whether the human's actions reflect compliance.

## Design Decision: Separate Evaluation Path

LLM compliance evaluation is **not** a new `VerificationPredicate` permit. The sealed predicate hierarchy (`CountDelta`, `ArmyCentroidMovement`, `ExpansionPlacement`, `UnitsNearLocation`) is synchronous, deterministic, and runs every tick. LLM evaluation is async (2-5 second latency), fires once (when the window closes), and produces richer output than a boolean.

The evaluation path splits at `CoachingComplianceEvaluator.evaluate()`:
- **Verifiable advice** (`verification != null`): existing predicate-based path, unchanged
- **Non-verifiable advice** (`verification == null`): new LLM compliance path

## Lifecycle & Data Flow

```
evaluate() detects windowEnd reached + !isVerifiable()
  → capture current GameState snapshot
  → dispatch async LlmComplianceWorker with (baselineState, currentState, adviceText)
  → remove commitment from map immediately
  → add correlationId to inFlight set (guards against orphan callbacks)
  → (2-5 seconds later) worker callback fires:
    → check correlationId still in inFlight set (discard if not — game ended or superseded)
    → remove from inFlight set
    → recorder.record() with mapped verdict
    → fire CoachingComplianceResolved via Event.fireAsync() (non-tick thread)
  → on LLM failure or timeout: degrade to NEUTRAL
```

### Async Dispatch Mechanism

`ComplianceWorkerDispatcher.dispatch()` submits the LLM call via `CompletableFuture.supplyAsync()` on Quarkus's managed executor (`@Inject ManagedExecutor`). The Worker's sync function runs on the managed thread pool; the callback fires on the completion thread.

### Timeout

LLM call timeout: configurable via `quarkmind.coaching.compliance.llm-timeout-seconds` (default 10). On timeout, the dispatcher resolves as NEUTRAL and removes the correlationId from the in-flight set. Implemented via `CompletableFuture.orTimeout()`.

### Game End / Cancellation

`CoachingComplianceEvaluator.withdrawAll()` clears the in-flight set after clearing commitments. Any callback that fires after `withdrawAll()` finds its correlationId absent from the in-flight set and discards silently. No orphaned trust records.

### Supersession

If new advice for the same domain arrives while an LLM evaluation is in flight for a previous commitment, `CoachingChannelBroker.onCoachingCompleted()` already handles supersession by replacing the commitment. The old correlationId is removed from the in-flight set at that point, so its callback is discarded when it returns.

### Baseline State Capture

Non-verifiable advice currently has no baseline — `withBaseline()` is never called because `verification` is null. The LLM needs the game state at advice time.

**Fix:** `OpenCommitment` gains a `baselineState` field:

```java
record OpenCommitment(
    String correlationId,
    String agentId,
    CoachingAdvice advice,
    long issuedAtFrame,
    GameState baselineState    // nullable — captured from triggerState for non-verifiable advice
)
```

`CoachingChannelBroker.onCoachingCompleted()` populates `baselineState` from `event.triggerState()` when `!advice.isVerifiable()`. When `advice.isVerifiable()`, the baseline is embedded in the predicate via `withBaseline()` and `baselineState` is null.

## Components

### LlmComplianceWorkerFactory

Static factory following the `LlmPatternClassifierWorkerFactory` pattern — stateless worker, sync LLM call wrapped in the Worker framework.

```java
public final class LlmComplianceWorkerFactory {

    static Worker createWorker(ChatModel chatModel, ComplianceWorkerDispatcher.Callback onCompletion);

    static String summariseForCompliance(GameState baseline, GameState current, String adviceText);

    static String buildSystemPrompt();
}
```

No `parseVerdict()` here — parsing lives in `ComplianceVerdict.parse()` (single owner).

**Input map:**
- `baseline` — GameState at advice time
- `current` — GameState at verification window end
- `advice` — the original coaching advice text
- `domain` — CoachingDomain
- `correlationId`, `agentId`, `gameFrame` — for callback routing

### ComplianceVerdict

```java
record ComplianceVerdict(String verdict, double confidence, String reasoning) {
    static ComplianceVerdict parse(String text);
}
```

`parse()` handles: valid JSON, markdown-fenced JSON, malformed JSON (returns `NEUTRAL` verdict with 0.0 confidence), missing fields (defaults: verdict=`IGNORED`, confidence=0.5, reasoning=""). This is the single parsing entry point — no duplicate `parseVerdict()` method elsewhere.

### ComplianceWorkerDispatcher

`@ApplicationScoped` bean — orchestrates dispatch and callback wiring.

```java
@ApplicationScoped
public class ComplianceWorkerDispatcher {

    void dispatch(OpenCommitment commitment, GameState currentState);
    boolean isAvailable();
    void cancelAll();    // called by evaluator.withdrawAll() on game end

    @FunctionalInterface
    interface Callback {
        void onCompleted(String correlationId, String agentId, ComplianceVerdict verdict,
                         CoachingAdvice advice, long gameFrame);
    }
}
```

**Responsibilities:**
- Summarises both states via `LlmComplianceWorkerFactory.summariseForCompliance()`
- Builds input map, dispatches Worker via `CompletableFuture.supplyAsync()` on `ManagedExecutor`
- Maintains `Set<String> inFlight` — correlationIds of dispatched-but-pending evaluations
- Registers callback: checks correlationId in `inFlight` (discards if absent), maps verdict to outcome, calls `recorder.record()`, fires `CoachingComplianceResolved` via `Event.fireAsync()`
- On LLM failure or timeout: degrades to NEUTRAL
- `cancelAll()` clears `inFlight` set — orphaned callbacks discard silently

The callback is a nested interface on the dispatcher rather than a standalone type — it carries `CoachingAdvice` (needed by `recorder.record()`) and `ComplianceVerdict` (structured, not raw strings).

**ChatModel availability:** Uses `Instance<ChatModel>` with `isResolvable()` check — same unqualified `ChatModel` as the advisory framework (configured via `quarkus.langchain4j.*`). In profiles without LLM config (`%mock`, `%emulated`), the dispatcher reports itself unavailable and the evaluator degrades to NEUTRAL. A cheaper/slower model can be configured via a named model (`quarkus.langchain4j.compliance.*`) in future — not part of this issue.

### CoachingComplianceEvaluator Changes

The `!advice.isVerifiable()` branch extends:

```java
if (!advice.isVerifiable()) {
    if (currentFrame >= windowEnd) {
        if (commitment.baselineState() != null && dispatcher.isResolvable()
                && dispatcher.get().isAvailable()) {
            dispatcher.get().dispatch(commitment, state);
            iterator.remove();
        } else {
            recorder.record(commitment.correlationId(), commitment.agentId(), "NEUTRAL", advice);
            fireComplianceResolved(currentFrame, domain, "NEUTRAL", commitment.correlationId());
            iterator.remove();
        }
    }
    continue;
}
```

`dispatcher` is injected as `@Inject Instance<ComplianceWorkerDispatcher>`. Uses `isResolvable()` to check CDI availability (absent in profiles without LLM config). The evaluator never holds a direct reference — always goes through `Instance<>`.

`withdrawAll()` is extended to call `dispatcher.get().cancelAll()` when resolvable — clears the in-flight set so orphaned LLM callbacks discard silently.

## Verdict Mapping

The LLM returns a three-value verdict. Mapping to compliance outcomes:

| LLM Verdict | Compliance Outcome | Trust Signal |
|-------------|-------------------|--------------|
| `COMPLIED` | ENDORSED | Positive — advice was followed |
| `PARTIALLY` | PARTIAL | Mixed (trust polarity 0.5) — attempt made but incomplete |
| `IGNORED` | CHALLENGED | Negative — no evidence of compliance |
| (LLM failure) | NEUTRAL | No signal — same as today |

**PARTIAL is a new outcome value.** `CoachingEffectivenessTrustRecorder.record()` already takes `String outcome` — no API change needed. The recorder is a logging stub; adding a value costs nothing. `CoachingComplianceResolved.status()` is also `String` — compatible. The human override path (`resolveHuman()`) is unaffected — it only produces ENDORSED/CHALLENGED.

## Game State Summarisation

The LLM receives a compact text comparison, not raw `GameState` objects:

```
ADVICE: "Build more Stalkers and expand to your natural"

BEFORE (frame 1200, 1:40):
Resources: 450 minerals, 200 vespene, 38/46 supply
Army: 3x STALKER, 2x ZEALOT (5 units)
Buildings: NEXUS, GATEWAY, GATEWAY, FORGE, CYBERNETICS_CORE, PYLON, PYLON

AFTER (frame 1650, 2:17):
Resources: 280 minerals, 150 vespene, 52/62 supply
Army: 6x STALKER, 2x ZEALOT, 2x SENTRY (10 units)
Buildings: NEXUS, NEXUS, GATEWAY, GATEWAY, FORGE, CYBERNETICS_CORE, PYLON, PYLON, PYLON

CHANGES:
+3x STALKER, +2x SENTRY
+1x NEXUS, +1x PYLON
Minerals: -170, Vespene: -50, Supply: +14/+16
```

The CHANGES section pre-computes the delta — the LLM reads it directly without reasoning over two lists. Units and buildings grouped by type, counted. No positions, no tags.

### System Prompt

Instructs the LLM to:
- Compare BEFORE vs AFTER in the context of the ADVICE
- COMPLIED = clear evidence of following the advice
- PARTIALLY = some relevant actions but incomplete or mixed
- IGNORED = no evidence of following the advice, or actions contradict it
- Confidence reflects signal clarity (high = obvious compliance or obvious disregard)

Response format:
```json
{
  "verdict": "COMPLIED | PARTIALLY | IGNORED",
  "confidence": 0.0-1.0,
  "reasoning": "one sentence"
}
```

## Testing Strategy

### Unit tests (plain JUnit)

| Test class | Coverage |
|-----------|----------|
| `LlmComplianceWorkerFactoryTest` | `summariseForCompliance()` — correct delta rendering for unit/building adds, removals, resource changes. Empty army, no changes, mixed adds/removals. `buildSystemPrompt()` — prompt contains verdict vocabulary and response format. |
| `ComplianceVerdictTest` | `parse()` — valid JSON, markdown-fenced JSON, malformed JSON (→ NEUTRAL), missing fields (defaults), all three verdict values. |
| `CoachingComplianceEvaluatorTest` | Extended — non-verifiable advice with baseline dispatches to dispatcher mock. Non-verifiable without baseline degrades to NEUTRAL. Non-verifiable without dispatcher (Instance not resolvable) degrades to NEUTRAL. Verifiable advice path unchanged (regression). `withdrawAll()` calls `cancelAll()` on dispatcher. |
| `ComplianceWorkerDispatcherTest` | Dispatch builds correct input map from commitment + current state. Callback maps COMPLIED→ENDORSED, PARTIALLY→PARTIAL, IGNORED→CHALLENGED correctly. LLM failure degrades to NEUTRAL. Timeout degrades to NEUTRAL. Callback after `cancelAll()` is discarded (in-flight guard). Superseded correlationId callback is discarded. |

### Integration test (@QuarkusTest)

| Test class | Coverage |
|-----------|----------|
| `LlmComplianceIT` | Full cycle: coaching advice with no predicate → evaluator dispatches at window end → mock ChatModel returns canned verdict → trust recorder logs correct outcome → CoachingComplianceResolved fires with correct status and correlationId. |

### What's NOT tested

- Real LLM quality (prompt engineering iteration) — that's operational tuning, not a code test
- UI changes — none; this is backend-only

## File Inventory

| File | Change |
|------|--------|
| `OpenCommitment.java` | Add `GameState baselineState` field |
| `CoachingChannelBroker.java` | Populate `baselineState` for non-verifiable advice |
| `CoachingComplianceEvaluator.java` | Extend `!isVerifiable()` branch to dispatch LLM |
| `LlmComplianceWorkerFactory.java` | **New** — static factory, summarisation, prompt, parsing |
| `ComplianceVerdict.java` | **New** — record for parsed LLM response (single parsing owner) |
| `ComplianceWorkerDispatcher.java` | **New** — CDI bean, dispatch orchestration, in-flight tracking, nested `Callback` interface |
| `LlmComplianceWorkerFactoryTest.java` | **New** |
| `ComplianceWorkerDispatcherTest.java` | **New** |
| `CoachingComplianceEvaluatorTest.java` | Extended |
| `LlmComplianceIT.java` | **New** |

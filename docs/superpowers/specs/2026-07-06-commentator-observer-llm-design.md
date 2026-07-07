# Commentator LLM (Observer Mode) — Design Spec

**Date:** 2026-07-06
**Issue:** quarkmind#181
**Status:** Approved
**Scope:** Observer mode only — AI plays, human watches, commentator narrates
**Deferred:** Coach mode (#230), human feedback trust dimensions (#231)

---

## Context

The summarisation hierarchy (#182) produces structured event levels: L2 moments (game-significant events), L3 phases (strategic context shifts), L4 arcs (narrative summaries). The advisory team (#180) proved the LLM Worker dispatch pattern at game-loop latency with trust-weighted routing.

This issue adds a human-facing LLM commentator that narrates the game in real time. It uses two complementary patterns — reactive commentary (Pattern A: Worker dispatch) for immediate reactions to dramatic moments, and contextual narration (Pattern B: EventAccumulator + Worker dispatch) for periodic strategic summaries. Both feed a single `quarkmind-commentary` Qhorus channel.

The dual-pattern design validates Worker dispatch for LLM-at-game-loop-latency and demonstrates that `EventAccumulator` + `WindowPolicy` compose independently of `SummarisationRunner` — a key signal for blocks#27 extraction to casehub-blocks.

---

## Architecture

### Dual-pattern commentary

| | Pattern A (Reactive) | Pattern B (Narrative) |
|---|---|---|
| **Trigger** | Specific L2 moment fires | Window timer (~45s or 4+ moments) |
| **Latency** | Immediate (within tick) | Deferred (accumulated) |
| **Context** | CaseContext point-in-time snapshot | Temporal history + L3/L4 phases |
| **Output** | Short punchy narration | Contextual narrative paragraph |
| **Integration** | Worker dispatch via CaseHub signal | EventAccumulator + Worker dispatch |
| **Platform pattern** | Advisory Worker model | Accumulator framework + Worker dispatch |
| **Example** | "The enemy is at the gates — pulling back to defend!" | "Over the last minute, the bot secured map control with three skirmishes while expanding..." |

### Component diagram

```
                    L2 Moment Bus
                         │
              ┌──────────┼──────────┐
              ▼                      ▼
    ┌─────────────────┐    ┌─────────────────┐
    │ CommentaryTrigger│    │ Commentary       │
    │ Builder          │    │ Accumulator      │
    │ (Pattern A)      │    │ (Pattern B)      │
    │                  │    │                  │
    │ Moments → signal │    │ L2 accumulate →  │
    │ key → engine     │    │ signal key →     │
    │ dispatches Worker│    │ dispatch Worker  │
    └────────┬─────────┘    └────────┬─────────┘
             │                       │
             ▼                       ▼
    ┌────────────────────────────────────────┐
    │    quarkmind-commentary channel        │
    │    (Qhorus APPEND, STATUS messages)    │
    └────────────────────────────────────────┘
             │
             ▼
    ┌────────────────────────────────────────┐
    │    CommentaryChannelBackend            │
    │    (HumanObserverChannelBackend)       │
    │    → Electron visualizer / WebSocket   │
    └────────────────────────────────────────┘
```

---

## Pattern A — Reactive Commentary

### CommentaryTriggerBuilder (@ApplicationScoped CDI bean)

Runs in `GameTickExecutor.execute()` after summarisation tick, before advisory triggers. CDI bean (not static utility) because cooldown requires per-instance state — unlike `AdvisoryTriggerBuilder` which is stateless.

- Reads `MOMENTS_LATEST` from CaseContext
- Triggers on ALL `GameMomentType` values — commentary reacts to every moment type, including those advisory drops (FIRST_CONTACT, BATTLE_ENDED, BUILDING_LOST, SCOUT_LOST) plus the three new types (ARMY_SHIFT, POSTURE_CHANGE, GAME_ENDING)
- Maps all moment types to a single `game.commentary.trigger` CaseFile key
- Batches all moments from the current tick into one trigger payload
- Cooldown: tracks last-fired frame, skips if within 110 frames (~5s at 22.4fps)
- Payload: moment types, game frame, game state summary (minerals, supply, army size)

### Reactive Commentary Worker

Created via factory (same pattern as `AdvisoryWorkerFactory`), dispatched by engine.

- Registered on `starcraft-game` case definition with capability `commentary-reactive`
- `ContextChangeTrigger` on `game.commentary.trigger` key
- Reads trigger payload + CaseContext game state
- LLM call with system prompt shaped by disposition traits
- Posts STATUS to `quarkmind-commentary` channel via `CommentaryChannelBroker`
- Fires `CommentaryCompleted` CDI event (commentary-specific fields) AND `LlmWorkerCompleted` CDI event (shared latency recording)

---

## Pattern B — Narrative Commentary

### Architectural approach

Pattern B uses the `EventAccumulator` + `WindowPolicy` framework for accumulation (cheap, runs in-tick), then dispatches a Worker for the async LLM call — same fire-and-forget pattern as Pattern A. This avoids the synchronous `Summariser` interface, which would block the game loop (LLM calls are 1–5 seconds vs the 500ms tick budget).

Pattern B does NOT use `SummarisationRunner` or implement `Summariser<IN, OUT>`. Those are synchronous contracts designed for pure-Java heuristics (microsecond completion). An LLM-backed implementation would violate the contract and stall the game tick.

### CommentaryAccumulator (@ApplicationScoped)

Manages windowed accumulation of L2 moments for narrative dispatch.

- Subscribes to the L2 moment bus at `@PostConstruct` (same bus as phaseRunner)
- Wraps `EventAccumulator<GameMoment>` with `WindowPolicy`: ~1000 frames (~45s) OR 4 moments
- Longer than phase window (672 frames/30s) to avoid commentary overlapping with phase transitions
- `tick(long now)`: if `accumulator.shouldEmit(now)`, drains batch, snapshots current L3/L4 context from `NarrativeContextHolder`, returns trigger map with serialized batch + context snapshot. Returns empty map if window has not emitted.
- Called from `GameTickExecutor` at step 4b. Returns trigger map — does NOT write to CaseFile directly (follows the `AdvisoryTriggerBuilder.buildTriggers()` pattern where trigger builders return data and `caseHub.signal()` writes to CaseFile + dispatches Workers)

### Narrative Commentary Worker

Created via factory (same pattern as reactive Worker), dispatched by engine on CaseFile key change.

- Registered on `starcraft-game` case definition with capability `commentary-narrative`
- `ContextChangeTrigger` on `game.commentary.narrative.trigger` key
- Reads accumulated batch payload + L3/L4 context snapshot from CaseFile (snapshotted at accumulation emit time — NOT a live read from `NarrativeContextHolder`)
- System prompt includes disposition traits, current phase, arc progression, game state
- LLM call generates a paragraph of contextual narration
- Posts STATUS to `quarkmind-commentary` channel via `CommentaryChannelBroker`
- Fires `CommentaryCompleted` + `LlmWorkerCompleted` CDI events

### NarrativeContextHolder (@ApplicationScoped)

Holds live L3/L4 context for snapshotting by `CommentaryAccumulator`.

- Subscribes to L3 phase bus and L4 arc bus at `@PostConstruct`
- Maintains latest `GamePhase` and latest `GameArc` as volatile fields
- Injected into `CommentaryAccumulator` — context is snapshotted at accumulation emit time (step 4b), NOT read live by the Worker (which executes 1–5 seconds later, by which time context may have changed)
- Cleared on `@Observes GameStarted`

---

## Data Model

### Commentary (record)

```java
record Commentary(String text, long gameFrame, CommentaryType type)
```

### CommentaryType (enum)

```java
enum CommentaryType { REACTIVE, NARRATIVE }
```

---

## Channel Infrastructure

### quarkmind-commentary channel

- Qhorus channel with APPEND semantic
- STATUS messages only (Observer mode — no obligations)
- NOT the normative oversight channel — oversight carries obligation-bearing speech acts (COMMAND, QUERY, DONE), which commentary STATUS does not (see Design Decisions below)

### Design Decision: Separate channel vs oversight channel

Issue #181 body specifies "oversight channel" for commentary output. This spec deviates deliberately: the oversight channel carries obligation-bearing speech acts (COMMAND, QUERY, DONE) with normative force. Commentary STATUS messages are informational — mixing them with obligation-bearing messages in the same channel would blur the semantic contract and require downstream consumers to distinguish between actionable and informational messages. The separate `quarkmind-commentary` channel preserves clean speech-act semantics. Issue #181 will be updated to reflect this decision

### CommentaryChannelBroker (@ApplicationScoped)

- Owns `quarkmind-commentary` channel
- Receives commentary from both patterns
- Dispatches STATUS messages via `MessageService.dispatch()`
- Sender: `"commentary.reactive"` or `"commentary.narrative"`
- Actor type: AGENT

### CommentaryChannelBackend (HumanObserverChannelBackend)

- Backend ID: `quarkmind-commentary-observer`
- Actor type: HUMAN
- Stores latest commentary for WebSocket/visualizer consumption
- Same pattern as existing `AdvisoryChannelBackend`

---

## Eidos Registration

### Commentator Descriptors

Four descriptors registered via `QuarkMindAgentRegistrar` (renamed from `QuarkMindAdvisorRegistrar`):

| Agent ID | Capability | Personality |
|---|---|---|
| `claude:commentator-energetic@v1` | `commentary-reactive` | Enthusiastic, vivid, exclamatory |
| `claude:commentator-analytical@v1` | `commentary-reactive` | Calm, precise, measured |
| `claude:narrator-dramatic@v1` | `commentary-narrative` | Story-driven, dramatic arc |
| `claude:narrator-tactical@v1` | `commentary-narrative` | Data-heavy, tactical analysis |

Model family: claude. Model: sonnet-4.

### CommentaryDispositionTerm

Quarkmind-specific vocabulary implementing `VocabularyTerm`:

```java
enum CommentaryDispositionTerm implements VocabularyTerm {
    ENERGETIC,    // energy axis — enthusiastic, exclamatory
    ANALYTICAL,   // energy axis — calm, precise, measured
    DRAMATIC,     // style axis — story-driven, narrative arc
    TACTICAL      // style axis — data-heavy, tactical analysis
}
```

Registered via `VocabularyRegistrar` CDI bean.

### Disposition axis mapping

`DispositionPreference.computeMultiplier()` evaluates exactly two axes: `riskAppetite` and `ruleFollowing`. Commentary personality maps to these existing axes:

| CommentaryDispositionTerm | AgentDisposition field | Value | Rationale |
|---|---|---|---|
| ENERGETIC | riskAppetite | bold | Expressive risk-taking in narration |
| ANALYTICAL | riskAppetite | conservative | Measured, careful analysis |
| DRAMATIC | ruleFollowing | flexible | Bends narrative structure for drama |
| TACTICAL | ruleFollowing | strict | Follows data and facts strictly |

Commentary `AgentDescriptor` instances built in `QuarkMindAgentRegistrar` with these concrete axis values. The routing infrastructure works as-is — `DispositionAwareRoutingStrategy.resolvePreference()` needs commentary-specific game-context mappings added to produce non-neutral multipliers.

### Game-context disposition preference

Commentary routing preferences added to `DispositionAwareRoutingStrategy.resolvePreference()`:

- High combat density → prefer riskAppetite:bold (ENERGETIC narration for action)
- Economic phases → prefer riskAppetite:conservative (ANALYTICAL narration for strategy)
- Phase transitions → prefer ruleFollowing:flexible (DRAMATIC narration for turning points)
- Stable states → prefer ruleFollowing:strict (TACTICAL narration for analysis)

---

## Trust Dimensions

One auto-evaluated dimension for the first implementation:

### 1. Response latency (dimension key: `response-latency`)

Shared `LlmWorkerLatencyRecorder` observes `LlmWorkerCompleted` CDI events (fired by both advisory and commentary Workers). Records latency trust for all LLM worker types.

| Capability | Max acceptable latency |
|---|---|
| `commentary-reactive` | 2000ms |
| `commentary-narrative` | 5000ms |

Normalized score: `1.0 - (actualMs / maxMs)`, clamped to [0.01, 1.0].

### Game outcome — advisory only, not applicable to commentary

Game outcome trust (`AdvisoryGameOutcomeRecorder`) stays advisory-specific. Commentary is observer-mode narration with zero causal influence on game outcome — attributing WIN/LOSS to commentators would introduce noise, not signal. A good narrator during a losing streak would be incorrectly penalised.

`AdvisoryGameOutcomeRecorder` continues to observe `AdvisoryCompleted` only. `AdvisoryInvocationCounter` stays advisory-specific (not renamed).

### Deferred dimensions

Timing quality and narration accuracy require human feedback signals (skip/dismiss/thumbs-up in visualizer) that don't exist yet. Filed as #231. This is acknowledged as a trust signal weakness — commentary routing relies solely on latency until human feedback infrastructure arrives.

### Trust routing policy

`QuarkMindTrustRoutingPolicyProvider` refactored: `buildPolicy()` split into `buildAdvisoryPolicy()` and `buildCommentaryPolicy()` with capability-specific quality floor maps.

Added to `forCapability()`:

| Capability | Min Observations | Quality Floors |
|---|---|---|
| `commentary-reactive` | 5 | response-latency: 0.4 |
| `commentary-narrative` | 5 | response-latency: 0.3 |

Lower min observations (5) for both — commentary is frequent, trust should converge fast. No `recommendation-quality` or `game-outcome` floors — neither dimension applies to commentary.

---

## Generalization: Advisory → LLM Worker Infrastructure

### Three-event completion model

`AdvisoryCompleted` stays unchanged — it has advisory-specific fields (`recommendation`, `confidence`, `gameStateSnapshot`) consumed by `DeferredAdvisoryEvaluator` and `AdvisoryChannelBroker`. Renaming it to `LlmWorkerCompleted` would break these downstream consumers with null/dummy fields.

New events:

| Event | Fields | Fired by |
|---|---|---|
| `CommentaryCompleted` | workerId, capability, gameFrame, text, commentaryType, latencyMs | Commentary Workers |
| `LlmWorkerCompleted` | workerId, capability, gameFrame, latencyMs | Both advisory and commentary Workers (secondary event) |

`LlmWorkerCompleted` carries only the genuinely shared fields. The latency recorder observes this single event type. Domain-specific observers continue to observe their domain events (`AdvisoryCompleted`, `CommentaryCompleted`).

### Migration: existing advisory code changes

The `CompletionCallback` lambda in `QuarkMindCaseHub.wireAdvisory()` currently fires only `AdvisoryCompleted`. Required changes:

1. **Inject `Event<LlmWorkerCompleted>`** into `QuarkMindCaseHub` alongside existing `Event<AdvisoryCompleted>`
2. **Update `wireAdvisory()` callback lambda** to fire both events:
   - `AdvisoryCompleted` — unchanged (all advisory-specific fields)
   - `LlmWorkerCompleted` — new (workerId, capability, gameFrame, latencyMs only)
3. **`LlmWorkerLatencyRecorder`** (renamed from `AdvisoryLatencyRecorder`) observes `LlmWorkerCompleted` only — NOT `AdvisoryCompleted`. The rename and event-type change must happen atomically to avoid silent latency recording breakage.
4. **Commentary wiring** (`wireCommentary()`, new method): uses a new `CommentaryCompletionCallback` functional interface with commentary-specific parameters (workerId, capability, gameFrame, text, commentaryType, latencyMs). Fires both `CommentaryCompleted` and `LlmWorkerCompleted`.

### Infrastructure renames

| Current | Renamed | Rationale |
|---|---|---|
| `AdvisoryLatencyRecorder` | `LlmWorkerLatencyRecorder` | Observes `LlmWorkerCompleted` — shared across all LLM workers |
| `QuarkMindAdvisorRegistrar` | `QuarkMindAgentRegistrar` | Registers both advisors and commentators |

**Stays advisory-specific** (no rename):
- `AdvisoryCompleted` — advisory-specific fields (`recommendation`, `confidence`, `gameStateSnapshot`)
- `AdvisoryCompletionObserver` — forwards to advisory-specific counter
- `AdvisoryInvocationCounter` — tracks advisory workers only (game outcome attribution must not mix commentary)
- `AdvisoryGameOutcomeRecorder` — iterates `AdvisoryInvocationCounter`, game outcome is advisory-only
- `DeferredAdvisoryEvaluator` — recommendation quality doesn't apply to commentary
- `AdvisoryChannelBroker` — specific to quarkmind-advisory channel
- `AdvisoryChannelBackend` — specific to advisory observation
- `AdvisoryTriggerBuilder` — maps moments to advisory trigger keys
- `AdvisoryWorkerFactory` — creates advisory Workers

New commentary-specific:
- `CommentaryCompletionObserver` — observes `CommentaryCompleted`, forwards to commentary invocation tracking
- `CommentaryWorkerFactory` — creates commentary Workers

---

## New GameMomentTypes

Three additions to the enum and corresponding Drools rules in `MomentDetectionRuleUnit`:

| Type | Detection logic | State requirements |
|---|---|---|
| `ARMY_SHIFT` | Army mineral+gas value changes by >30% between consecutive moment detection ticks | `previousArmyValue` field in `MomentDetectionRuleUnit` for delta comparison |
| `POSTURE_CHANGE` | Enemy posture field changes (e.g., MACRO → ATTACK) | `previousPosture` field in `MomentDetectionRuleUnit` for change detection |
| `GAME_ENDING` | Enemy building count reaches 0, or own building count reaches 0 | Stateless — threshold check on current tick data |

Implementation note: `ARMY_SHIFT` and `POSTURE_CHANGE` require stateful comparison against previous-tick values. New fields added to `MomentDetectionRuleUnit` (same pattern as existing `firstContactFired` boolean state). Values updated at end of each `fireRules()` invocation after deduplication.

---

## Game Loop Integration

In `GameTickExecutor.execute()`:

```
1.  engine.tick() + observe()
2.  translator.toMap() → case data
3.  caseHub.signalAndAwaitSync()       ← plugins run (incl. MomentDetectionTask)
4.  summarisationLifecycle.tick()       ← L2→L3, L3→L4 runners (existing)
4b. commentaryAccumulator.tick()       ← NEW: returns narrative trigger map (accumulation + context snapshot, in-memory only)
5.  milestoneOutcomeRecorder.evaluate()
6.  deferredAdvisoryEvaluator.evaluate()
7.  commentaryTriggerBuilder.build()   ← NEW: returns reactive trigger map (Pattern A)
8.  caseHub.signal(reactiveTriggers)   ← NEW: fire-and-forget reactive Worker
8b. caseHub.signal(narrativeTriggers)  ← NEW: fire-and-forget narrative Worker (if 4b returned non-empty)
9.  caseHub.signal(advisoryTriggers)   ← existing advisory triggers
10. engine.dispatch()
```

Steps 4b and 7 return trigger maps (in-memory, no CaseFile write). Steps 8, 8b, and 9 pass those maps to `caseHub.signal()`, which writes to CaseFile keys and dispatches Workers via `ContextChangeTrigger`. This follows the established `AdvisoryTriggerBuilder.buildTriggers()` → `caseHub.signal()` pattern.

Pattern B: accumulation at 4b (microseconds), Worker dispatch at 8b (fire-and-forget async LLM call).
Pattern A: trigger check at 7 (microseconds), Worker dispatch at 8 (fire-and-forget async LLM call).

### Concurrent commentary — dual-pattern interaction

Both patterns can fire on the same tick (the moment that fills Pattern B's 4-moment threshold also triggers Pattern A, which reacts to ALL moment types). This is intentional — sports broadcasts regularly interleave instant reactions with periodic summaries.

**Redundancy mitigation:** The narrative Worker's system prompt explicitly instructs: "Narrate the strategic arc and context — do NOT repeat specific moments that were just announced." The reactive and narrative Workers serve different purposes (immediate reaction vs contextual summary), so natural language dedup through prompting is sufficient. The narrative Worker does not need to know what the reactive Worker said — their outputs are structurally different enough that overlapping content reads as reinforcement, not repetition.

**Frequency during battles:** The 4-moment count trigger could cause narrative emissions every 8-16 seconds during intense combat (moments every 2-4 seconds). To prevent this, `CommentaryAccumulator` enforces a **minimum time floor** alongside the window policy: even if 4 moments accumulate, don't emit until at least 672 frames (~30s) have passed since the last narrative emission. During battles, Pattern A's reactive commentary covers the play-by-play; Pattern B waits for a lull to summarize.

**Expected frequency ranges:**
- Pattern A (reactive): at most once per 5 seconds (110-frame cooldown), typically 2-4 times per minute during active play
- Pattern B (narrative): at most once per 30 seconds (minimum time floor), typically once per 45-60 seconds

---

## Testing Strategy

### Unit tests (plain JUnit)

- `CommentaryTriggerBuilderTest` — moment→trigger mapping, cooldown enforcement, batching
- `CommentaryAccumulatorTest` — window policy, accumulation, CaseFile signal on emit
- `NarrativeCommentaryWorkerTest` — mock ChatModel, verify prompt includes L3/L4 context
- `NarrativeContextHolderTest` — captures latest phase/arc from bus, clears on GameStarted
- `CommentaryDispositionTermTest` — vocabulary term registration
- `ReactiveCommentaryWorkerTest` — mock ChatModel, verify output format
- `MomentDetectionTaskTest` — extend for ARMY_SHIFT, POSTURE_CHANGE, GAME_ENDING rules
- `LlmWorkerLatencyRecorderTest` — verify latency recorded from `LlmWorkerCompleted` events
- Existing advisory tests — verify pass unchanged (no Advisory→LlmWorker rename on core classes)

### Integration tests (@QuarkusTest)

- `CommentaryChannelBrokerIT` — both patterns dispatch to quarkmind-commentary channel
- `CommentaryPipelineIT` — end-to-end: moments → commentary in channel
- Existing `AdvisoryIntegrationIT` — verify passes after rename

---

## Blocks Validation (blocks#27)

| Framework type | Validated by |
|---|---|
| `EventStreamBus` | Pattern B subscribes to L2/L3/L4 buses |
| `EventAccumulator` | Pattern B uses distinct window policy (~45s/4 moments) |
| `Summariser<IN, OUT>` | Validated by existing phase + arc summarisers (pure-Java, synchronous) — LLM calls are async and belong in Workers, not Summarisers |
| `SummarisationRunner` | Validated by existing phase + arc runners |
| `LevelEvent` | Consumed by both patterns |
| `WindowPolicy` | Third policy configuration validates customizability |

After this feature: 2 summarisers + 1 accumulator-only consumer, 3 window policies, multi-level subscription. Commentary validates that `EventAccumulator` and `WindowPolicy` compose independently of `SummarisationRunner` — a stronger extraction signal than another Summariser implementation.

---

## Configuration

### application.properties additions

```properties
# Commentary trust routing (same 3 flags as advisory — already present)
# casehub.ledger.trust-score.enabled=true
# casehub.ledger.trust-score.incremental.enabled=true
# casehub.ledger.trust-score.materialization.enabled=true

# Commentary-specific config
quarkmind.commentary.reactive.cooldown-frames=110
quarkmind.commentary.narrative.window-frames=1000
quarkmind.commentary.narrative.window-count=4
quarkmind.commentary.trust.min-observations=5
```

---

## Platform Coherence Review

- **Step 1 (exists?):** No commentator/narrator in any CaseHub app. Novel feature.
- **Step 2 (right repo?):** SC2-specific narration is domain logic → quarkmind. Correct tier.
- **Step 3 (consolidation?):** LlmWorker* generalization consolidates advisory infrastructure. `CommentaryAccumulator` validates independent composition of `EventAccumulator` + `WindowPolicy` for blocks#27.
- **Step 4 (consistent?):** Worker dispatch, eidos registration, trust routing, channel broker/backend — all follow existing platform patterns.
- **Step 5 (doc update?):** No platform doc change needed — application tier only.

### Protocols checked

| Protocol | Status |
|---|---|
| trust-routing-config-flags-required | Commentary uses same 3 flags — already configured |
| competing-strategy-implementations-concrete-injection | Multiple commentator impls → concrete type injection in @QuarkusTest |
| game-lifecycle-observer-synchrony | NarrativeContextHolder uses @Observes (sync) for GameStarted |
| scouting-consumer-postconstruct-required | NarrativeContextHolder subscribes in @PostConstruct — follows pattern |

---

## Deferred Issues

| # | Title | Rationale |
|---|---|---|
| #230 | Coach mode — real-time advice for human players | Different game loop architecture, COMMAND speech acts, human action attribution |
| #231 | Human feedback trust dimensions — timing quality and accuracy | Requires visualizer UI (skip/dismiss/thumbs-up) that doesn't exist yet |

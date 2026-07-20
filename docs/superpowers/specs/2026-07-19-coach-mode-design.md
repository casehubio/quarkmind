# Coach Mode — Real-Time Actionable Advice for Human Players

**Issue:** #230
**Status:** Approved
**Date:** 2026-07-19

## Overview

Coach mode is a new game loop variant where a human plays StarCraft II and QuarkMind observes and advises in real-time. The AI does not control the game — it watches, detects coaching moments via the existing scouting/moment detection pipeline, and delivers actionable advice as COMMAND messages on a Qhorus oversight channel. Compliance is tracked implicitly by observing whether the human's subsequent actions match the advice.

Coach mode is architecturally distinct from Observer mode (#181): the human is playing (not the AI), and advice is obligation-bearing (COMMAND, not STATUS).

## Approach

**Third LLM pipeline** — coach mode follows the same pattern as advisory and commentary: a parallel pipeline with its own trigger builder, worker factory, channel broker, and completion event. Shares detection layers (moment detection, scouting intel, summarisation) but has its own output path. The three pipelines (advisory, commentary, coaching) share detection infrastructure but have genuinely different semantics and can evolve independently.

**Not chosen:**
- Advisory extension (B) — conflates STATUS/COMMAND speech acts in one pipeline, creates coupling
- Separate game loop (C) — duplicates observation infrastructure, loses shared detection layers

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Target game mode | Engine-agnostic, testable against emulated | Pipeline reacts to game state, not engine type |
| Plugin chain in coach mode | Suppress action plugins, keep observation | Scouting + moment detection run; strategy/tactics/economics gated off |
| Relationship to commentary | Separate pipeline, shared detection | Commentary validates detection; coaching has different speech act semantics |
| Compliance detection (v1) | Implicit via unit/building count deltas | Covers ~60% of coaching scenarios; position-based (#244) and LLM-evaluated (#245) follow |
| Coaching personalities (v1) | Two fixed: directive and Socratic | Trust routing learns which works better; adaptive switching (#246) follows |
| Latency model | Tiered by urgency | Crisis: 2s hard cap. Strategic/economic: 5s soft cap |

## §1 Game Mode Gate

Coach mode is activated via a CaseFile key: `game.mode = "coach"`. Set at game start via profile config or QA endpoint.

**Plugin gating:** Strategy-routing, strategy, tactics, and economics plugins add `game.mode != "coach"` to their `activateIf()` predicate. When coach mode is active, they silently skip. Scouting, moment detection, and summarisation run unchanged.

**IntentQueue stays empty** — no commands reach the game engine. `dispatch()` is a no-op.

**Post-plugin phase:** Advisory triggers don't fire in coach mode (advisory is for AI-play self-evaluation). Commentary triggers still fire (narration remains useful). Coaching triggers fire in place of advisory triggers.

**New profile: `%coach`** composes with engine profiles:
- `mvn quarkus:dev -Dquarkus.profile=coach,emulated` — testing
- `mvn quarkus:dev -Dquarkus.profile=coach,sc2` — real play
- `mvn quarkus:dev -Dquarkus.profile=coach` — mock engine (unit testing)

**QA endpoints** (dev/test only, `@UnlessBuildProfile("prod")`):
- `POST /sc2/mode/coach` — switch to coach mode mid-session
- `POST /sc2/mode/ai` — switch back to AI-play

**Mode transition semantics:** Switching mode mid-session fires a `GameModeChanged` CDI event. Stateful components observe this event and clean up:

- **Coach → AI:** `CoachingComplianceEvaluator` WITHDRAWs all open coaching commitments with NEUTRAL trust outcome. Compliance evaluator state cleared.
- **AI → Coach:** `DeferredAdvisoryEvaluator` discards pending evaluations. `IntentQueue` is cleared (may contain stale intents from action plugins that were active in AI mode). Advisory triggers suppressed; coaching triggers begin.
- **Both directions:** In-flight LLM workers (advisory or coaching) that complete after the switch are silently discarded — the channel broker checks the current game mode before dispatching.

## §2 Coaching Pipeline Components

Package: `io.quarkmind.plugin.coaching`

### CoachingTriggerBuilder

CDI `@ApplicationScoped` bean (stateful, like `CommentaryTriggerBuilder`).

- Reads `MOMENTS_LATEST` from CaseContext
- Maps moments to urgency tiers:
  - **Crisis** (NEXUS_UNDER_ATTACK, BATTLE_STARTED, BUILDING_LOST) → 2s hard latency cap, 150 frame cooldown (~7s at 22fps)
  - **Strategic** (TECH_TRANSITION_DETECTED, ARMY_SHIFT, POSTURE_CHANGE, FIRST_CONTACT) → 5s soft cap, 110 frame cooldown
  - **Economic** (ECONOMIC_CRISIS, SUPPLY_BLOCK) → 5s soft cap, 110 frame cooldown
- Unmapped (deferred): BATTLE_ENDED (post-battle debrief timing is complex), SCOUT_LOST (low coaching value — player likely noticed), GAME_ENDING (post-game analysis is a separate feature)
- **Global cooldown with urgency preemption** (single `lastFiredFrame` + `lastFiredTier`): when any tier fires, same-or-lower urgency tiers are suppressed for that tier's cooldown duration. However, a higher-urgency event preempts the active cooldown — crisis always fires immediately regardless of an active strategic/economic cooldown, resetting the global counter. This preserves the no-burst invariant for same-tier events (two crises within 150 frames are still suppressed) while restoring the urgency hierarchy: a strategic tip at frame 100 cannot suppress a base attack warning at frame 200. Urgency order: Crisis > Strategic > Economic.
- Writes `game.coaching.trigger` with urgency tier metadata in payload
- Resets cooldown on `GameStarted`

### CoachingWorkerFactory

Plain Java static factory (like `CommentaryWorkerFactory`). Both personalities share a single capability `coaching` — this is required for trust-based routing to work, since `DispositionAwareRoutingStrategy.select()` receives candidates for a single capability and selects among them. With separate capabilities, each routing invocation would see only one candidate and trust-based learning would be inoperable.

The factory inspects the selected agent's `CoachingDispositionTerm` metadata to determine prompt style:

**Directive** (`CoachingDispositionTerm.DIRECTIVE`):
- System prompt: "You are a StarCraft II coach giving direct, actionable instructions to a player mid-game."
- Output style: imperative commands ("Build 3 Stargates now — they're going mech")

**Socratic** (`CoachingDispositionTerm.SOCRATIC`):
- System prompt: "You are a StarCraft II coach who guides players to discover the right action through targeted questions."
- Output style: guiding questions ("What do you notice about your supply? You're floating 800 minerals.")

**Crisis directive override:** When the urgency tier is CRISIS, the coaching worker always uses the directive style regardless of personality selection. A Socratic question during a base attack wastes seconds the player doesn't have. This is not the full adaptive switching deferred to #246 — it is a single hard override for the crisis tier only.

**Structured output** — unlike commentary (plain text), coaching workers return a `CoachingAdvice` record:

```java
public record CoachingAdvice(
    String advice,                        // human-readable coaching text
    CoachingDomain domainTag,             // enum: BUILD, MILITARY, EXPAND, TECH
    UnitType verificationUnitType,        // nullable — unit to check for
    BuildingType verificationBuildingType, // nullable — building to check for (mutually exclusive with unitType)
    Integer verificationCountDelta,       // nullable — how many should appear
    int verificationWindowFrames          // evaluation delay (default ~450 frames / 20s)
) {}
```

`CoachingDomain` is a constrained enum (`BUILD`, `MILITARY`, `EXPAND`, `TECH`) — not a free-text string — to ensure supersession consistency. The LLM prompt includes the enum values and their semantics.

**Verification predicate validation:** The coaching worker validates structured output before returning:
1. If `verificationUnitType` is non-null, validate against `UnitType` enum — on mismatch, set to null (non-verifiable)
2. If `verificationBuildingType` is non-null, validate against `BuildingType` enum — on mismatch, set to null
3. At most one of `verificationUnitType`/`verificationBuildingType` can be non-null
4. If `verificationCountDelta` is null but a verification type is set, set the type to null (both or neither)
5. The LLM prompt includes the valid `UnitType` and `BuildingType` enum values to minimise mismatches
6. Clamp `verificationWindowFrames` to a minimum floor: `max(verificationWindowFrames, 200)` (~9s at 22fps). A pathologically small window (0 or near-zero) would check the predicate before the player has any chance to act, producing inevitable CHALLENGED outcomes and corrupting trust data.

Output keys: `agent.coaching.{directive|socratic}.advice`, `.verification`

**Prompt context includes:** game state snapshot, game phase (from summarisation), pattern assessment (EnemyArchetype + confidence from scouting), recent moments, current army composition, resource levels.

**Latency enforcement:** LLM call uses `CompletableFuture.orTimeout()` with the urgency tier's cap. On timeout: the future completes exceptionally, the worker catches the timeout exception, and does NOT fire `CoachingCompleted`. The virtual thread terminates normally after the timeout handler runs. If the LLM responds just after timeout, the response is discarded (future already completed). No coaching advice is issued — better to miss a moment than deliver stale advice.

### CoachingSessionSelector

CDI `@ApplicationScoped` bean. Enforces the "single coach agent selected per game" invariant.

- On the first coaching trigger per game: invokes `DispositionAwareRoutingStrategy.select()` with capability `coaching` and caches the selected agent ID
- All subsequent triggers within the same game return the cached selection — routing is NOT re-invoked, preventing mid-game personality switches that would violate the "one voice" invariant
- Clears cached selection on `GameStarted` CDI event
- During bootstrap (both agents lack trust data): uses `quarkmind.coaching.default-personality` config to determine preference. The routing strategy's workload-based fallback selects between equal candidates; the config biases the initial selection before trust data accumulates
- If the cached agent is the Socratic personality and the urgency tier is CRISIS, the crisis directive override (§2 CoachingWorkerFactory) still applies — the session selector determines the *agent*, the factory determines the *prompt style* for that invocation

### CoachingCompletionCallback

Fires `CoachingCompleted` CDI event (advice, verification predicate, urgency tier, agent descriptor) and shared `LlmWorkerCompleted` for latency recording.

### CoachingChannelBroker

CDI `@ApplicationScoped` bean. Owns `quarkmind-coaching` channel.

- Allowed types: `COMMAND`, `DONE`, `DECLINE` (not STATUS — key difference from commentary/advisory)
- Semantic: `ChannelSemantic.APPEND`
- Observes `CoachingCompleted`, dispatches as COMMAND
- **correlationId generation:** Each COMMAND dispatch includes a non-null correlationId (`UUID.randomUUID().toString()`). This is required for Qhorus commitment auto-open (per GE-20260517-5de55b) — without correlationId, `commitmentService.open()` is never called and the entire compliance tracking system is silently inert
- COMMAND + non-null correlationId → Qhorus auto-opens commitment
- Stores correlationId alongside verification predicate and source game frame for `CoachingComplianceEvaluator`
- **Frame-ordering gate:** Tracks the latest trigger source frame per `CoachingDomain`. Before dispatching a COMMAND, compares the trigger's source frame against the stored frame for that domain. If the trigger is older (out-of-order LLM response), the COMMAND is discarded — prevents stale advice from superseding fresh advice when LLM calls complete out-of-order
- **Mode gate:** Checks `game.mode` before dispatching. If mode has changed since the trigger fired (e.g., switched from coach to AI while LLM was running), the COMMAND is silently discarded

### CoachingComplianceEvaluator

CDI `@ApplicationScoped` bean. Ticked every frame by `GameTickExecutor` (post-plugin phase).

**Game state access:** The compliance evaluator receives `GameState` directly from `GameTickExecutor` (same pattern as `MilestoneOutcomeRecorder.evaluateMilestones(GameState)`). It reads `gameState.myUnits()` for per-unit-type counts and `gameState.myBuildings()` for per-building-type counts.

**Baseline snapshot timing:** The baseline count is captured on the evaluator's first tick after the commitment is registered — not at COMMAND dispatch time. The `CoachingChannelBroker` (running on a virtual thread) stores the commitment metadata but does not have access to `GameState`. The delay is one tick (~45ms at 22fps), which is negligible for count delta verification.

**Concurrency model:** The evaluator receives data from two threads — virtual thread (channel broker stores commitment metadata via CDI event) and game tick thread (evaluator iterates, checks deltas, resolves/removes). Internal state uses `ConcurrentHashMap<CoachingDomain, OpenCommitment>` — domain-keyed for O(1) supersession lookup, thread-safe for concurrent add (from virtual thread) and iterate/remove (from game tick). This is more appropriate than a list because the primary operations are domain-keyed lookup (supersession) and iteration (verification), and at most 4 domains can have open commitments simultaneously.

Tracks open coaching commitments with their verification predicates. For each open commitment past its verification window:

- **Unit count delta check:** did `verificationCountDelta` units of `verificationUnitType` appear since the COMMAND was issued? (Uses `gameState.myUnits()` filtered by type.)
- **Building count delta check:** did `verificationCountDelta` buildings of `verificationBuildingType` appear? (Uses `gameState.myBuildings()` filtered by type.)
- If yes → implicit FULFILLED, records ENDORSED outcome via trust recorder
- If no → UNFULFILLED, records CHALLENGED outcome
- **Non-verifiable advice** (both `verificationUnitType` and `verificationBuildingType` are null): auto-expires as NEUTRAL. This cleanly separates "can't verify" from "verified and failed" — non-verifiable advice does not corrupt the trust signal.

**Explicit acknowledgment** (DONE/DECLINE via Qhorus) also resolves — whichever comes first wins.

**Auto-expire:** commitments with a verification predicate and no resolution after `compliance.auto-expire-frames` (~900 frames / 40s) are expired and recorded as CHALLENGED. Commitments with no verification predicate auto-expire as NEUTRAL.

**Supersession:** each COMMAND carries a `CoachingDomain` tag (BUILD, MILITARY, EXPAND, TECH). New COMMAND in the same domain WITHDRAWs the previous open commitment with NEUTRAL trust outcome. Cross-domain advice coexists independently.

### CoachingEffectivenessTrustRecorder

CDI `@ApplicationScoped` bean. New trust dimension: `coaching-effectiveness`.

Records:
- Compliance rate (implicit + explicit FULFILLED vs CHALLENGED/expired)
- Game state improvement after complied advice (delta in army value, economy, supply)

Feeds into `DispositionAwareRoutingStrategy` — trust routing learns which coaching personality the player responds to. Game-outcome trust does NOT apply (the human made the decisions, not the coach).

## §3 Qhorus Commitment Lifecycle

### Issuing advice

1. `CoachingChannelBroker` dispatches COMMAND to `quarkmind-coaching`
2. Qhorus auto-opens commitment (correlationId is non-null)
3. Correlation ID stored with verification predicate in `CoachingComplianceEvaluator`

### Resolution paths (first to fire wins)

| Path | Trigger | Commitment state | Trust outcome |
|------|---------|-----------------|---------------|
| Implicit compliance | Verification predicate satisfied | FULFILLED | ENDORSED |
| Explicit acknowledgment | Human sends DONE | FULFILLED | ENDORSED (boosted if implicit also satisfied) |
| Explicit decline | Human sends DECLINE | DECLINED | Neutral |
| Non-verifiable | Both verification fields null, window expires | Auto-expired | NEUTRAL |
| Ignored | Window expires, predicate not met | Auto-expired | CHALLENGED |
| Withdrawn | Coach supersedes (same domain) | DECLINED (by coach) | Neutral |

### Supersession logic

Each coaching COMMAND carries a domain tag: `build`, `military`, `expand`, `tech`. When a new COMMAND arrives in the same domain, the previous open commitment is WITHDRAWN. Cross-domain advice coexists: "expand now" (expand) and "build Stalkers" (military) are independent.

### Human response correlation

Garden entry GE-20260517-5879a9: `receiveHumanMessage()` passes `correlationId=null`. For v1 with implicit compliance, this is manageable — the compliance evaluator resolves most commitments without human messages. Explicit acknowledgment UI is follow-up #248.

## §4 Game Loop Integration

Coaching slots into `GameTickExecutor`'s existing post-plugin phase:

```
4a. Summarisation lifecycle tick (L2→L3, L3→L4)         — unchanged
4b. Commentary accumulation tick                         — unchanged
4c. Milestone evaluation                                — SKIPPED in coach mode
4d. Deferred advisory evaluation                        — SKIPPED in coach mode
4e. Coaching compliance evaluation                      — NEW (coach mode only)
4f. Reactive commentary trigger + signal                — unchanged
4g. Narrative commentary trigger signal                  — unchanged
4h. Advisory trigger + signal                           — SKIPPED in coach mode
4i. Coaching trigger + signal                           — NEW (coach mode only)
```

Steps 4c/4d/4h and 4e/4i are mutually exclusive — controlled by `game.mode` CaseFile key. Commentary always runs regardless of mode.

**Milestone gating rationale:** `MilestoneOutcomeRecorder.onGameStopped()` records an `OutcomeRecord` using `strategyRouter.lastSelectedId()` as the actorId. In coach mode, strategy-routing is gated off — `lastSelectedId()` would return null/stale, producing corrupt ledger records. `MilestoneOutcomeRecorder.onGameStopped()` must check `game.mode` and skip in coach mode. (Contrast with `AdvisoryGameOutcomeRecorder.onGameStopped()` which is already safe — its `invokedAdvisors.isEmpty()` guard returns early when no advisors ran.)

**Coaching signal flow:** `CoachingTriggerBuilder` writes `game.coaching.trigger` → `caseHub.signal()` (fire-and-forget) → CaseHub engine activates coaching worker binding → `CoachingWorkerFactory`-built worker runs LLM call on virtual thread with urgency-tier timeout → `CoachingCompleted` → `CoachingChannelBroker` dispatches COMMAND.

## §5 Agent Registration and Trust Routing

### Coach agent descriptors

Registered via `QuarkMindAgentRegistrar`:

| Agent ID | Slot | Capability | Personality | Disposition |
|----------|------|------------|-------------|-------------|
| `claude:coach-directive@v1` | `coach` | `coaching` | Directive | riskAppetite: bold, socialOrient: collaborative, ruleFollowing: flexible |
| `claude:coach-socratic@v1` | `coach` | `coaching` | Socratic | riskAppetite: conservative, socialOrient: collaborative, ruleFollowing: strict |

Both agents share the **same** `coaching` capability — this follows the advisory pattern where two agents per capability compete via trust routing (e.g., `claude:crisis-aggressive@v1` and `claude:crisis-conservative@v1` both register `advisory-crisis`). All disposition axis values use `ConscientiousnessTerm` values (same vocabulary as advisory and commentary agents). The `slotVocabulary` is `ConscientiousnessTerm.URI`.

### CoachingDispositionTerm

New enum implementing `VocabularyTerm` with URI `quarkmind:coaching-disposition`:

| Term | Description |
|------|-------------|
| `DIRECTIVE` | Explicit commands, imperative voice |
| `SOCRATIC` | Guiding questions, discovery-oriented |

Two terms, one axis for v1. Expansion to two axes (urgency × directness) is follow-up #247.

`CoachingDispositionTerm` is personality metadata — it describes the coaching style, not the agent's disposition profile for routing. Disposition routing uses `ConscientiousnessTerm` values on `AgentDisposition` axes (riskAppetite, socialOrient, ruleFollowing). These are separate concerns: `CoachingDispositionTerm` determines prompt style, `ConscientiousnessTerm` determines routing preference.

### Trust routing

- `DispositionAwareRoutingStrategy` handles multi-agent selection — both coaching agents compete on the `coaching` capability, same pattern as advisory agents
- Trust dimensions: `coaching-effectiveness` (new, primary) + `response-latency` (existing, shared). Game-outcome trust does NOT apply — the human made the decisions, not the coach.
- Single coach agent selected per game via `CoachingSessionSelector` (§2): routing invoked once per game on first trigger, cached for all subsequent triggers. One voice avoids conflicting advice.
- Over multiple games, routing learns player preference and shifts selection
- CaseHub binding: one binding for `game.coaching.trigger` → `coaching` capability → routing selects one of two agents → `CoachingSessionSelector` caches for the game

**Trust routing policy:** `QuarkMindTrustRoutingPolicyProvider.forCapability()` requires a new `case "coaching"` entry. The coaching policy differs from advisory:

| Parameter | Coaching | Advisory (crisis) | Rationale |
|-----------|----------|-------------------|-----------|
| Quality floors | `coaching-effectiveness: 0.3`, `response-latency: 0.3` | `recommendation-quality: 0.2`, `response-latency: 0.3`, `game-outcome: 0.2` | No game-outcome floor — human controls outcomes |
| minimumObservations | 3 | 5 | Coaching generates fewer observations per game (session selector caches — one routing per game, not per trigger). Lower threshold prevents prolonged bootstrap. |
| blendFactor | 0.7 | 0.7 | Same as advisory |
| bootstrapEscalationRequired | false | false | Same as advisory |

New config properties:
```properties
quarkmind.coaching.trust.min-observations=3
quarkmind.coaching.trust.quality-floors.coaching-effectiveness=0.3
quarkmind.coaching.trust.quality-floors.response-latency=0.3
```

## §6 Configuration

```properties
# Coach mode activation
quarkmind.game.mode=coach

# Coaching LLM
quarkmind.coaching.model=claude-sonnet-5
quarkmind.coaching.temperature=0.3
quarkmind.coaching.max-tokens=200

# Tiered latency caps (ms)
quarkmind.coaching.latency.crisis=2000
quarkmind.coaching.latency.strategic=5000
quarkmind.coaching.latency.economic=5000

# Compliance verification
quarkmind.coaching.compliance.default-window-frames=450
quarkmind.coaching.compliance.auto-expire-frames=900

# Default personality (overridden by trust routing after enough data)
quarkmind.coaching.default-personality=directive
```

**Profile composition:** `%coach` composes with `%emulated`, `%sc2`, `%mock`.

**Trust routing config:** the three `casehub.ledger.trust-score.*` flags (protocol PP-20260610-bd14ab) must be enabled (already required for advisory). Additionally, `QuarkMindTrustRoutingPolicyProvider` must be updated with a `case "coaching"` entry defining coaching-specific quality floors and observation thresholds (see §5 Trust routing policy table).

## §7 Testing Strategy

### Unit tests (no Quarkus)

- `CoachingTriggerBuilderTest` — moment-to-urgency mapping, cooldown per tier, payload structure
- `CoachingWorkerFactoryTest` — prompt generation for directive/socratic, structured output parsing, latency timeout wiring
- `CoachingComplianceEvaluatorTest` — implicit compliance (units appeared), ignored (window expired), supersession (same domain withdraws old), auto-expire
- `CoachingSessionSelectorTest` — caches on first trigger, returns same agent for subsequent triggers, clears on GameStarted, default-personality bootstrap
- `CoachingDispositionTermTest` — term ↔ disposition mapping, vocabulary registration
- `CoachingEffectivenessTrustRecorderTest` — compliance rate, game state delta scoring

### Integration tests (`@QuarkusTest`)

- `CoachingPipelineIT` — full tick in coach mode: moment → trigger → worker → COMMAND on channel → commitment opened. Action plugins gated off, scouting/moment detection run.
- `CoachingComplianceIT` — issues COMMAND, advances game state to satisfy predicate, asserts implicit FULFILLED + ENDORSED. Second scenario: advance past window without compliance, assert CHALLENGED.
- `CoachingCommitmentLifecycleIT` — supersession (two COMMANDs same domain, first WITHDRAWN). Cross-domain (two COMMANDs different domains coexist).
- `CoachingTrustRoutingIT` — multiple games, different compliance rates per personality, assert trust routing shifts selection.

### Emulated game testing

Coach mode against `EmulatedGame` with synthetic "human" actions — emulated player follows some advice and ignores others, validating full compliance detection loop. `%coach` composes with `%emulated`.

### Not tested in v1

- Real SC2 human-play integration (requires SC2 + human)
- Position-based verification predicates (#244)
- LLM-evaluated compliance (#245)
- Adaptive personality switching (#246)

## §8 Scoped Follow-ups

| Follow-up | Issue | Epic |
|-----------|-------|------|
| Position-based compliance | #244 | #250 Compliance Evolution |
| LLM-evaluated compliance | #245 | #250 Compliance Evolution |
| Coaching acknowledgment UI | #248 | #250 Compliance Evolution |
| Adaptive personality (intra-game) | #246 | #251 Personality Model |
| Disposition axes expansion | #247 | #251 Personality Model |
| Full tactic taxonomy (all phases) | #243 | #252 Knowledge Infrastructure |
| Commentary training dataset | #249 | #252 Knowledge Infrastructure |

## Component Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                    GameTickExecutor                              │
│                                                                 │
│  Phase 1-2: Physics + Broker (unchanged)                        │
│  Phase 3:   Plugins via CaseHub                                 │
│             ├─ Scouting ✓       (runs in coach mode)            │
│             ├─ Strategy-routing ✗ (gated off)                   │
│             ├─ Strategy ✗        (gated off)                    │
│             ├─ Tactics ✗         (gated off)                    │
│             ├─ Economics ✗       (gated off)                    │
│             └─ Summarisation ✓   (moment detection runs)        │
│  Phase 4:   Post-plugin                                         │
│             ├─ 4a-b: summarisation, commentary accum            │
│             ├─ 4c: milestone evaluation (SKIPPED in coach mode) │
│             ├─ 4e: CoachingComplianceEvaluator (NEW)            │
│             ├─ 4f-g: commentary triggers (unchanged)            │
│             └─ 4i: CoachingTriggerBuilder → signal (NEW)        │
│  Phase 5:   Dispatch (no-op — IntentQueue empty)                │
└─────────────────────────────────────────────────────────────────┘
                              │
                    caseHub.signal() (fire-and-forget)
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    CaseHub Engine                                │
│                                                                 │
│  Binding: game.coaching.trigger → coaching capability            │
│  CoachingSessionSelector returns cached agent (or routes once)   │
│  CoachingWorkerFactory builds directive or socratic worker       │
│  LLM call on virtual thread with urgency-tier timeout           │
│  Returns: advice + verification predicate                       │
└─────────────────────────────────────────────────────────────────┘
                              │
                    CoachingCompleted CDI event
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│              CoachingChannelBroker                               │
│                                                                 │
│  Channel: quarkmind-coaching (COMMAND/DONE/DECLINE)             │
│  Dispatches COMMAND → Qhorus auto-opens commitment              │
│  Stores correlationId + verification predicate                  │
└─────────────────────────────────────────────────────────────────┘
                              │
                    correlationId + predicate
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│           CoachingComplianceEvaluator                            │
│                                                                 │
│  Ticked every frame (post-plugin phase)                         │
│  For each open commitment past verification window:             │
│    Check game state → unit/building count delta                 │
│    Satisfied → FULFILLED + ENDORSED                             │
│    Not satisfied → CHALLENGED                                   │
│    Superseded (same domain) → WITHDRAWN                         │
│  Records to CoachingEffectivenessTrustRecorder                  │
└─────────────────────────────────────────────────────────────────┘
```

## Protocol Compliance

| Protocol | Relevance | Compliance |
|----------|-----------|------------|
| PP-20260610-88dbbd (observer synchrony) | Coach observers on GameStarted/GameStopped | Use `@Observes` (synchronous) for any observer reading volatile coaching state |
| PP-20260610-bd14ab (trust routing config) | Coaching effectiveness trust dimension | Three `casehub.ledger.trust-score.*` flags required — already configured for advisory |
| PP-20260610-3c3e89 (competing strategy impls) | Not directly applicable | Coach mode doesn't add a StrategyTask — it gates existing ones off |
| PP-20260601-5fa812 (plugin seam visibility) | Coaching interfaces | Public seam interfaces, package-private implementations |

## Garden Entries

| Entry | Relevance |
|-------|-----------|
| GE-20260517-5879a9 | `receiveHumanMessage()` passes `correlationId=null` — human explicit acknowledgment deferred to #248 |
| GE-20260517-5de55b | `MessageService.send()` auto-opens commitment on COMMAND — coaching relies on this |
| GE-20260609-e53d82 | Oversight gate with `commandMessageId=-1L` — coaching v1 does not exercise this path: the compliance evaluator resolves commitments via game state observation (implicit compliance), not via Qhorus DONE/DECLINE message replies. The oversight gate applies to DONE/DECLINE senders who must reference the originating COMMAND's message ID. Explicit acknowledgment UI (#248) will need to handle this. |
| GE-20260622-e779f1 | `StoredMessageTypePolicy` enforces COMMAND/QUERY only — coaching channel uses COMMAND, compliant |
| GE-20260605-73c9d6 | `CommitmentState.DECLINED` not `CANCELLED` — use DECLINED for coach-initiated withdrawal |

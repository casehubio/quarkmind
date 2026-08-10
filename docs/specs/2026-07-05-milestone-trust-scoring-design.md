# Milestone-Based Trust Scoring — Design Spec

**Issue:** quarkmind#191
**Date:** 2026-07-05
**Cross-repo dependency:** engine#648 (`OutcomeRecorder.addAttestation` SPI)
**Deferred:** #223 (multi-factor DominanceAssessor), #224 (advisory milestones), #225 (proportional game-end attribution for strategy pivots)

---

## Problem

A binary win/loss outcome is a coarse signal for trust routing. A strategy that dominates for 90% of a game and loses on one late mistake is penalised at the same rate as one outplayed from the start. Conversely, a lucky win from a poor position is rewarded as heavily as a dominant win.

The existing trust pipeline records a single `OutcomeRecord` per strategy per game at confidence 1.0. The ledger's Beta distribution model (`TrustScoreComputer`) already supports variable-confidence attestations — `weight = decayWeight × confidence` — and multiple attestations per `LedgerEntry`. The computation infrastructure handles this; the API and the application-side recording logic don't use it.

## Solution

Record intermediate trust attestations at configurable game-time milestones. Each milestone evaluates strategy dominance and produces an attestation on the **same ledger entry** as the game-end outcome, preserving `decisionCount = games played`.

### Confidence model

```
confidence = temporalWeight × |dominanceScore|
```

- **temporalWeight** — how much of the game has elapsed. Early milestones (0.3) shift belief less than late ones (0.5). Game-end is always 1.0.
- **dominanceScore** — how clear the signal is, in [-1.0, +1.0]. Positive = ahead, negative = behind.
- **Verdict** — `dominanceScore > 0` → ENDORSED, `< 0` → CHALLENGED.
- **Dead zone** — `|dominanceScore| < deadZoneThreshold` → skip (uninformative, avoids noise pollution).

This model is information-theoretically grounded: temporal scope and signal clarity are independent, multiplicative factors on the information content of an observation.

**Confidence semantics:** The existing `OutcomeRecord` Javadoc documents categorical confidence tiers (0.1 = tick, 0.7 = game event, 1.0 = session). Milestone confidence is continuous — the product of two [0,1] factors — producing values across the full range (e.g., 0.015–0.8). `TrustScoreComputer` treats confidence as a continuous multiplier (`weight = decayWeight × confidence`) regardless. The categorical tiers are a convention for human-authored code, not a contract. Milestones replace the convention with a principled model; both are valid inputs to the same computation.

### Effect on Beta distribution

Dominant play then loss (per game):
- ENDORSED(0.27) + ENDORSED(0.45) + CHALLENGED(1.0)
- alpha grows from milestone credit; beta from the loss → moderated penalty

Poor play then lucky win:
- CHALLENGED(0.27) + CHALLENGED(0.45) + ENDORSED(1.0)
- beta grows from milestones; alpha from win → moderated reward

No milestones fired (short game):
- ENDORSED/CHALLENGED(1.0) only → identical to today

---

## Component Architecture

### New components (all in `io.quarkmind.agent`)

**`MilestoneTrigger`** — interface deciding WHEN to evaluate.

```java
public interface MilestoneTrigger {
    List<MilestoneEvent> check(long gameFrame, MilestoneSession session);
}

public record MilestoneEvent(String milestoneId, double temporalWeight) {}
```

**`FrameThresholdTrigger`** — fires at configured game frame thresholds. Each threshold has a fixed temporal weight.

**`GamePhaseTrigger`** — fires on phase transitions from the summarisation layer (`GamePhaseSummariser`). Subscribes to `SummarisationLifecycle.phaseBus()` via `Instance<SummarisationLifecycle>` (lazy resolution to avoid circular dependency, matching the `DroolsStrategyTask` pattern). Maintains a `volatile GamePhase lastSeenPhase` field updated by the subscription callback. `check()` compares `lastSeenPhase` against the session's fired set — if a new phase has arrived since the last milestone, it fires. Temporal weight derived from game progress: `gameFrame / expectedGameLength`, clamped to [0.1, 0.8]. Each phase transition fires at most once.

**Temporal ordering dependency:** Milestone evaluation runs in `GameTickExecutor.execute()` AFTER `summarisationLifecycle.tick()`, so `GamePhaseTrigger` sees current-tick phase transitions. This ordering is required — reversing it would cause triggers to lag by one tick.

Both are CDI beans, discovered via `@Any Instance<MilestoneTrigger>`.

**`DominanceAssessor`** — interface deciding WHAT the assessment is.

```java
public interface DominanceAssessor {
    double assess(GameState state);
}
```

Returns [-1.0, +1.0]. Positive = ahead, negative = behind.

**`MultiFactorDominanceAssessor`** — four-factor implementation (economy, army value, tech tier, base count) with configurable weights and two-layer fog-of-war guard. Returns `DominanceScore(double overall, Map<String, Double> factors)`. See #223 spec for details.

```
score = clamp((mySupplyUsed - enemyArmySupplyEstimate) / maxExpectedDelta, -1.0, 1.0)
```

**Fog-of-war guard:** When `enemyUnits` is empty (no scouting data), returns 0.0 — falls in the dead zone, attestation skipped. Dominance cannot be assessed without observing the opponent. This is the common case early-game and whenever the enemy army is hidden.

Enemy army supply estimated from visible `enemyUnits` via `SC2Data.supplyCost(unit.type())` per unit. `maxExpectedDelta` configurable (default: 40 supply). Intentionally crude — the `DominanceAssessor` seam exists so a multi-factor implementation (#223) slots in without changing anything else.

**`MilestoneSession`** — `@ApplicationScoped` per-game state, reset on `GameStarted`.

```java
@ApplicationScoped
public class MilestoneSession {
    private final Map<String, UUID> entryIds = new ConcurrentHashMap<>();     // strategyId → ledger entry UUID
    private final Set<String> firedMilestones = ConcurrentHashMap.newKeySet(); // "frame:4032", "phase:MID_SKIRMISH"

    public Optional<UUID> entryId(String strategyId) { ... }
    public void setEntryId(String strategyId, UUID id) { ... }
    public boolean hasFired(String milestoneId) { ... }
    public void markFired(String milestoneId) { ... }
    public void reset() { entryIds.clear(); firedMilestones.clear(); }
}
```

Same lifecycle pattern as `GameSession` and `StrategySelector`: `@ApplicationScoped` with manual reset (game loop is not request-scoped). Uses `ConcurrentHashMap` for thread-safe visibility across the game-start thread (`reset()` via `@Observes GameStarted`) and the scheduler thread (`markFired()`/`entryId()`/`setEntryId()` via `GameTickExecutor`), matching the `volatile`/`AtomicBoolean` safety pattern in `GameSession` and `StrategySelector`.

### Changed components

**`MilestoneOutcomeRecorder`** — replaces `GameOutcomeRecorder`. Single class owning the full attestation lifecycle.

Three responsibilities:

1. **`onGameStarted(@Observes GameStarted)`** — resets `MilestoneSession`.

2. **`evaluateMilestones(GameState)`** — called each tick from `GameTickExecutor.execute()`, after `summarisationLifecycle.tick()` (so phase triggers see current-tick phases) and before `engine.dispatch()`. Iterates all triggers; for each fired event, runs `dominanceAssessor.assess()`, applies dead zone, records. First attestation per strategy calls `recordAndReturnId()` (creates entry, stores UUID in session); subsequent calls `addAttestation(entryId, verdict, confidence, capabilityTag)` where `capabilityTag = strategySelector.getOpponentContext()` at evaluation time — this naturally reflects scouting-updated opponent context for later milestones.

3. **`onGameStopped(@Observes GameStopped)`** — maps `GameResult` → verdict (WIN→ENDORSED, LOSS→CHALLENGED, TIE→SOUND, UNKNOWN→skip). If entry exists for the current strategy, calls `addAttestation(entryId, verdict, 1.0, context)`. If no milestones fired, calls `recordAndReturnId()` — identical to today's behavior.

**Strategy pivot handling:** `MilestoneSession.entryIds` is keyed by strategy ID. If `StrategySelector.selectedId` changes mid-game, subsequent milestones go to a new entry. Each strategy gets independent attestation chains.

**Known limitation — pivot asymmetry:** When a mid-game pivot occurs, the outgoing strategy's entry accumulates milestone attestations but no game-end verdict. Over many pivot games, this produces a modest positive bias for the outgoing strategy. The magnitude is bounded: milestone confidence (0.15–0.4) is much lower than game-end confidence (1.0), and pivots are rare (at most once per game). Writing a synthetic game-end attestation was considered but rejected: `SOUND` is treated as positive evidence by `TrustScoreComputer` (adds to alpha, not neutral), which would worsen the bias rather than neutralise it. No existing `AttestationVerdict` is computationally neutral. A proportional game-end attribution model is tracked in #225.

**`GameOutcomeRecorder`** — deleted. Fully replaced by `MilestoneOutcomeRecorder`. `AdvisoryGameOutcomeRecorder`'s Javadoc `{@link GameOutcomeRecorder}` reference (line 20) updates to `{@link MilestoneOutcomeRecorder}`.

**`GameTickExecutor`** — `MilestoneOutcomeRecorder` is injected via CDI. `execute()` calls `milestoneOutcomeRecorder.evaluateMilestones(gameState)` after `summarisationLifecycle.tick()` and before `engine.dispatch()`, alongside the existing side-effect calls (`deferredAdvisoryEvaluator.evaluate()`). The `GameState` from `engine.observe()` provides both `gameFrame` for trigger checks and the full state for `DominanceAssessor`. No CaseFile writes — ledger only.

### Unchanged components

- `StrategyTrustObserver` — strategy selection (unrelated to outcome recording)
- `StrategyTrustRouter` — consumes trust scores (doesn't care how they were produced)
- `StrategySelector` — per-game selection state (read by MilestoneOutcomeRecorder)
- `AdvisoryGameOutcomeRecorder` — advisory outcomes are orthogonal (#224)
- `GameSession` — provides subjectId
- `QuarkMindCapabilityTag` — capability tag constants

---

## Configuration

```properties
# Master switch
quarkmind.milestones.enabled=true

# Dead zone — |dominanceScore| below this skips the attestation
quarkmind.milestones.dead-zone-threshold=0.15

# Frame-based triggers (SC2 Faster = 22.4 fps)
quarkmind.milestones.frame-thresholds[0].frame=4032
quarkmind.milestones.frame-thresholds[0].weight=0.3
quarkmind.milestones.frame-thresholds[1].frame=10752
quarkmind.milestones.frame-thresholds[1].weight=0.5

# Phase-based triggers
quarkmind.milestones.phase-triggers.enabled=true
quarkmind.milestones.phase-triggers.expected-game-length=20160
quarkmind.milestones.phase-triggers.min-weight=0.1
quarkmind.milestones.phase-triggers.max-weight=0.8

# Supply dominance assessor (placeholder)
quarkmind.milestones.dominance.max-expected-delta=40
```

**Profile behavior:**
- `%sc2`: enabled (meaningful WIN/LOSS/TIE outcomes)
- `%test`: enabled (integration tests verify milestone infrastructure; trust storage is ephemeral)
- `%mock`, `%emulated`, `%emulated-sc2`: disabled (produce `GameResult.UNKNOWN` at game end — milestone attestations without game-end counterbalance create one-sided trust signal)
- `%replay`: disabled (observe-only)
- When disabled: `MilestoneOutcomeRecorder` skips milestone evaluation but still records game-end outcomes (preserves current behavior)

**Existing trust config unchanged:** `quarkmind.trust.strategy.min-observations` (default 10) still means "10 games" — `decisionCount` is not inflated because milestones append attestations to the same entry.

---

## Foundation dependency: engine#648

`OutcomeRecorder` SPI extension — engine#648 must deliver these methods on a **sub-interface** (e.g., `AttestingOutcomeRecorder extends OutcomeRecorder`), not as additions to `OutcomeRecorder` directly. Adding methods to the existing interface (even with `default` implementations) would make `instanceof OutcomeRecorder` always true regardless of JAR version, breaking the detection mechanism below.

```java
public interface AttestingOutcomeRecorder extends OutcomeRecorder {
    UUID recordAndReturnId(OutcomeRecord record);
    void addAttestation(UUID entryId, AttestationVerdict verdict, double confidence, String capabilityTag);
}
```

**Cross-repo coordination:** This sub-interface requirement must be communicated to the engine#648 implementation. The quarkmind dependency on `casehub-ledger-api` will pick up the new interface when the engine JAR version is bumped.

Until engine#648 ships, `MilestoneOutcomeRecorder` checks at startup whether the injected `OutcomeRecorder` is `instanceof AttestingOutcomeRecorder`. Without the sub-interface, milestone evaluation is a no-op — `evaluateMilestones()` returns immediately (log at debug level). `onGameStopped()` records game-end outcomes exactly as `GameOutcomeRecorder` does today. This preserves the BOOTSTRAP maturity model: `decisionCount` remains 1 per game, and `minimumObservations = 10` means 10 actual games. The milestone infrastructure ships testable (triggers, assessor, session are exercised in unit tests) but inert in production. When engine#648 lands and provides `AttestingOutcomeRecorder`, milestones activate automatically — no configuration changes required.

---

## Testing

### Unit tests — available now (plain JUnit)

| Test | Covers |
|------|--------|
| `FrameThresholdTriggerTest` | Fires at correct frames, no double-fire, respects session tracking |
| `GamePhaseTriggerTest` | Fires on phase transitions, temporal weight = frame/expectedLength clamped [0.1, 0.8], no double-fire |
| `MultiFactorDominanceAssessorTest` | Four factors in [-1.0, +1.0], two-layer fog-of-war guard, weighted overall, worker exclusion |
| `MilestoneSessionTest` | Entry ID per strategy, fired tracking, reset, concurrent access safety |
| `MilestoneOutcomeRecorderTest` | Milestone → correct verdict/confidence, dead zone → skip, game-end appends to existing entry, no milestones → creates entry at game-end, strategy pivot → separate entries, **SPI fallback: when injected `OutcomeRecorder` is not `AttestingOutcomeRecorder`, `evaluateMilestones()` is a no-op and game-end records via `record()` only** |

### Integration tests — after engine#648 (`@QuarkusTest`)

These tests require the `AttestingOutcomeRecorder` SPI from engine#648. They cannot be written or run until the sub-interface is available in `casehub-ledger-api`.

| Test | Covers |
|------|--------|
| `StrategyOutcomeRecordIT` (rewritten) | Full pipeline: game start → tick past milestone → game stop → one entry with multiple attestations, `decisionCount = 1`. Also: game-end only → identical to pre-milestone behavior |
| `MilestoneIntegrationIT` (new) | Multi-game: 3 games with milestones → `decisionCount = 3`. Trust score reflects milestone modulation |

---

## Out of scope

- Specific dominance metrics beyond supply → #223
- Advisory milestone attestations → #224
- INVALID verdict for games that shouldn't count statistically (noted in #191, separate concern)

# Layer 6: Trust-Weighted Strategy Routing — Design Spec

**Issue:** #158
**Date:** 2026-06-10
**Status:** Revised after two rounds of code review

---

## What L6 adds

Layer 5 (adaptive plugin selection) decides *whether* to dispatch a plugin based on game-state conditions. Layer 6 decides *which* strategic commitment to pursue — selecting among competing `StrategyTask` implementations based on their track record against detected opponent types.

The key insight: the right strategy is opponent-dependent. An early-pressure build beats a greedy economic opponent but loses to a defensive turtle. Trust routing learns, across games, which strategic commitment has the best win rate per opponent classification.

**Future scope (Approach C — not this issue):** Per-tick strategy switching based on real-time trust re-evaluation. L6 commits to one strategy per game with one mid-game checkpoint. Switching inside a game is deferred.

**Critical known limitation — L6 produces zero routing benefit until win/loss detection ships.** `GameOutcomeRecorder` records `SOUND` for all game outcomes in L6 (see §Game outcome recording). With uniform SOUND verdicts, all strategies accumulate near-identical Bayesian Beta trust scores (converging toward 1.0 after enough games) and all pass the QUALIFIED threshold — the router cannot distinguish among them and selection degrades to the tiebreaker (designated fallback). Trust routing is demonstrably correct in mechanism (verified by integration tests with seeded scores) but produces no actual strategic learning until real win/loss signals are wired.

---

## Two-loop architecture

```
OUTER LOOP (per-game — L6)
  GameStarted (synchronous CDI event)
    └─ StrategyTrustObserver.onGameStarted() [@Observes, sync]
         └─ StrategyTrustRouter selects commitment from trust history
              → StrategySelector.selectForGame(strategyId, STRATEGY_VS_UNKNOWN)

  EnemyPostureClassifiedEvent (first confident ENEMY_POSTURE, fired by DroolsScoutingTask)
    └─ StrategyTrustObserver.onPostureClassified() [@Observes, sync]
         └─ StrategyTrustRouter re-evaluates with classified opponent context
              → may pivot once; StrategySelector.markCheckpointFired() locks thereafter
         Note: fires inside DroolsScoutingTask.execute() → pivot is SAME-TICK (see §Same-tick pivot)

  GameStopped (synchronous CDI event)
    └─ GameOutcomeRecorder.onGameStopped() [@Observes, sync — see §Observer synchrony]
         └─ Reads StrategySelector before it can be reset
         └─ Records (strategyId, opponentContext, SOUND) to ledger
         └─ OutcomeRecordSaveService.save() [@Transactional] commits
              → IncrementalTrustUpdateObserver fires at TransactionPhase.AFTER_SUCCESS
              → PerActorTrustComputer.upsert() materializes decisionCount into ActorTrustScoreRepository

INNER LOOP (per-tick — L2-L5, unchanged)
  CaseEngine dispatches StrategyTask
    └─ canActivate() checks StrategySelector.isSelected(getId())
         → exactly one strategy impl runs (invariant — see §Dual-strategy invariant)
```

**Observer synchrony note:** All game lifecycle observers (`GameStarted`, `GameStopped`) use `@Observes` (synchronous):
- `StrategyTrustObserver.onGameStarted()` — in-memory trust lookup; sync eliminates race with first `gameTick()`
- `GameOutcomeRecorder.onGameStopped()` — **MUST be sync** to capture `StrategySelector` state before `StrategySelector.reset()` can fire on the next game start. In-memory `OutcomeRecorder` is non-blocking; the "blocking I/O" justification for `@ObservesAsync` applies only to the JPA backend that doesn't yet exist. Consistent with `GameSession`, which is never reset in `stopGame()` for the same reason.
- `EnemyPostureClassifiedEvent` — synchronous because it fires from inside `DroolsScoutingTask.execute()` (see §Same-tick pivot).

Only `PluginOutcomeAuditor` retains `@ObservesAsync` — per-tick CEP decisions are background I/O that need not block the game loop.

---

## Competing StrategyTask implementations

Three `@ApplicationScoped @CaseType("starcraft-game")` CDI beans, all implementing `StrategyTask`:

| Class | getId() | entryCriteria() | canActivate() gate | Macro commitment |
|---|---|---|---|---|
| `DroolsStrategyTask` | `"strategy.drools"` | `{READY, ENEMY_ARMY_SIZE}` | entry criteria + `broker.current(POSTURE).isPresent()` | Existing Drools per-tick reasoning. Adapts based on CEP intel. **STRATEGY absent until POSTURE published** — see §Activation asymmetry. |
| `EarlyPressureStrategyTask` | `"strategy.early-pressure"` | `{READY}` | entry criteria only | Commits to early military build from tick 1. No scouting dependency. Beats greedy/economic; loses to defensive or equal aggression. STRATEGY written immediately. |
| `EconomicExpansionStrategyTask` | `"strategy.economic-expansion"` | `{READY}` | entry criteria only | Commits to economic scaling. No scouting dependency. Beats passive/defensive; loses to early rushers. STRATEGY written immediately. |

**entryCriteria() choice for committed strategies:** `{READY}` — no `ENEMY_ARMY_SIZE` dependency. This means `EarlyPressureStrategyTask` and `EconomicExpansionStrategyTask` may fire **before scouting in the same tick**. This is intentional: unconditional commitment is the design point. They do not read scouting output; they have no ordering requirement on it.

**Bootstrap default:** `"strategy.drools"`. Most capable and contextually adaptive; safest fallback before trust data accumulates.

**ScoutingIntelConsumer status:** `EarlyPressureStrategyTask` and `EconomicExpansionStrategyTask` do **not** implement `ScoutingIntelConsumer`. They make unconditional commitments; scouting intel doesn't change their output. The broker's `activeTypes` union is unaffected: `DroolsStrategyTask` is always registered as a CDI bean and always contributes `POSTURE` and `TIMING_ALERT` to the union. `DroolsScoutingTask`'s CEP gate (`broker.isSubscribed(POSTURE)`) always evaluates true.

---

## Activation asymmetry — DroolsStrategyTask vs committed strategies

`DroolsStrategyTask.canActivate()` gates on `broker.current(ScoutingIntelType.POSTURE).isPresent()`. At game start, the broker's `latest` map is cleared by `ScoutingIntelBroker.onGameStarted()`. Consequently:

- When `DroolsStrategyTask` is the selected strategy, **STRATEGY is absent from the CaseFile for the first 20–40 ticks** until `DroolsScoutingTask` writes POSTURE to the broker. During this window, `DroolsTacticsTask` (which requires STRATEGY in entry criteria) is also inactive.
- `EarlyPressureStrategyTask` and `EconomicExpansionStrategyTask` write STRATEGY from tick 1 — tactics activates immediately when they are selected.

The POSTURE gate on `DroolsStrategyTask` is semantically correct: Drools reads POSTURE to make its decisions; running without it produces blind strategy. The activation latency is acceptable game design — there are no enemy forces to respond to in the first 1–2 seconds.

---

## Opponent classification

`DroolsScoutingTask` produces `ENEMY_POSTURE` via CEP rules. The trust router maps posture to a capability tag:

| ENEMY_POSTURE value | Capability tag |
|---|---|
| `"AGGRESSIVE"` | `starcraft.strategy.vs.aggressive` |
| `"ECONOMIC"` | `starcraft.strategy.vs.economic` |
| `"DEFENSIVE"` | `starcraft.strategy.vs.defensive` |
| `"UNKNOWN"` / absent | `starcraft.strategy.vs.unknown` |

New constants added to `QuarkMindCapabilityTag`:
```java
public static final String STRATEGY_VS_AGGRESSIVE = "starcraft.strategy.vs.aggressive";
public static final String STRATEGY_VS_ECONOMIC   = "starcraft.strategy.vs.economic";
public static final String STRATEGY_VS_DEFENSIVE  = "starcraft.strategy.vs.defensive";
public static final String STRATEGY_VS_UNKNOWN    = "starcraft.strategy.vs.unknown";
```

---

## EnemyPostureClassifiedEvent

New CDI event record in `agent/`:
```java
public record EnemyPostureClassifiedEvent(String posture) {}
```

`DroolsScoutingTask` fires this when posture transitions to a non-UNKNOWN value, **outside** the `postureDispatchEnabled` gate:

```java
// Inside execute(), posture change detection:
if (!posture.equals(prevPosture)) {
    prevPosture = posture;
    if (postureDispatchEnabled
            && (broker.isSubscribed(ScoutingIntelType.POSTURE) || advisoryEnabled)) {
        publishIntel(new ScoutingIntelPayload.PostureUpdate(posture)); // broker + advisory dispatch
    }
    if (!"UNKNOWN".equals(posture)) {
        postureClassified.fire(new EnemyPostureClassifiedEvent(posture)); // always: trust routing
    }
}
```

`postureDispatchEnabled` controls dispatch to broker/advisory consumers. Trust routing is an independent concern — the agent always benefits from opponent classification for strategy selection regardless of whether advisory dispatch is enabled. The two concerns must not share a gate. The only case where the checkpoint doesn't fire is if posture never leaves UNKNOWN — a legitimate game state, not a configuration issue.

---

## Same-tick pivot

`EnemyPostureClassifiedEvent.fire()` (synchronous `Event.fire()`) executes from inside `DroolsScoutingTask.execute()`, which runs inside the CaseEngine control loop. `StrategyTrustObserver.onPostureClassified()` runs inline, within that `execute()` call. When `DroolsScoutingTask.execute()` returns, `StrategySelector.selectedId` already reflects the pivoted strategy.

The CaseEngine re-evaluation loop that follows evaluates `canActivate()` for remaining tasks with the updated `StrategySelector`. **The strategy pivot is effective within the same tick's `createAndSolve()` re-evaluation pass** — not the next tick. Readers who assume CDI observer delivery is deferred will misread this behavior; document at the call site.

---

## StrategySelector

`@ApplicationScoped` CDI singleton holding per-game selection state:

```java
@ApplicationScoped
public class StrategySelector {
    private volatile String  selectedId      = "strategy.drools";
    private volatile String  opponentContext = QuarkMindCapabilityTag.STRATEGY_VS_UNKNOWN;
    private final AtomicBoolean checkpointFired = new AtomicBoolean(false);

    public void selectForGame(String strategyId, String context) {
        this.selectedId      = strategyId;
        this.opponentContext = context;
    }

    public boolean isSelected(String strategyId) { return selectedId.equals(strategyId); }
    public String  getSelectedId()               { return selectedId; }
    public String  getOpponentContext()           { return opponentContext; }

    public boolean isCheckpointFired() { return checkpointFired.get(); }
    public boolean claimCheckpoint()   { return checkpointFired.compareAndSet(false, true); }

    public void reset() {
        selectedId      = "strategy.drools";
        opponentContext = QuarkMindCapabilityTag.STRATEGY_VS_UNKNOWN;
        checkpointFired.set(false);
    }
}
```

`volatile` on `selectedId` and `opponentContext` is sufficient because writes only occur from sync CDI observers (effectively single-threaded at event time); reads in `canActivate()` on the CaseEngine thread see the latest write via Java volatile guarantee. `AtomicBoolean` for `checkpointFired` ensures the claim is race-free.

---

## StrategyTrustRouter

`@ApplicationScoped` CDI bean. Implements the four-phase trust maturity model. Injected by `StrategyTrustObserver`.

**Injection:** `TrustGateService` (capability scores via `MaterializedTrustScoreSource`) and the `decisionCount()` wrapper added to `TrustGateService` in `casehub-ledger`.

**Selection algorithm:**

```
DESIGNATED_FALLBACK = "strategy.drools"

for each candidateId in candidates:
    count = trustGateService.decisionCount(candidateId, opponentContext)
    score = trustGateService.currentScore(candidateId, opponentContext)  // OptionalDouble

    if count < minimumObservations OR score.isEmpty():
        phase = BOOTSTRAP; phaseScore = 0.5
        # 0.5 is strictly below the minimum QUALIFIED phaseScore of ~0.838
        # (threshold+margin+ε)*blendFactor + 1.0*(1-blendFactor) ≥ 0.838
        # so any QUALIFIED candidate correctly outranks any BOOTSTRAP candidate

    elif policy.isBorderline(score.getAsDouble()):
        phase = BORDERLINE; phaseScore = 0.0

    elif policy.passesThresholdCheck(score.getAsDouble()):
        phase = QUALIFIED; phaseScore = score * blendFactor + 1.0 * (1 - blendFactor)

    else:
        phase = EXCLUDED; phaseScore = 0.0

winner = candidate with highest phaseScore
if tie at equal phaseScore: winner = DESIGNATED_FALLBACK (explicit tiebreaker — not list order)
if all candidates BORDERLINE or EXCLUDED: winner = DESIGNATED_FALLBACK (exempt from exclusion)
return winner
```

**Tiebreaker:** When multiple candidates have equal `phaseScore` (including all-BOOTSTRAP at 0.5), the designated fallback `"strategy.drools"` wins explicitly. Do not rely on CDI iteration order — with CDI-discovered candidates, order is non-deterministic.

**BORDERLINE exemption for designated fallback:** `"strategy.drools"` is never excluded, even when BORDERLINE. Its role is to guarantee a strategy always runs.

Trust policy defaults (configurable via `application.properties`):

| Property | Default | Meaning |
|---|---|---|
| `quarkmind.trust.strategy.min-observations` | `10` | Games per (strategy, context) before trust gates selection |
| `quarkmind.trust.strategy.threshold` | `0.65` | Win-rate equivalent to qualify |
| `quarkmind.trust.strategy.borderline-margin` | `0.08` | Exclusion band around threshold |
| `quarkmind.trust.strategy.blend-factor` | `0.6` | Trust vs availability weight |

---

## StrategyTrustObserver

`@ApplicationScoped` CDI bean. `@Observes` (synchronous) for both events.

```java
@ApplicationScoped
public class StrategyTrustObserver {

    @Inject StrategySelector    strategySelector;
    @Inject StrategyTrustRouter trustRouter;
    @Inject @Any Instance<StrategyTask> strategyTasks; // CDI-discovered — auto-includes new strategies

    void onGameStarted(@Observes GameStarted event) {
        strategySelector.reset();
        List<String> candidateIds = strategyTasks.stream()
            .map(StrategyTask::getId)
            .toList();
        String winner = trustRouter.select(candidateIds, QuarkMindCapabilityTag.STRATEGY_VS_UNKNOWN);
        strategySelector.selectForGame(winner, QuarkMindCapabilityTag.STRATEGY_VS_UNKNOWN);
        log.infof("[TRUST] Game start — selected %s (bootstrap context)", winner);
    }

    void onPostureClassified(@Observes EnemyPostureClassifiedEvent event) {
        if (!strategySelector.claimCheckpoint()) return; // one pivot per game
        String context = mapPostureToContext(event.posture());
        List<String> candidateIds = strategyTasks.stream()
            .map(StrategyTask::getId)
            .toList();
        String winner = trustRouter.select(candidateIds, context);
        strategySelector.selectForGame(winner, context);
        log.infof("[TRUST] Checkpoint — opponent=%s, selected %s (effective this tick)", event.posture(), winner);
    }
}
```

`DESIGNATED_FALLBACK` is not in `CANDIDATE_IDS` — it is a hardcoded constant in `StrategyTrustRouter` representing a deliberate architectural choice, not a discoverable property.

---

## Dual-strategy invariant

**Invariant:** Exactly one `StrategyTask` implementation fires per tick.

**Enforcement:** `StrategySelector.isSelected(getId())` in each implementation's `canActivate()`. No framework-level guard exists. A `StrategySelector` bug could silently allow two strategies to write `STRATEGY` in the same tick — last-writer wins.

**Why sufficient:** `selectedId` writes occur only from `StrategyTrustObserver` (synchronous CDI event context). `canActivate()` reads on the CaseEngine thread see the latest write via `volatile`. The invariant holds under normal operation.

**Verification:** `TrustWeightedStrategyIT` asserts both that the selected strategy ran AND that each non-selected strategy did not (`canActivate()` returned false — tested for each).

---

## canActivate() changes

```java
// EarlyPressureStrategyTask and EconomicExpansionStrategyTask:
@Override
public boolean canActivate(CaseFile caseFile) {
    return strategySelector.isSelected(getId())
        && entryCriteria().stream().allMatch(caseFile::contains);
}

// DroolsStrategyTask — retains existing POSTURE broker gate:
@Override
public boolean canActivate(CaseFile caseFile) {
    return strategySelector.isSelected(getId())
        && entryCriteria().stream().allMatch(caseFile::contains)
        && broker.current(ScoutingIntelType.POSTURE).isPresent();
}
```

---

## QuarkMindTaskRegistrar changes

```java
// Before (L5):
@Inject @CaseType("starcraft-game") StrategyTask strategyTask;

// After (L6):
@Inject @Any Instance<StrategyTask> strategyTasks;

// onStart():
strategyTasks.forEach(t -> registry.register((TaskDefinition) t, Set.of(CASE_TYPE)));
```

---

## LedgerLifecycleAdapter — removal

`LedgerLifecycleAdapter` **must be removed** from the production codebase.

**Why it breaks L6:** `LedgerLifecycleAdapter.onGameStop(@Observes GameStopped)` clears `InMemoryLedgerEntryRepository` synchronously during `Event.fire()`. `GameOutcomeRecorder.onGameStopped()` (now `@Observes`, sync) writes the outcome record after the clear fires. Subsequent `IncrementalTrustUpdateObserver.onAttestationRecorded()` reads from the ledger — sees at most 1 entry (the one just written). `PerActorTrustComputer.computeForActor()` calls `ActorTrustScoreRepository.upsert(decisionCount=1)`, replacing any prior count. `MaterializedTrustScoreSource.decisionCount()` reads back 1. `decisionCount` never exceeds 1; the `minimumObservations=10` threshold is permanently unreachable.

**Why removal is safe:** `LedgerLifecycleAdapter` was designed before L6 when ledger data was ephemeral per-tick attribution. L6 requires cross-game accumulation; the two requirements are incompatible. Test isolation for `@QuarkusTest` is achieved by:
- `gameSession.reset()` in `@BeforeEach` (existing pattern in `LedgerAuditIT`)
- Scoping queries by `gameSession.id()` (tests that use `findBySubjectId` are already isolated by session)
- `InMemoryLedgerEntryRepository.clear()` in `@BeforeEach` for tests that seed trust scores and need a clean ledger (`TrustWeightedStrategyIT`)

---

## Game outcome recording

`GameOutcomeRecorder` observes `GameStopped` **synchronously** (`@Observes`):

```java
void onGameStopped(@Observes GameStopped event) {
    outcomeRecorder.record(OutcomeRecord.of(
        strategySelector.getSelectedId(),
        gameSession.id(),
        strategySelector.getOpponentContext(),
        AttestationVerdict.SOUND,   // proxy — real win/loss deferred (see §Limitation)
        1.0                         // session confidence: complete game = session in SC2
    ));
}
```

`@Observes` (not `@ObservesAsync`) is required: must capture `StrategySelector` state before `StrategySelector.reset()` can fire on the next `startGame()`. Identical rationale to `GameSession` never resetting in `stopGame()`.

**Confidence 1.0:** Per `OutcomeRecord` Javadoc: "Recommended: 0.1 (tick), 0.7 (game/in-game event), 1.0 (session)." A completed SC2 game is a session-level outcome for the strategic commitment made at game start. `PluginOutcomeAuditor` uses 0.7 for per-tick CEP state-change events (significant in-game decisions, not session-level outcomes).

---

## Trust accumulation configuration requirements

`IncrementalTrustUpdateObserver` is a no-op unless three flags are true. Add to `application.properties` (all profiles except `%sc2` where JPA + TrustScoreJob are authoritative):

```properties
casehub.ledger.trust-score.enabled=true
casehub.ledger.trust-score.incremental.enabled=true
casehub.ledger.trust-score.materialization.enabled=true
```

Without these, `ActorTrustScoreRepository` is never written, `MaterializedTrustScoreSource.currentScore()` always returns `OptionalDouble.empty()`, all strategies are permanently BOOTSTRAP, and `"strategy.drools"` is selected every game by fallback. L6 infrastructure is present but trust routing produces no learning.

**Transactional dependency — do not bypass:** `IncrementalTrustUpdateObserver.onAttestationRecorded()` fires at `TransactionPhase.AFTER_SUCCESS` of `OutcomeRecordSaveService.save()`. Trust scores update only when that transaction commits successfully. The `@Transactional` boundary on `OutcomeRecordSaveService.save()` is the linchpin — `AttestationRecordedEvent` is fired inside the transaction by `ledgerRepo.saveAttestation()`, and CDI delivers `AFTER_SUCCESS` observers synchronously during commit. Do not call `outcomeRecorder.record()` from inside a rolled-back transaction: if the outer transaction rolls back, `AFTER_SUCCESS` never fires and trust score updates are silently dropped for that game.

---

## casehub-ledger changes required

`TrustGateService` needs a `decisionCount()` delegation:
```java
public int decisionCount(String actorId, String capabilityTag) {
    return source.decisionCount(actorId, capabilityTag);
}
```

Non-breaking addition. Must be installed to local Maven before building QuarkMind with L6.

---

## New files

| File | Package | Purpose |
|---|---|---|
| `EnemyPostureClassifiedEvent.java` | `agent/` | CDI event record — fired by DroolsScoutingTask on first confident posture |
| `StrategySelector.java` | `agent/` | Per-game selection state |
| `StrategyTrustRouter.java` | `agent/` | Trust score lookup + four-phase selection algorithm |
| `StrategyTrustObserver.java` | `agent/` | CDI event observer — triggers selection at game start and checkpoint |
| `GameOutcomeRecorder.java` | `agent/` | Observes GameStopped (sync); writes outcome attestation to ledger |
| `EarlyPressureStrategyTask.java` | `plugin/` | `@CaseType("starcraft-game")` — early military commitment |
| `EconomicExpansionStrategyTask.java` | `plugin/` | `@CaseType("starcraft-game")` — economic expansion commitment |

**New test file:**
- `TrustTestUtils.java` — `src/test/` — seeding helper for `ActorTrustScoreRepository.upsert()` with consistent alpha/beta values

**Modified files:**
- `DroolsStrategyTask.java` — updated `canActivate()` to add `StrategySelector` check
- `DroolsScoutingTask.java` — inject `Event<EnemyPostureClassifiedEvent>`; fire outside `postureDispatchEnabled` gate when posture becomes non-UNKNOWN
- `QuarkMindTaskRegistrar.java` — `@Any Instance<StrategyTask>` for three implementations
- `QuarkMindCapabilityTag.java` — four new `STRATEGY_VS_*` constants
- `application.properties` — three trust-score accumulation flags set to `true`

**Removed files:**
- `LedgerLifecycleAdapter.java` — breaks cross-game trust accumulation; test isolation moved to `@BeforeEach`

**casehub-ledger (separate repo):**
- `TrustGateService.java` — add `decisionCount(String, String)` delegation

---

## Tests

**Unit tests:**

| Test | Verifies |
|---|---|
| `StrategyTrustRouterTest` | Bootstrap returns `"strategy.drools"`; QUALIFIED candidate above threshold selected; BORDERLINE excluded; BORDERLINE fallback still selected; all-tie phaseScore selects `"strategy.drools"` explicitly (not list order) |
| `StrategySelectorTest` | `claimCheckpoint()` is idempotent; `reset()` clears all state |
| `EarlyPressureStrategyTaskTest` | `canActivate()` false when not selected; true when selected + entry criteria met; `STRATEGY="ATTACK"` after execute |
| `EconomicExpansionStrategyTaskTest` | Same pattern; `STRATEGY="EXPAND"` |

**Integration tests (`@QuarkusTest`):**

| Test | Verifies |
|---|---|
| `TrustWeightedStrategyIT` | `@BeforeEach`: `InMemoryLedgerEntryRepository.clear()` + seed trust score via `TrustTestUtils.seedQualified(trustScoreRepo, "strategy.early-pressure", STRATEGY_VS_AGGRESSIVE)`; fire `GameStarted`; assert `StrategySelector.getSelectedId() = "strategy.early-pressure"`; run one tick; assert early-pressure ran (STRATEGY written) AND `DroolsStrategyTask.canActivate()` returned false AND `EconomicExpansionStrategyTask.canActivate()` returned false |
| `StrategyCheckpointIT` | `@BeforeEach`: seed as above; fire `GameStarted` (→ `"strategy.drools"` selected, vs.unknown context); fire `EnemyPostureClassifiedEvent("AGGRESSIVE")`; assert pivot to `"strategy.early-pressure"`; fire second `EnemyPostureClassifiedEvent`; assert no second pivot (`claimCheckpoint()` returns false) |
| `StrategyOutcomeRecordIT` | Fire `GameStopped` (sync); assert `OutcomeRecord` written with correct `actorId`, `capabilityTag`, `confidence=1.0`; **additionally assert** `trustGateService.decisionCount(strategySelector.getSelectedId(), STRATEGY_VS_UNKNOWN) == 1` — verifies the full `OutcomeRecordSaveService → AttestationRecordedEvent → IncrementalTrustUpdateObserver → ActorTrustScoreRepository.upsert()` pipeline is operational, not just that the ledger entry was written |

**TrustTestUtils — test seeding helper**

`ActorTrustScoreRepository.upsert()` takes 13 parameters; the alpha/beta values must be internally consistent with the seeded `trustScore` to prevent `PerActorTrustComputer` from recomputing inconsistent values if incremental update fires during the test. Add `TrustTestUtils` in `src/test/java/io/quarkmind/agent/`:

```java
public final class TrustTestUtils {
    private TrustTestUtils() {}

    /**
     * Seeds a QUALIFIED trust score (trustScore=0.82, decisionCount=12) for the given
     * (actorId, capabilityTag) pair. Uses Beta(11, 3) prior consistent with 0.786 ≈ 0.82
     * after rounding. Score is above the default threshold+margin (0.65+0.08=0.73).
     */
    public static void seedQualified(ActorTrustScoreRepository repo,
                                     String actorId, String capabilityTag) {
        int pos = 10, neg = 2;                // attestationPositive/Negative
        double alpha = pos + 1.0, beta = neg + 1.0; // Beta(1,1) prior → alpha/beta
        repo.upsert(
            actorId, ScoreType.CAPABILITY, capabilityTag, null,
            ActorType.AGENT,
            0.82,           // trustScore — read directly by MaterializedTrustScoreSource
            12,             // decisionCount — above minimumObservations(10)
            0,              // overturnedCount
            alpha, beta,    // consistent with pos/(pos+neg) ≈ 0.833 ≈ 0.82
            pos, neg,       // attestationPositive, attestationNegative
            Instant.now()
        );
    }
}
```

`MaterializedTrustScoreSource` reads `trustScore` and `decisionCount` directly from `ActorTrustScore` fields — it does not recompute from alpha/beta — so a small alpha/beta discrepancy from rounding does not affect routing decisions. The helper is sufficient for any trust policy with `threshold ≤ 0.73`.

---

## Open issues to file before closing #158

1. **Win/loss outcome detection from real SC2** — reads `playerResult` from SC2 API at game end; without it L6 produces no actual routing learning. Estimate: S, Med.
2. **casehub-ledger: add `TrustGateService.decisionCount()`** — one-line delegation; prerequisite for L6 build.

---

## What L6 demonstrates for the harness pattern

L6 closes the accountability gap on strategic commitment: prior layers established what to do each tick; L6 establishes which game plan to pursue and improves that choice over games. The pattern generalises:

> Replace `StrategyTask` with any domain decision seam with competing implementations.
> Replace `ENEMY_POSTURE` / `EnemyPostureClassifiedEvent` with any contextual classifier event.
> Trust routing learns which expert performs best in each context — automatically, without re-configuration.

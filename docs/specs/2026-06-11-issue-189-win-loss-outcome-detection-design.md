# Design: Win/Loss Outcome Detection from Real SC2 (#189)

**Branch:** `issue-189-winloss-sc2-outcome`  
**Issue:** [#189](https://github.com/casehubio/quarkmind/issues/189)  
**Deferred:** Milestone-based trust scoring within a game → [#191](https://github.com/casehubio/quarkmind/issues/191)

---

## Problem

L6 trust-weighted strategy routing (`StrategyTrustRouter`) learns from game outcomes via
`OutcomeRecorder`. Currently `GameOutcomeRecorder` always records `AttestationVerdict.SOUND`
because `GameStopped` carries no outcome data. Trust routing accumulates observations but
every verdict is neutral — the routing model never learns a directional signal from win or loss.

Two compounding gaps:

1. `GameStopped` is no-arg — no outcome data flows through the event.
2. For a **natural game end** (SC2 signals `status=ended`), `GameStopped` is never fired at
   all — only `AgentOrchestrator.stopGame()` fires it, and that is only called manually.

The SC2 API delivers `playerResults: List<PlayerResult>` on the final observation response
when status transitions to `ended`. This data is currently discarded.

---

## Design

### 1. New types

**`GameResult` enum** — placed in `sc2/` (the SC2Engine seam, not `sc2/real/`):

```java
public enum GameResult { WIN, LOSS, TIE, UNKNOWN }
```

**`GameStopped` record** — enriched from no-arg to carry the result:

```java
public record GameStopped(GameResult result) {}
```

All existing callers updated to pass an explicit result. Specifically:
- `AgentOrchestrator.stopGame()` → `new GameStopped(GameResult.UNKNOWN)`
- `StrategyOutcomeRecordIT` fire calls (lines 56, 76, 89, 93) → `new GameStopped(GameResult.WIN)`
  — test intent (accumulation, correct strategy/context recording) survives unchanged;
  `ENDORSED` verdict is written instead of `SOUND`, which is correct behaviour

---

### 2. Player ID detection (`QuarkusSC2Transport`)

`QuarkusSC2Transport.gameLoop()` already sends `RequestGameInfo` and receives
`ResponseGameInfo` before calling `callback.onGameStart()`. The transport parses
`localPlayerId` from that response directly — no new method on `SC2FrameCallback` needed.

```java
ResponseGameInfo gameInfo = ResponseGameInfo.from(infoResp);
int localPlayerId = extractLocalPlayerId(gameInfo);
callback.onGameStart(gameInfo);
```

`extractLocalPlayerId()` is a package-private static method on `QuarkusSC2Transport`:

```java
static int extractLocalPlayerId(ResponseGameInfo gameInfo) {
    return gameInfo.getPlayersInfo().stream()          // Set<PlayerInfo> — unordered
        .filter(pi -> pi.getPlayerType()
            .map(t -> t == PlayerType.PARTICIPANT)
            .orElse(false))
        .mapToInt(PlayerInfo::getPlayerId)
        .findFirst()
        .orElse(1);                                    // fallback: player 1 (shouldn't occur)
}
```

Note: `ResponseGameInfo.getPlayersInfo()` returns `Set<PlayerInfo>` (unordered, backed by
`collectingAndThen(toSet(), unmodifiableSet)`); `PlayerInfo.getPlayerType()` returns
`Optional<PlayerType>`. The bot is the `PARTICIPANT` entry; the AI is `COMPUTER`.

`SC2BotAgent` is not involved in player ID detection. Its `onGameStart()` remains
responsible for terrain extraction only.

`SC2BotAgent` gains only:

```java
private final AtomicReference<GameResult> lastOutcome = new AtomicReference<>(GameResult.UNKNOWN);
```

Reset to `UNKNOWN` in `onGameStart()` (see §4).

---

### 3. Outcome extraction (`QuarkusSC2Transport`)

The game loop breaks when `obsResp.getStatus() != Sc2Api.Status.in_game`. That final
`obsResp` currently goes unused. The extraction happens inline before the break, while
`obsResp` is in scope:

```java
GameResult gameResult = GameResult.UNKNOWN;

while (!quitting.get() && !Thread.currentThread().isInterrupted()) {
    Sc2Api.Response obsResp = sendSync(...);
    if (obsResp.getStatus() != Sc2Api.Status.in_game) {
        gameResult = extractResult(obsResp, localPlayerId);   // inline before break
        break;
    }
    // ... onStep, sendStep ...
}
```

`extractResult()` is a package-private static method:

```java
static GameResult extractResult(Sc2Api.Response obsResp, int localPlayerId) {
    try {
        ResponseObservation ro = ResponseObservation.from(obsResp);
        return ro.getPlayerResults().stream()
            .filter(pr -> pr.getPlayerId() == localPlayerId)
            .map(pr -> switch (pr.getResult()) {
                case VICTORY   -> GameResult.WIN;
                case DEFEAT    -> GameResult.LOSS;
                case TIE       -> GameResult.TIE;
                case UNDECIDED -> GameResult.UNKNOWN;
            })
            .findFirst()
            .orElse(GameResult.UNKNOWN);
    } catch (Exception e) {
        log.warnf("[SC2] Could not parse player result from final observation: %s", e.getMessage());
        return GameResult.UNKNOWN;
    }
}
```

`PlayerResult.getResult()` returns `com.github.ocraft.s2client.protocol.observation.Result`
directly (required field, never null). The switch is exhaustive over all four enum values.
The try-catch guards against the edge case where the final frame cannot be parsed
(e.g., `sc2ApiResponse.hasObservation()` is false). For the interrupt/exception exit path
(manual quit or crash), `gameResult` stays `UNKNOWN`.

**`SC2FrameCallback.onGameEnd()`** signature changes to `onGameEnd(GameResult result)`.

**Critical ordering fix** — the transport `finally` block currently sets `running = false`
before calling `callback.onGameEnd()`. This order must be reversed:

```java
} finally {
    if (gameStarted) callback.onGameEnd(gameResult); // 1. store lastOutcome FIRST
    running = false;                                  // 2. signal disconnection AFTER
    if (quitting.get()) { /* sendSync(RequestQuit) */ }
    closeSocket();
    gameLoopThread = null;
}
```

Because `SC2BotAgent.onGameEnd()` only performs an `AtomicReference.set()` (no CDI events,
no I/O), this is fast. The happens-before guarantee: the `AtomicReference` write in step 1
is visible before the `volatile` write of `running = false` in step 2, which is visible
before any scheduler thread reads `running` in `gameTick()`. This ensures `lastOutcome()`
is populated before `gameTick()` can detect the disconnection and read it.

---

### 4. SC2BotAgent — onGameStart + onGameEnd

```java
@Override
public void onGameStart(ResponseGameInfo gameInfo) {
    lastOutcome.set(GameResult.UNKNOWN);   // reset before new game
    gameInfo.getStartRaw().ifPresent(startRaw -> {
        // ... existing terrain extraction ...
    });
    log.info("[SC2] Game started");
}

@Override
public void onGameEnd(GameResult result) {
    lastOutcome.set(result);
    log.infof("[SC2] Game ended — result=%s", result);
}

public GameResult getLastOutcome() { return lastOutcome.get(); }
```

No CDI event firing here. `AgentOrchestrator` owns `GameStopped` emission.

---

### 5. SC2Engine seam

`SC2Engine` interface gains a default method:

```java
default GameResult lastOutcome() { return GameResult.UNKNOWN; }
```

`RealSC2Engine` overrides it:

```java
@Override
public GameResult lastOutcome() { return botAgent.getLastOutcome(); }
```

Mock, emulated, and replay engines inherit the default and return `UNKNOWN`. No changes
required in any non-sc2 engine.

---

### 6. AgentOrchestrator: game-end detection + double-fire guard

Two new fields:

```java
private final AtomicBoolean gameActive      = new AtomicBoolean(false);
private volatile boolean    engineWasConnected = false;
```

**Threading model:**
- `gameActive` — armed in `startGame()` (game lifecycle begins); CAS'd to false exactly once
  per game by `fireGameStoppedOnce()`. Written by both scheduler thread (`gameTick()` does
  NOT write it) and calling thread (`stopGame()`). AtomicBoolean required.
- `engineWasConnected` — written and read exclusively by the scheduler thread (Quarkus SKIP
  concurrent execution guarantees single-active `gameTick()`), except that `stopGame()` also
  writes it to block the natural-end detection path. volatile provides cross-thread visibility.

Private helper that fires exactly once per game:

```java
private void fireGameStoppedOnce(GameResult result) {
    if (gameActive.compareAndSet(true, false)) {
        gameStoppedEvent.fire(new GameStopped(result));
    }
}
```

**`startGame()`** — arms `gameActive` and resets `engineWasConnected`:

```java
public void startGame() {
    gameActive.set(true);       // game lifecycle begins
    engineWasConnected = false; // ensure clean state
    gameSession.reset();
    engine.connect();
    engine.joinGame();
    gameStartedEvent.fire(new GameStarted());
    log.info("Game started");
}
```

Note: `gameActive = true` is set here (not in `gameTick()`) because the game lifecycle
begins when `startGame()` is called. The connect-window race (where `gameTick()` could
detect `!isConnected()` during the up-to-90s connect/createGame/joinGame phase and fire
`GameStopped(UNKNOWN)`) is prevented by `engineWasConnected = false`, which blocks the
`else if` branch in `gameTick()` until the engine is actually observed as connected.

**`gameTick()`** — tracks the connected→disconnected transition; does NOT write `gameActive`:

```java
@Scheduled(every = "${starcraft.tick.interval:500ms}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
public void gameTick() {
    if (schedulerPaused) return;
    if (engine.isConnected()) {
        engineWasConnected = true;
        lastTickResult.set(tickExecutor.execute());
    } else if (engineWasConnected) {
        engineWasConnected = false;
        fireGameStoppedOnce(engine.lastOutcome());       // natural game end
    }
}
```

`gameActive` is NOT written in `gameTick()`. If it were, a concurrent `stopGame()` that
CAS'd `gameActive` to false could be undone by a `gameTick()` tick that still observes
`isConnected() = true` (valid during the window between `transport.quit()` being called and
`running = false` propagating). That would re-arm the guard and allow a second `GameStopped`
to fire. By keeping `gameActive` writes strictly in `startGame()` and `fireGameStoppedOnce()`,
the CAS guard is never re-armed mid-game.

**`stopGame()`**:

```java
public void stopGame() {
    engineWasConnected = false; // block natural-end path — prevents double-fire if gameTick()
                                // observes !isConnected() after this call
    engine.leaveGame();
    fireGameStoppedOnce(GameResult.UNKNOWN);
    log.info("Game stopped");
}
```

`engineWasConnected = false` is written before `engine.leaveGame()` to close the natural-end
detection window in `gameTick()`. If `gameTick()` reads `engineWasConnected = true` before
this write (race), its subsequent `fireGameStoppedOnce()` CAS will fail because `stopGame()`'s
CAS wins first (or vice versa). The volatile write and AtomicBoolean CAS together guarantee
exactly one `GameStopped` fires regardless of ordering.

Manual stop always passes `UNKNOWN` — the game loop is being interrupted, no resolved
outcome is available.

**Continuous loop (training mode):** after `fireGameStoppedOnce()`, `gameActive = false`
and `engineWasConnected = false`. The next `startGame()` call sets `gameActive = true` and
resets `engineWasConnected = false`. Clean state for the next game.

**Edge case — stopGame() before first connected tick:** `engineWasConnected = false`,
`gameActive = true` (set in `startGame()`). `stopGame()` fires `GameStopped(UNKNOWN)` via
CAS. `GameOutcomeRecorder` receives `UNKNOWN` and skips ledger write (§7). This is correct:
if no game tick ran, no trust signal should be recorded.

---

### 7. GameOutcomeRecorder — verdict mapping

```java
void onGameStopped(@Observes GameStopped event) {
    if (event.result() == GameResult.UNKNOWN) {
        log.infof("[OUTCOME] Game ended with unknown result — skipped (strategy=%s context=%s)",
            strategySelector.getSelectedId(), strategySelector.getOpponentContext());
        return;
    }
    AttestationVerdict verdict = switch (event.result()) {
        case WIN     -> AttestationVerdict.ENDORSED;
        case LOSS    -> AttestationVerdict.CHALLENGED;
        case TIE     -> AttestationVerdict.SOUND;
        case UNKNOWN -> throw new AssertionError(); // unreachable — guarded above
    };
    outcomeRecorder.record(OutcomeRecord.of(
        strategySelector.getSelectedId(),
        gameSession.id(),
        strategySelector.getOpponentContext(),
        verdict,
        1.0
    ));
    log.infof("[OUTCOME] Recorded: strategy=%s context=%s result=%s verdict=%s",
        strategySelector.getSelectedId(), strategySelector.getOpponentContext(),
        event.result(), verdict);
}
```

| Game result | Verdict      | Trust effect          |
|-------------|-------------|-----------------------|
| `WIN`       | `ENDORSED`   | Increases trust score |
| `LOSS`      | `CHALLENGED` | Decreases trust score |
| `TIE`       | `SOUND`      | Neutral               |
| `UNKNOWN`   | —            | Skipped, logged only  |

---

### 8. EconomicsLifecycle — impact analysis

`EconomicsLifecycle.onGameStop(@Observes GameStopped event)` observes the event as a
lifecycle signal to manage economics workflow state. It never calls `event.result()`. The
enriched record compiles and behaves identically — no change needed.

---

### 9. Observer synchrony

All `GameStopped` observers use `@Observes` (synchronous), per the existing protocol
`game-lifecycle-observer-synchrony.md`. `fireGameStoppedOnce()` fires synchronously from
whichever thread triggers it (scheduler thread for natural end via `gameTick()`, calling
thread for manual stop via `stopGame()`). Observers run to completion before the fire
returns.

---

## Files changed

| File | Change |
|------|--------|
| `sc2/GameResult.java` | New — enum WIN/LOSS/TIE/UNKNOWN |
| `sc2/GameStopped.java` | Add `GameResult result` component |
| `sc2/SC2Engine.java` | Add `default GameResult lastOutcome()` |
| `sc2/real/SC2FrameCallback.java` | `onGameEnd()` → `onGameEnd(GameResult)` |
| `sc2/real/SC2BotAgent.java` | Reset `lastOutcome` in `onGameStart()`; implement `onGameEnd(GameResult)` |
| `sc2/real/QuarkusSC2Transport.java` | `extractLocalPlayerId()`; `extractResult()`; inline `gameResult` extraction before break; reverse `onGameEnd`/`running=false` order |
| `sc2/real/RealSC2Engine.java` | Override `lastOutcome()` |
| `agent/AgentOrchestrator.java` | Add `gameActive` + `engineWasConnected`; `fireGameStoppedOnce()`; `startGame()` arms `gameActive` + resets `engineWasConnected`; `stopGame()` resets `engineWasConnected` + fires once; transition-tracking `gameTick()` (no `gameActive` write) |
| `agent/GameOutcomeRecorder.java` | Map `GameResult` to `AttestationVerdict`; skip UNKNOWN |
| `plugin/flow/EconomicsLifecycle.java` | No change — observes signal only, never accesses `result()` |
| `sc2/mock/StrategyOutcomeRecordIT.java` | Update four `new GameStopped()` → `new GameStopped(GameResult.WIN)`; add WIN/LOSS/UNKNOWN assertion cases |

---

## Tests

| Test | Type | What it verifies |
|------|------|-----------------|
| `QuarkusSC2TransportTest.extractLocalPlayerId` (extend) | Unit — package-private static | Mocked `ResponseGameInfo` with PARTICIPANT (id=2) + COMPUTER (id=1) entries → returns 2; PARTICIPANT-only → returns that id; no PARTICIPANT → returns fallback 1 |
| `QuarkusSC2TransportTest.naturalGameEnd_parsesPlayerResult` (extend) | Unit — `FakeSC2Server` | Set `observationsBeforeEnd=2` and `playerResultForGameEnd=Victory`; assert `callback.onGameEnd(WIN)` called |
| `RealSC2EngineTest` (extend) | Unit | `lastOutcome()` returns `UNKNOWN` before game; returns `WIN` after `botAgent.onGameEnd(WIN)` called directly |
| `StrategyOutcomeRecordIT` (extend) | `@QuarkusTest` | `fire(WIN)` → `ENDORSED` in ledger + `decisionCount=1`; `fire(LOSS)` → `CHALLENGED`; `fire(UNKNOWN)` → no ledger write, `decisionCount` unchanged |

**`FakeSC2Server` extension:** add `Sc2Api.Result playerResultForGameEnd = null` field.
When `status = ended`, append `addPlayerResult(Sc2Api.PlayerResult.newBuilder().setPlayerId(1).setResult(playerResultForGameEnd))` to the `ResponseObservation` if non-null. Uses raw `Sc2Api.Result` (protobuf enum) since `buildResponse()` works at the protobuf layer.

---

## Out of scope

- Milestone-based trust scoring within a game → [#191](https://github.com/casehubio/quarkmind/issues/191)
- INVALID verdict for games that should not count statistically → related to #191

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

All existing callers updated to pass an explicit result.

---

### 2. Player ID detection (`QuarkusSC2Transport`)

`QuarkusSC2Transport.gameLoop()` already sends `RequestGameInfo` and receives
`ResponseGameInfo` before calling `callback.onGameStart()`. The transport parses
`localPlayerId` from that response directly — no new method on `SC2FrameCallback` needed.

```java
ResponseGameInfo gameInfo = ResponseGameInfo.from(infoResp);
int localPlayerId = extractLocalPlayerId(gameInfo); // find PARTICIPANT type entry
callback.onGameStart(gameInfo);
```

`extractLocalPlayerId()` scans `gameInfo.getPlayersInfo()` for the entry with type
`PARTICIPANT` (bot) as opposed to `COMPUTER` (AI opponent), and returns its `getPlayerId()`.
If no `PARTICIPANT` entry is found (shouldn't happen in practice), defaults to `1`.

`SC2BotAgent` is not involved in player ID detection. Its `onGameStart()` remains
responsible for terrain extraction only.

`SC2BotAgent` gains only:

```java
private final AtomicReference<GameResult> lastOutcome = new AtomicReference<>(GameResult.UNKNOWN);
```

Reset to `UNKNOWN` at game start.

---

### 3. Outcome extraction (`QuarkusSC2Transport`)

The game loop breaks when `obsResp.getStatus() != Sc2Api.Status.in_game`. That final
`obsResp` currently goes unused. Instead:

```java
if (obsResp.getStatus() != Sc2Api.Status.in_game) {
    gameResult = extractResult(obsResp, callback);
    break;
}
```

`extractResult()` parses the final `obsResp`. It wraps `ResponseObservation.from(obsResp)`
in a try-catch — if the final frame cannot be parsed (edge case: observation field absent),
it logs a warning and returns `UNKNOWN`. On success it scans `getPlayerResults()` for the
entry matching `localPlayerId` and maps:

| SC2 Result  | GameResult |
|-------------|-----------|
| `VICTORY`   | `WIN`      |
| `DEFEAT`    | `LOSS`     |
| `TIE`       | `TIE`      |
| `UNDECIDED` | `UNKNOWN`  |
| not found   | `UNKNOWN`  |

For the interrupt/exception exit path (manual quit or crash), `gameResult` stays `UNKNOWN`.

**`SC2FrameCallback.onGameEnd()`** signature changes to `onGameEnd(GameResult result)`.

**Critical ordering fix** — the transport `finally` block currently sets `running = false`
before calling `callback.onGameEnd()`. This order must be reversed:

```java
} finally {
    if (gameStarted) callback.onGameEnd(gameResult); // populate lastOutcome FIRST
    running = false;                                   // signal disconnection AFTER
    ...
}
```

This ensures `lastOutcome()` is populated before `gameTick()` can detect the disconnection
and read it. Without this fix, `gameTick()` could race and record `UNKNOWN` instead of the
actual result.

---

### 4. SC2BotAgent — onGameEnd

```java
@Override
public void onGameEnd(GameResult result) {
    lastOutcome.set(result);
    log.infof("[SC2] Game ended — result=%s", result);
}

public GameResult getLastOutcome() { return lastOutcome.get(); }
```

No CDI event firing here. `AgentOrchestrator` owns that.

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

### 6. AgentOrchestrator — game-end detection + double-fire guard

New field:

```java
private final AtomicBoolean gameActive = new AtomicBoolean(false);
```

Private helper (fires exactly once per game):

```java
private void fireGameStoppedOnce(GameResult result) {
    if (gameActive.compareAndSet(true, false)) {
        gameStoppedEvent.fire(new GameStopped(result));
    }
}
```

**`startGame()`** — sets `gameActive.set(true)` before `engine.connect()`, consistent with
the existing `gameSession.reset()` ordering.

**`stopGame()`** — replaces `gameStoppedEvent.fire(new GameStopped())` with:

```java
engine.leaveGame();
fireGameStoppedOnce(GameResult.UNKNOWN);
```

Manual stop always records `UNKNOWN` — the game loop is being interrupted, no resolved
outcome is available.

**`gameTick()`** — gains natural game-end detection:

```java
if (!engine.isConnected()) {
    fireGameStoppedOnce(engine.lastOutcome());
    return;
}
```

Because `callback.onGameEnd(result)` now fires before `running = false`, by the time
`gameTick()` observes `!isConnected()`, `engine.lastOutcome()` already holds the correct
result. The `compareAndSet` guard prevents double-fire if `stopGame()` already fired
(manual stop scenario).

**Continuous loop (training mode):** after `fireGameStoppedOnce()`, `gameActive` is `false`.
The next `startGame()` call sets it back to `true`. No state leaks between games.

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
        case WIN  -> AttestationVerdict.ENDORSED;
        case LOSS -> AttestationVerdict.CHALLENGED;
        case TIE  -> AttestationVerdict.SOUND;
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

### 8. Observer synchrony

All `GameStopped` observers use `@Observes` (synchronous), per the existing protocol
`game-lifecycle-observer-synchrony.md`. This is unchanged. `fireGameStoppedOnce()` fires
synchronously from whichever thread triggers it (scheduler thread for natural end via
`gameTick()`, calling thread for manual stop via `stopGame()`). Observers run to completion
before the fire call returns.

---

## Files changed

| File | Change |
|------|--------|
| `sc2/GameResult.java` | New — enum WIN/LOSS/TIE/UNKNOWN |
| `sc2/GameStopped.java` | Add `GameResult result` component |
| `sc2/SC2Engine.java` | Add `default GameResult lastOutcome()` |
| `sc2/real/SC2FrameCallback.java` | `onGameEnd()` → `onGameEnd(GameResult)` |
| `sc2/real/SC2BotAgent.java` | Store `lastOutcome`; implement `onGameEnd(GameResult)` |
| `sc2/real/QuarkusSC2Transport.java` | Parse `localPlayerId` from `ResponseGameInfo`; extract result from final obsResp; reverse `onGameEnd`/`running=false` order |
| `sc2/real/RealSC2Engine.java` | Override `lastOutcome()` |
| `agent/AgentOrchestrator.java` | Add `gameActive`; `fireGameStoppedOnce()`; natural-end detection in `gameTick()` |
| `agent/GameOutcomeRecorder.java` | Map `GameResult` to `AttestationVerdict`; skip UNKNOWN |

---

## Tests

| Test | Type | What it verifies |
|------|------|-----------------|
| `SC2BotAgentPlayerIdTest` | Unit (plain JUnit) | `onGameStart()` with PARTICIPANT + COMPUTER entries → correct `localPlayerId` stored |
| `RealSC2EngineTest` (extend) | Unit | `lastOutcome()` returns `UNKNOWN` before game; returns stored value after `onGameEnd()` |
| `QuarkusSC2TransportTest` (extend) | Unit | Mock socket responds with `status=ended` + `playerResult(WIN)` → `onGameEnd(WIN)` called on callback |
| `StrategyOutcomeRecordIT` (extend) | `@QuarkusTest` | `fire(WIN)` → `ENDORSED` in ledger; `fire(LOSS)` → `CHALLENGED`; `fire(UNKNOWN)` → no ledger write |

---

## Out of scope

- Milestone-based trust scoring within a game → [#191](https://github.com/casehubio/quarkmind/issues/191)
- INVALID verdict for games that should not count statistically → related to #191

---
id: PP-20260610-88dbbd
title: "Game lifecycle observers that read volatile selector state must use @Observes (synchronous)"
type: rule
scope: repo
applies_to: "CDI observers on GameStarted and GameStopped that read from StrategySelector or GameSession"
severity: important
refs:
  - src/main/java/io/quarkmind/agent/GameOutcomeRecorder.java
  - src/main/java/io/quarkmind/agent/StrategyTrustObserver.java
  - src/main/java/io/quarkmind/agent/GameSession.java
violation_hint: "@ObservesAsync on GameStopped → body may run after StrategySelector.reset() on next GameStarted → records wrong strategy/context, or reads reset UUID from GameSession"
created: 2026-06-10
---

`StrategySelector.reset()` and `GameSession.reset()` fire inside the **synchronous** `GameStarted` handler. A `@ObservesAsync GameStopped` body is dispatched to a worker thread after `Event.fire()` returns — meaning a rapid `stopGame()` → `startGame()` sequence can execute the `GameStarted` reset before the async `GameStopped` body reads the selector. The result is silently wrong attribution (recording the new game's strategy as the previous game's outcome).

```java
// Wrong — races with StrategySelector.reset() on next GameStarted
void onGameStopped(@ObservesAsync GameStopped event) {
    String strategy = strategySelector.getSelectedId(); // may be already reset
}

// Correct — fires inline during Event.fire(), guaranteed before any subsequent @Observes
void onGameStopped(@Observes GameStopped event) {
    String strategy = strategySelector.getSelectedId(); // pre-reset value guaranteed
}
```

Use `@ObservesAsync` only for observers that do blocking I/O (JDBC, network) and do not read volatile game lifecycle state. `GameOutcomeRecorder` uses in-memory `OutcomeRecorder` — synchronous is safe. Consistent with `GameSession.reset()` only in `startGame()` (not `stopGame()`) for the same race-prevention reason.

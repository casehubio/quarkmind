---
id: PP-20260612-afe621
title: "gameTick() must never write gameActive"
type: rule
scope: repo
applies_to: "AgentOrchestrator — any change to gameTick() or fireGameStoppedOnce()"
severity: critical
violation_hint: "gameTick()'s connected branch calls gameActive.set(true) — this re-arms the guard after stopGame() has already CAS'd it to false, producing a second GameStopped fire"
created: 2026-06-12
---

`gameActive` is an `AtomicBoolean` that guards exactly-once firing of `GameStopped`. It is armed once per game in `startGame()` and CAS'd to false exactly once by `fireGameStoppedOnce()`. `gameTick()` must only READ `gameActive` (via the CAS in `fireGameStoppedOnce()`) — never WRITE it directly. If `gameTick()`'s connected branch called `gameActive.set(true)`, a concurrent `stopGame()` that already won the CAS (setting `gameActive` to false) would be undone on the next scheduler tick during the game-loop cleanup window, because `transport.quit()` is asynchronous and `running` stays true for several frames while the loop exits. The result is a second `GameStopped` event firing with the wrong result. The `engineWasConnected` volatile flag handles natural game-end detection in `gameTick()`; `gameActive` is solely a once-per-game fire guard owned by `startGame()` and `fireGameStoppedOnce()`.

---
id: PP-20260528-612dee
title: "Command extraction logic lives in dedicated extractor classes, not SimulatedGame subclasses"
type: principle
scope: repo
applies_to: "Classes in io.quarkmind.sc2.mock"
severity: guidance
refs:
  - src/main/java/io/quarkmind/sc2/mock/IEM10CommandExtractor.java
  - src/main/java/io/quarkmind/sc2/replay/ReplayCommandExtractor.java
violation_hint: "A method like commandStream() or extractIntents() added directly to IEM10JsonSimulatedGame, ReplaySimulatedGame, or SimulatedGame"
created: 2026-05-28
---

`SimulatedGame` subclasses own tick-by-tick state simulation only: `tick()`, `reset()`,
`snapshot()`, `isComplete()`. Intent extraction — parsing replay events into
`List<TimedIntent>` — belongs in a dedicated extractor class (`IEM10CommandExtractor`,
`ReplayCommandExtractor`). The extractor reads from the game object via package-private
accessors; the game object does not know about intents. This keeps each class's
responsibility narrow and makes both independently testable.

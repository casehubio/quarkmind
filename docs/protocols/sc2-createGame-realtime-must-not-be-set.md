---
id: PP-20260609-37704d
title: "RequestCreateGame must not set realtime — setting it silently breaks RequestStep and the game loop"
type: rule
scope: repo
applies_to: "sc2/real/QuarkusSC2Transport.createGame() and any future SC2 engine implementation"
severity: critical
refs:
  - src/main/java/io/quarkmind/sc2/real/QuarkusSC2Transport.java
  - docs/superpowers/specs/2026-06-09-quarkus-sc2-transport-design.md
violation_hint: "RequestCreateGame.newBuilder().setRealtime(true) or .setRealtime(false) — either form sets the field"
created: 2026-06-09
---

`RequestCreateGame.realtime` must never be set. The proto default (`false`) means step-based control — SC2 advances the simulation only when the bot sends `RequestStep`. If `realtime = true` is set, SC2 advances on its own clock and `RequestStep` is silently ignored; the game loop observes the same frozen frame state every step while the simulation advances in the background without bot input. Do not set this field to `false` either — an explicit `false` overrides to an identical value but may change behaviour in future SC2 API versions. Leave the field entirely absent (proto default).

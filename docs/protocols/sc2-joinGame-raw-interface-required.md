---
id: PP-20260609-deb0d7
title: "joinGame() must set InterfaceOptions.raw=true — omission silently kills observation and action paths"
type: rule
scope: repo
applies_to: "sc2/real/QuarkusSC2Transport.joinGame() and any future SC2 engine implementation"
severity: critical
refs:
  - src/main/java/io/quarkmind/sc2/real/QuarkusSC2Transport.java
  - docs/superpowers/specs/2026-06-09-quarkus-sc2-transport-design.md
violation_hint: "joinGame() builds RequestJoinGame without setOptions(InterfaceOptions.newBuilder().setRaw(true))"
created: 2026-06-09
---

`RequestJoinGame` must always include `InterfaceOptions.raw = true`. Without it, SC2 does not populate `ObservationRaw` in any frame — `obs.getRaw()` returns empty `Optional`, `orElseThrow()` crashes on the first observation, and `Action.action_raw` is silently ignored by SC2. This is confirmed from the SC2 proto comment: "Populated if Raw interface is enabled." Every SC2 engine implementation that calls `RequestJoinGame` must include this flag; there is no fallback and no error message from SC2 when it is absent.

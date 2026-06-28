---
id: PP-20260628-abcf60
title: "Protobuf translator tests must include ocraft parsing layer"
type: rule
scope: repo
applies_to: "All tests in sc2/emulated/server/ that translate between GameState and SC2 protobuf"
severity: important
refs:
  - src/test/java/io/quarkmind/sc2/emulated/server/GameStateRoundTripTest.java
  - src/test/java/io/quarkmind/sc2/emulated/server/EmulatedSC2ServerTest.java
violation_hint: "A round-trip test that only checks protobuf serialize/deserialize without calling ResponseObservation.from() or StartRaw.from()"
garden_ref: "GE-20260628-9159ce"
created: 2026-06-28
---

Translator tests for GameState↔Protobuf must include the ocraft parsing layer
(ResponseObservation.from(), StartRaw.from()) in the assertion chain — not just
protobuf serialization round-trips. Ocraft applies validation rules (orElseThrow
on required sub-fields, minimum grid sizes, mandatory camera positions) that go
beyond protobuf schema compliance. A protobuf-only round-trip test creates a false
sense of completeness; the ocraft parse step catches the gap between "protobuf-valid"
and "library-valid" that only surfaces at runtime in SC2BotAgent.

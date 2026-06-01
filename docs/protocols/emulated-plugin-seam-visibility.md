---
id: PP-20260601-5fa812
title: "External plugin seam: interface and API types public; implementations package-private"
type: rule
scope: repo
applies_to: sc2/emulated/ plugin seam interfaces (RaceModel, and future seams from #74)
severity: important
refs:
  - docs/superpowers/specs/2026-06-01-playerstate-public-api-design.md
violation_hint: "Internal implementations (e.g. ProtossRaceModel) marked public; or internal machinery types (PhysicsState, PendingCompletion) appearing in public method signatures; or factory/installation method made public before a public installation seam is designed"
created: 2026-06-01
---

When a plugin seam interface in `sc2/emulated/` is promoted to public for external implementations, promote: the interface itself, its return types, and its parameter types (e.g. `RaceModel`, `ProductionResult`, `PlayerState`). Keep internal implementations (`ProtossRaceModel`, `TerranRaceModel`, `ZergRaceModel`) and the factory/installation method package-private until the installation seam (#74) is explicitly designed as public. The public API surface is exactly what external plugin authors need — nothing less, nothing more. Leaking internal machinery (physics fields, pending completion types) or prematurely exposing the factory creates coupling that is hard to remove later.

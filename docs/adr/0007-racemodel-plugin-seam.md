# 0007 — RaceModel plugin seam for multi-race EmulatedGame

Date: 2026-05-30
Status: Accepted

## Context and Problem Statement

`EmulatedGame` was hardcoded to Protoss: `reset()` seeded Protoss units, `countProbesPerBase()`
filtered on PROBE/NEXUS, and `tick()` accumulated probe-only income. Adding Terran
(MULE, SCV, OC) and Zerg (larva/Egg, Queen inject, Overlord supply) mechanics required a
structural decision about where race-specific logic lives. The platform vision (#74) also
calls for pluggable races — SC2 as a plugin, not baked-in.

## Decision Drivers

* Race-specific mechanics (larva regen, MULE expiry, Queen energy) are substantial and
  grow with each race — mixing them into `EmulatedGame` would make the class much harder
  to reason about
* #74 explicitly targets pluggable races: SC2 implementations must be replaceable by
  YAML-backed plugins without touching `EmulatedGame`
* The two-phase production check (canProduce before resource deduction,
  onProductionCommitted after) must be correctness-preserving regardless of which race
  is playing

## Considered Options

* **Option A** — Inline switch branches in EmulatedGame
* **Option B** — `RaceModel` interface with per-race implementations (chosen)
* **Option C** — EmulatedGame subclasses per race

## Decision Outcome

Chosen option: **Option B — `RaceModel` interface**, because it separates race concerns
cleanly from physics, is independently testable per race, and establishes the plugin seam
#74 needs. `EmulatedGame` holds `private RaceModel playerRaceModel = new ProtossRaceModel()`
and delegates six operations: `seedInitialState`, `tickPassive`, `canProduce`,
`onProductionCommitted`, `onUnitSpawned`, `trainCount`.

### Positive Consequences

* Each race model is independently readable and unit-testable without constructing a full `EmulatedGame`
* `EmulatedGame` is now race-agnostic: no Protoss-specific code remains in the physics engine
* SC2 implementations can be replaced by YAML-backed plugins (#74) without touching `EmulatedGame`
* `ProductionResult` enum (PROCEED/HANDLED/BLOCKED) makes the MULE short-circuit explicit rather than a confusing `false` return

### Negative Consequences / Tradeoffs

* `RaceModel` lives in `sc2/emulated/` (same package as `PlayerState`) so implementations have direct field access — external race plugins will need `PlayerState` to expose a public mutator API (#164)
* EGG units are spawned in `onProductionCommitted` even for queued training, so an EGG appears before morphing actually begins (documented as Known Divergence; acceptable for tutorial scope)

## Pros and Cons of the Options

### Option A — Inline switch branches in EmulatedGame

* ✅ No new files or interfaces
* ❌ `EmulatedGame` grows to contain Protoss + Terran + Zerg mechanics mixed with movement and combat — hard to reason about independently
* ❌ No plugin seam for #74 — race logic stays baked in

### Option B — `RaceModel` interface (chosen)

* ✅ Each race independently readable and testable
* ✅ Natural plugin seam for #74
* ✅ `EmulatedGame` stays physics-only
* ❌ Requires #164 (PlayerState public API) before external race plugins can work

### Option C — EmulatedGame subclasses per race

* ✅ Maximum per-race clarity
* ❌ Shared `tick()` becomes hard to reason about across subclasses
* ❌ Cannot support a Protoss-vs-Zerg game where both sides use the same engine instance
* ❌ No plugin seam for #74

## Links

* Spec: `docs/superpowers/specs/2026-05-30-terran-zerg-emulated-mechanics-design.md`
* Issue: #138 (implementation), #74 (pluggable races future), #164 (PlayerState public API debt)
* See also: ADR-0006 (EmulatedGame/SimulatedGame separation)

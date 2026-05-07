# 0006 — Separation of EmulatedGame and SimulatedGame

Date: 2026-05-07
Status: Accepted

## Context and Problem Statement

Two simulation modes are needed: a scripted oracle for deterministic tests, and
a physics engine for closed-loop development without SC2. The question is whether
to evolve one class to serve both purposes or maintain them separately.

## Decision Drivers

* Test determinism — the oracle must produce exactly the values tests expect, every time
* Physics evolution — the engine must grow (combat, pathfinding, building collision) without affecting test stability
* Cognitive clarity — readers should understand each class's single purpose immediately

## Considered Options

* **Option A** — Separate classes: `SimulatedGame` (oracle) and `EmulatedGame` (physics engine)
* **Option B** — Single class with a mode flag (`oracle` vs `physics`)
* **Option C** — Evolve `SimulatedGame` in-place to include physics

## Decision Outcome

Chosen option: **Option A**, because mixing physics into the oracle corrupts
its determinism — physics involves randomness (high-ground miss chance),
pathfinding heuristics, and frame-dependent state that makes test assertions brittle.

### Positive Consequences

* `SimulatedGame` remains a hand-crafted, deterministic oracle; all existing tests are stable
* `EmulatedGame` evolves freely (E1–E6 and beyond) without breaking the test suite
* Future engines (replay-accurate forward simulation) can be added without touching either

### Negative Consequences / Tradeoffs

* Some logic must stay consistent between both — mitigated by sharing `SC2Data` in `domain/`
* Two code paths for overlapping intent-handling concepts (train, build)

## Pros and Cons of the Options

### Option A — Separate classes

* ✅ Oracle determinism preserved; physics evolves independently
* ✅ Each class has one clear purpose — oracle or engine
* ❌ Some intent-handling logic is similar in both

### Option B — Mode flag

* ✅ Single class
* ❌ Mode flag proliferates into every method; harder to reason about
* ❌ Physics randomness leaks into oracle mode inadvertently

### Option C — Evolve SimulatedGame in-place

* ✅ No duplication
* ❌ Physics additions corrupt test oracle determinism
* ❌ Tests that relied on exact oracle values fail unpredictably

## Links

* `SimulatedGame` — `sc2/mock/SimulatedGame.java`
* `EmulatedGame` — `sc2/emulated/EmulatedGame.java`
* ADR-0005 — SC2Data in domain/ (constants shared between both)

# 0005 — SC2Data shared constants in domain/

Date: 2026-05-07
Status: Accepted

## Context and Problem Statement

Both `SimulatedGame` and `EmulatedGame` need the same SC2 constants: damage
per hit, attack range, supply cost, shield values, building health, mineral
costs, movement speeds, and building radii. Duplicating them in each engine
causes silent drift — a constant corrected in one engine stays wrong in the other.

## Decision Drivers

* Single source of truth — one set of constants, no drift between engines
* Native compatibility — `domain/` carries no framework dependencies
* Accessibility — both engines and future ones can import without coupling to each other

## Considered Options

* **Option A** — `SC2Data` static class in `domain/`
* **Option B** — `SC2Data` in `sc2/emulated/` (engine-specific package)
* **Option C** — Duplicate constant tables in each engine

## Decision Outcome

Chosen option: **Option A**, because `domain/` is the framework-free shared
layer that both engines already depend on.

### Positive Consequences

* `SimulatedGame` and `EmulatedGame` always use the same constants
* Tests can assert `SC2Data` values independently of any engine
* Native-safe — no Quarkus annotations enter `domain/`

### Negative Consequences / Tradeoffs

* `domain/` is no longer purely data records — it now includes a constants utility class
* Changes to `SC2Data` affect both engines simultaneously (desired, but requires care on breaking changes)

## Pros and Cons of the Options

### Option A — domain/ shared

* ✅ One source of truth; eliminates drift
* ✅ Native-compatible; no framework imports
* ❌ `domain/` grows beyond pure records

### Option B — sc2/emulated/

* ✅ Keeps `domain/` pure
* ❌ `SimulatedGame` cannot import without crossing package boundaries
* ❌ Forces duplication or an awkward reverse dependency

### Option C — Duplicate per engine

* ✅ Total isolation between engines
* ❌ Values drift silently; bugs present in one engine only
* ❌ Every data correction requires two edits

## Links

* `SC2Data` — `domain/SC2Data.java`
* ADR-0006 — EmulatedGame/SimulatedGame separation (both share SC2Data)

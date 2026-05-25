# 0003 — attackingUnits Set for attack-mode tracking

Date: 2026-05-07
Status: Superseded (removed in #134, 2026-05-25)

## Context and Problem Statement

Units have both a movement target (`unitTargets`) and an attack mode. The
kiting mechanic requires that a `MoveIntent` on a cooldown tick stops auto-fire,
while an `AttackIntent` re-enables it. A single command type cannot express both
semantics cleanly.

## Decision Drivers

* Kiting correctness — `MoveIntent` on cooldown ticks must not fire
* SC2 semantics — `AttackIntent` enables auto-attack; a pure move does not
* Minimal state — avoid per-unit command-type fields on the immutable `Unit` record

## Considered Options

* **Option A** — Separate `Set<String> attackingUnits`; `AttackIntent` adds, `MoveIntent` removes
* **Option B** — `unitTargets` stores a tagged record (target + isAttack boolean)
* **Option C** — Command enum field on each `Unit` record

## Decision Outcome

Chosen option: **Option A**, because it separates movement and attack-mode
concerns cleanly, and the Set is cheap to query in `resolveCombat()`.

### Positive Consequences

* Kiting works correctly: `MoveIntent` clears attack mode, unit stops firing that tick
* `resolveCombat()` loops only `attackingUnits` — no need to inspect every unit
* Unit death removes from both `unitTargets` and `attackingUnits` in one cleanup block

### Negative Consequences / Tradeoffs

* Two data structures must be kept consistent on unit death and intent dispatch
* A stale tag after death would cause a silent no-op (handled in `removeIf` cleanup)

## Pros and Cons of the Options

### Option A — Separate Set

* ✅ Clean separation; fast `contains()` check in hot combat loop
* ✅ `MoveIntent` cancel path is a single `remove()` call
* ❌ Two structures to keep in sync

### Option B — Tagged record in unitTargets

* ✅ Single structure
* ❌ Every movement update must carry the attack flag; easy to lose on repath
* ❌ Complicates `unitTargets` with non-movement semantics

### Option C — Command enum on unit record

* ✅ Self-contained per unit
* ❌ `Unit` is an immutable record — adding mutable command state requires rebuilding every tick

## Links

* `PlayerState.attackingUnits` — field (removed in #134)
* `EmulatedGame.setTarget()` — where add/remove happened (removed in #134)
* `EmulatedGame.resolveCombat()` — where it was consumed (removed in #129; became dead state)
* E4 emulation epic (attack cooldowns + cancel path)

## Superseded by

The decision drivers changed after #129 (auto-engage). `resolveCombat()` was rewritten to fire
via `nearestInRange` without consulting `attackingUnits`, making the Set write-only dead state.
`attackingUnits` was removed entirely in #134.

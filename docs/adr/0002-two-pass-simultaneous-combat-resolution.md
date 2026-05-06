# 0002 — Two-pass simultaneous combat resolution

Date: 2026-05-07
Status: Accepted

## Context and Problem Statement

`EmulatedGame.resolveCombat()` must apply damage from all attacking units each
tick. Sequential resolution (apply damage as each attacker fires) is
order-dependent: the first unit in iteration order can kill the second before it
fires, giving it a full tick of free firing. Real SC2 resolves combat
simultaneously within a game loop.

## Decision Drivers

* Fairness — both units in melee should fire in the same tick
* Reproducibility — outcome must not depend on iteration order
* Simplicity — solution must not complicate the data model

## Considered Options

* **Option A** — Two-pass: collect all damage into a map, then apply
* **Option B** — Sequential: apply damage immediately as each attacker fires
* **Option C** — Priority queue ordered by unit speed/attack rating

## Decision Outcome

Chosen option: **Option A**, because it matches SC2 semantics and eliminates
iteration-order bias with minimal complexity.

### Positive Consequences

* Units at equal HP kill each other simultaneously, as in real SC2
* Adding units to lists does not change combat outcomes
* Easy to reason about and test

### Negative Consequences / Tradeoffs

* One extra `Map<String, Integer>` allocation per tick (negligible at emulated scale)

## Pros and Cons of the Options

### Option A — Two-pass collect-then-apply

* ✅ Order-independent; matches real SC2 semantics
* ✅ Simple data structure; no extra state
* ❌ Minor allocation overhead

### Option B — Sequential

* ✅ No extra allocation
* ❌ First unit in iteration order gets free firing if it kills the second
* ❌ Outcome changes if unit list order changes

### Option C — Priority queue

* ✅ Could model attack speed differences
* ❌ Overcomplicated for the emulation scale
* ❌ SC2 uses simultaneous resolution within a loop, not priority ordering

## Links

* `EmulatedGame.resolveCombat()` — implementation
* E3 emulation epic

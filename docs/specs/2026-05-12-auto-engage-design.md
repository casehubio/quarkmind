# Friendly (and Enemy) Auto-Engage — Design Spec
**Issue:** #129 (epic #127 — Phase 5 completion)
**Date:** 2026-05-12

## Problem

`resolveCombat()` only fires for units in `attackingUnits`, a set populated exclusively by explicit `AttackIntent`. In real SC2, all units auto-attack the nearest enemy in weapon range every cooldown cycle — no command required. This gap causes systematic divergence from real replays in Phase 6 validation: any tick where a unit is in range but not commanded results in zero damage in the emulator versus real damage in the replay.

Additionally, `moveEnemyUnits()` has a stop-to-fight range check that halts enemy movement when a friendly enters range. Real SC2 units fire while continuing to move — they don't stop. This is a further source of divergence.

## Scope

1. Remove the `attackingUnits` gate from `resolveCombat` — any unit with an enemy in weapon range fires, every tick
2. Remove the stop-to-fight range check from `moveEnemyUnits` — enemy units advance while firing
3. Apply symmetrically to both friendly and enemy units

Out of scope: removing the now-dead `attackingUnits` field from `PlayerState` — tracked in #134.

## Design

### `resolveCombat` — gate removal

**Before (both loops):**
```java
if (!friendly.attackingUnits.contains(attacker.tag())) continue;
```

**After:** delete that line. The `nearestInRange` call that follows already exists — it just needs to run for every unit, not only commanded ones. Any unit with an enemy in weapon range fires this tick. Cooldown still gates fire rate per existing logic.

The remainder of `resolveCombat` (two-pass damage collection, high-ground miss chance, death cleanup, cooldown reset) is unchanged.

### `moveEnemyUnits` — stop-to-fight removal

**Before:**
```java
if (!retreating.contains(u.tag()) &&
        nearestInRange(u.position(), friendly.units, SC2Data.attackRange(u.type())).isPresent()) {
    return u; // stay and fight — resolveCombat() handles the attack this tick
}
```

**After:** delete this block. Enemy units always advance toward their target (or `NEXUS_POS` default). `resolveCombat` fires for them independently each tick if an enemy is in range.

### `moveFriendlyUnits` — no change

Friendly movement is already independent of combat. Units with a target continue toward it; units without a target stay put. Both will now fire via `resolveCombat` when an enemy enters range.

### `attackingUnits` — retained but no longer gates combat

The field remains on `PlayerState`. It is still:
- Populated by `setTarget` (AttackIntent adds, MoveIntent removes)
- Cleaned up on unit death
- Added by `spawnEnemyForTesting`

It no longer gates combat. It is now dead state for combat purposes — cleanup deferred to #134.

## Test Strategy

### New test (TDD red phase first)
`friendlyAutoEngagesWithoutAttackIntent`:
- Spawn a friendly unit adjacent to an enemy (within weapon range)
- Issue no intents
- Tick once
- Assert enemy took damage and enemy HP < max

Currently fails: no `AttackIntent` issued, gate blocks combat.

### Blast radius on existing tests

**Unaffected:** Any test using `spawnEnemyForTesting` — those already add to `attackingUnits`, so their combat eligibility is identical under the gate-free model.

**Potentially affected:** Multi-tick combat tests that relied on enemies stopping at a specific position when a friendly enters range (stop-to-fight removal). Outcomes (who wins, who dies) should be equivalent or more realistic, but timing may shift by a tick. Run full suite after each sub-change and update affected tests.

## Files Touched

| File | Change |
|------|--------|
| `sc2/emulated/EmulatedGame.java` | Remove `attackingUnits` gate from both loops in `resolveCombat`; remove stop-to-fight block from `moveEnemyUnits` |
| `sc2/emulated/EmulatedGameTest.java` | New `friendlyAutoEngagesWithoutAttackIntent` test; update any tests broken by stop-to-fight removal |

## Invariants Preserved

- Two-pass damage collection (collect all damage, then apply) — unchanged
- High-ground miss chance check — unchanged
- Weapon cooldown per unit — unchanged
- Blink mechanics — unchanged
- Retreat logic — unchanged (retreating units still always move)

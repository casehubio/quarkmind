# RaceModel.canProduce() Read-Only Enforcement — Design Spec

**Issue:** #165  
**Branch:** issue-165-racemodel-canproduce-readonly  
**Date:** 2026-06-01 (rev 3 — enum ProductionDecision, fixed test section)

---

## Problem

`RaceModel.canProduce()` carried a doc-only contract: must not mutate `PlayerState`
when returning `PROCEED` or `BLOCKED`; may mutate only when returning `HANDLED`.

The root cause runs deeper than the doc-only enforcement. `HANDLED` exists entirely to
bend `ProductionDecision` into a command carrier — because MULE calldown was modelled as
`TrainIntent(oc, UnitType.MULE)`, which it is not. A MULE calldown uses Orbital Command
energy, spawns instantly with no queue, and expires after a fixed lifetime. It is an
ability use, not training.

Modelling MULE as `TrainIntent` forced `canProduce` to return a deferred write action
(`Consumer<PlayerState>`) for the HANDLED case, making `ProductionDecision` carry two
unrelated concerns. The correct fix is to give MULE calldown its own intent type, which
makes `ProductionDecision` a clean read-only decision type and eliminates the write path
entirely.

---

## Design

### New type: `MuleCalldownIntent`

Record in `sc2/intent/`, added to the sealed `Intent` permits clause:

```java
public record MuleCalldownIntent(String buildingTag) implements Intent {}
```

`Intent` becomes:
```java
public sealed interface Intent
    permits BuildIntent, TrainIntent, AttackIntent, MoveIntent, BlinkIntent, MuleCalldownIntent {}
```

Per the project convention (GE-20260418-9b272f): the permits clause change and all
switches over `Intent` update in the same commit.

---

### New type: `PlayerStateView`

Public interface in `sc2.emulated`. Read-only projection of `PlayerState`:

```java
public interface PlayerStateView {
    double minerals();
    int vespene();
    int supply();
    int supplyUsed();
    List<Unit> units();        // returns unmodifiable list
    List<Building> buildings(); // returns unmodifiable list
}
```

`PlayerState` adds `implements PlayerStateView`. No other changes to `PlayerState` —
all six methods already exist with these exact signatures.

---

### New type: `ProductionDecision` (replaces `ProductionResult`)

Public enum in `sc2.emulated`. Two values — no data, no write path:

```java
public enum ProductionDecision { PROCEED, BLOCKED }
```

A sealed interface was considered but rejected: it was justified when `Handled` carried a
`Consumer<PlayerState>`, but with only two stateless variants a sealed type adds nesting
and import complexity with no gain. Exhaustive switch works on enums. MULE calldown now
routes through `MuleCalldownIntent` and a third variant is not anticipated.

`ProductionResult` is deleted. All references are within `sc2.emulated` and the two
`TerranEmulatedGameTest` MULE tests.

---

### `RaceModel` changes

**`canProduce` — signature change:**

```java
ProductionDecision canProduce(PlayerStateView view, String buildingTag, UnitType unitType);
```

Read-only enforcement is now structural. The MULE special-case is gone; no implementation
needs to return anything other than PROCEED or BLOCKED from this method. The stale
Javadoc (which described `PlayerState` as package-private) is corrected — `PlayerState`
is public since #164.

**New default method `onCalldown`:**

```java
/**
 * Handle a direct calldown ability for this race (e.g. MULE calldown from an Orbital Command).
 * Called by EmulatedGame after OC building validation succeeds.
 * May mutate PlayerState (addUnit, removeUnit) and model-internal state.
 * Must NOT manipulate resource fields (minerals, vespene, supply).
 * Default: no-op.
 *
 * @param absLoop absolute game loop (gameFrame × LOOPS_PER_TICK) when calldown was issued
 */
default void onCalldown(PlayerState state, String buildingTag, long absLoop) {}
```

---

### Implementation changes

**`ProtossRaceModel`** — trivial (unchanged in effect):
```java
public ProductionDecision canProduce(PlayerStateView view, String buildingTag, UnitType unitType) {
    return ProductionDecision.PROCEED;
}
```

**`ZergRaceModel`** — reads internal map only, unchanged in effect:
```java
public ProductionDecision canProduce(PlayerStateView view, String buildingTag, UnitType unitType) {
    if (hatcheryLarvaCount.getOrDefault(buildingTag, 0) > 0) return ProductionDecision.PROCEED;
    return ProductionDecision.BLOCKED;
}
```

**`TerranRaceModel`** — `canProduce` becomes trivial (Terran has no larva-style blocking
resource); MULE spawn moves to `onCalldown`:

```java
public ProductionDecision canProduce(PlayerStateView view, String buildingTag, UnitType unitType) {
    return ProductionDecision.PROCEED;
}

@Override
public void onCalldown(PlayerState state, String buildingTag, long absLoop) {
    final Building oc = state.buildings().stream()
        .filter(b -> b.tag().equals(buildingTag) && b.isComplete())
        .findFirst().orElse(null);
    if (oc == null) return;
    final String muleTag = "mule-" + buildingTag + "-" + absLoop;
    final int hp = SC2Data.maxHealth(UnitType.MULE);
    state.addUnit(new Unit(muleTag, UnitType.MULE, oc.position(), hp, hp, 0, 0, 0, 0));
    muleExpiresAtLoop.put(muleTag, absLoop + SC2Data.MULE_LIFETIME_LOOPS);
}
```

`muleExpiresAtLoop` stays in `TerranRaceModel` — MULE income and expiry tracking in
`tickPassive` remains unchanged.

**`EmulatedGame`** — two Intent switches gain `MuleCalldownIntent`; `handleTrain` is
simplified; new `handleMuleCalldown` added:

Timed-intent switch:
```java
case MuleCalldownIntent m -> () -> handleMuleCalldown(m, friendly, friendlyPhysics, ti.loop())
```

Immediate-intent switch:
```java
case MuleCalldownIntent m -> () -> handleMuleCalldown(m, state, physics,
                                       gameFrame * SC2Data.LOOPS_PER_TICK)
```

New handler:
```java
private void handleMuleCalldown(MuleCalldownIntent m, PlayerState state,
                                 PhysicsState physics, long absLoop) {
    final boolean ocPresent = state.buildings().stream()
        .anyMatch(b -> b.tag().equals(m.buildingTag()) && b.isComplete()
                  && b.type() == BuildingType.ORBITAL_COMMAND);
    if (!ocPresent) {
        log.debugf("[EMULATED] MULE calldown rejected — OC %s not ready", m.buildingTag());
        return;
    }
    final RaceModel model = (state == friendly) ? playerRaceModel : null;
    if (model != null) model.onCalldown(state, m.buildingTag(), absLoop);
}
```

`handleTrain` race model check is simplified — only `PROCEED` and `BLOCKED` remain:
```java
if (model != null
        && model.canProduce(state, buildingTag, t.unitType()) == ProductionDecision.BLOCKED) {
    log.debugf("[EMULATED] Train rejected — production resource unavailable for %s", t.unitType());
    return;
}
```

With a two-value enum an equality check is cleaner than a switch. If a third value is
ever added, this becomes a compile-time error on the first place it's used wrong — which
is sufficient.

**`ActionTranslator`** — adds `MuleCalldownIntent` case; real SC2 ability mapping is
stubbed (MULE calldown via Orbital ability is not yet wired to the real SC2 path — this
is an existing gap noted in `AbilityMapping`):

```java
case MuleCalldownIntent m -> null  // TODO: wire OC calldown ability for real SC2
```

---

## Testing

**Updated tests (signature changes only):**
- `TerranEmulatedGameTest` — 2 existing MULE tests: `new TrainIntent(oc.tag(), UnitType.MULE)`
  → `new MuleCalldownIntent(oc.tag())`

**New tests in `TerranEmulatedGameTest`:**
- `canProduce_alwaysReturnsProceeed` — `TerranRaceModel.canProduce(view, ...)` returns
  `PROCEED` for any unit type; Terran has no larva-style blocking resource
- `handleMuleCalldown_ocPresent_spawnsMuleAndRegistersExpiry` — `game.applyIntent(new
  MuleCalldownIntent(oc.tag()))`, assert MULE unit present in state and
  `activeMuleCount() == 1`
- `handleMuleCalldown_ocAbsent_noUnitAdded` — intent targets a tag that is not an OC (or
  does not exist); assert no MULE in state, unit count unchanged

**New tests in `ZergEmulatedGameTest`:**
- `canProduce_usesView_noLarva_returnsBlocked` — directly calls
  `ZergRaceModel.canProduce(view, ...)` with a `PlayerStateView` argument where no larva
  is present; confirms the structural enforcement compiles and works

**Existing tests unchanged:**
- `TerranEmulatedGameTest` MULE income/expiry tests (after signature fix above)
- `ZergEmulatedGameTest` all production tests (via `EmulatedGame`)
- `EmulatedGameTest` all Protoss production tests

---

## Protocol coherence

- **PP-20260601-5fa812:** `PlayerStateView` (parameter type) and `ProductionDecision`
  (return type) are public; `MuleCalldownIntent` is public (it is an Intent); all
  implementations remain package-private. Compliant.
- **GE-20260418-9b272f:** All switches over `Intent` update in the same commit as the
  permits clause change.

---

## Scope

Changes are within `sc2.emulated`, `sc2.intent`, `sc2.real`, and their test counterparts.
No domain, agent, plugin, or Flyway changes. Refs #165, #74.

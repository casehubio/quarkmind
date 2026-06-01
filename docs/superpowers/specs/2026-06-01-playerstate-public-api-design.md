# Design: PlayerState Public API (Issue #164)

**Branch:** `issue-164-playerstate-public-api`  
**Date:** 2026-06-01  
**Issue:** [#164](https://github.com/mdproctor/quarkmind/issues/164)

---

## Problem

`PlayerState` is package-private in `io.quarkmind.sc2.emulated`. `RaceModel` interface methods
take `PlayerState` directly, so external race plugin implementations (required by #74 — pluggable
races) cannot implement the interface. Making `RaceModel` public without fixing `PlayerState` is
meaningless.

The deeper problem: `PlayerState` conflates three distinct concerns, making any visibility fix
awkward:
- **Game state** — units, buildings, minerals, vespene, supply, supplyUsed
- **Physics state** — movement targets, cooldowns, pending completions, building queues
- **Enemy AI state** — staging area for retreating units

---

## Design

### Structural split

Rather than patching visibility, separate the three concerns explicitly.

| Concern | Destination | Visibility |
|---|---|---|
| Game state | `PlayerState` (refactored) | `public` |
| Physics machinery | New `PhysicsState` class | package-private |
| Enemy retreat buffer | `EnemyBehavior.stagingArea` | package-private |

---

### `PlayerState` — public class, private fields, typed API

```java
public class PlayerState {

    private final List<Unit>     units     = new ArrayList<>();
    private final List<Building> buildings = new ArrayList<>();
    private double minerals;
    private int    vespene;
    private int    supply;
    private int    supplyUsed;

    // Seed / reset
    public void setMinerals(double m)
    public void setVespene(int v)
    public void setSupply(int s)
    public void setSupplyUsed(int s)

    // Runtime mutation
    public void   addMinerals(double amount)
    public void   deductMinerals(double cost)
    public double minerals()

    public void deductVespene(int cost)
    public int  vespene()

    public void addSupply(int amount)
    public int  supply()

    public void addSupplyUsed(int cost)
    public int  supplyUsed()

    public void       addUnit(Unit unit)
    public void       removeUnit(String tag)
    public List<Unit> units()                 // unmodifiable view

    public void           addBuilding(Building building)
    public List<Building> buildings()         // unmodifiable view

    // Package-private — EmulatedGame bulk physics operations only
    List<Unit> removeUnitsWhere(Predicate<Unit> pred)   // returns removed units (see resolveCombat note)
    void replaceAllUnits(UnaryOperator<Unit> op)
    void replaceAllBuildings(UnaryOperator<Building> op)
    void clear()
}
```

**`removeUnitsWhere` returns `List<Unit>`** (not `void`) so that callers performing physics
cleanup after unit death can avoid a pre-collect step. See resolveCombat section below.

The distinction between public and package-private: public methods are semantic operations
meaningful to a race model plugin; package-private methods are bulk structural operations used
only by EmulatedGame's physics engine.

**Read-only contract on `canProduce()`:** `RaceModel.canProduce()` must not mutate `PlayerState`
when returning `PROCEED` or `BLOCKED`. This contract is doc-only — the public API cannot enforce
it because `seedInitialState()` legitimately needs the same setter methods. API enforcement is
deferred to #74 (tracked in [#165](https://github.com/mdproctor/quarkmind/issues/165)).

---

### `PhysicsState` — new package-private class

```java
class PhysicsState {
    final Map<String, Point2d>     unitTargets             = new HashMap<>();
    final Map<String, Integer>     unitCooldowns           = new HashMap<>();
    final Map<String, Integer>     blinkCooldowns          = new HashMap<>();
    final Map<String, Deque<UnitType>> buildingQueues      = new HashMap<>();
    final Map<String, Long>        buildingTrainingUntil   = new HashMap<>();
    final Map<String, Long>        buildingCompletionAtLoop = new HashMap<>();
    final List<PendingCompletion>  pendingCompletions      = new ArrayList<>();

    void fireCompletions(long currentTick) { ... }
    void clear() { ... }

    record PendingCompletion(long completesAtTick, Runnable action) {}
}
```

Fields remain directly accessible within the package — same pattern as the current `PlayerState`
fields. `PendingCompletion` moves here from `PlayerState`.

**Javadoc for `PhysicsState`:** "Internal simulation state not exposed to external observers:
production queues, movement targets, and combat cooldowns. Contains both production scheduling
state (buildingQueues, pendingCompletions, buildingTrainingUntil, buildingCompletionAtLoop) and
movement/combat state (unitTargets, unitCooldowns, blinkCooldowns) — grouped here because both
categories are EmulatedGame internals that race model plugins never touch."

**Lambda capture note:** lambdas stored in `pendingCompletions` close over both a `PlayerState`
and a `PhysicsState` (the pair in scope when `startTraining()` or `handleBuild()` is called).
Both variables are effectively final at capture time — this is safe and correct. A
`PendingCompletion` must not be transferred between physics objects: it references a specific
player's `PlayerState` and `PhysicsState` pair.

---

### `EnemyBehavior.stagingArea`

`stagingArea` is semantically enemy AI state — the wave buffer for units not yet ready to
re-attack. It moves from `PlayerState` to `EnemyBehavior` as a package-private field:

```java
// EnemyBehavior.java
final List<Unit> stagingArea = new ArrayList<>();
```

**`EnemyBehavior.reset()` must call `stagingArea.clear()`** — currently `enemy.clear()` handles
this implicitly. After the move, `reset()` must explicitly clear the staging area or units from
a previous game will survive into the next.

**EmulatedGame call sites** that currently write to `enemy.stagingArea` switch to
`enemyBehavior.stagingArea`. Affected production code sites:
- `tickEnemyRetreatTransfer()` — the main retreat path: `enemy.unitTargets.remove(u.tag())` →
  `enemyPhysics.unitTargets.remove(u.tag())`; `enemy.stagingArea.add(u)` →
  `enemyBehavior.stagingArea.add(u)`. This is the only production code path that writes to
  staging area.
- `addStagedUnitForTesting()` — fog-of-war test helper (see below)

**`addStagedUnitForTesting()` pre-condition:** this method requires `enemyBehavior != null`.
After the move it accesses `enemyBehavior.stagingArea` directly. The fog-of-war test that calls
it without `setEnemyStrategy()` must be updated to set up a minimal enemy strategy first (one
line). Do not add a null guard or fallback — the pre-condition is real: staging only makes sense
when an `EnemyBehavior` manages the retreat cycle.

`enemyStagingSize()` on EmulatedGame becomes:
```java
int enemyStagingSize() {
    return enemyBehavior != null ? enemyBehavior.stagingArea.size() : 0;
}
```

---

### `EmulatedGame` structural changes

**Field declarations:**

```java
final PlayerState  friendly        = new PlayerState();
final PlayerState  enemy           = new PlayerState();
final PhysicsState friendlyPhysics = new PhysicsState();   // package-private for tests
final PhysicsState enemyPhysics    = new PhysicsState();   // package-private for tests
EnemyBehavior      enemyBehavior;                          // was private; package-private for tests
```

**Internal method signatures** — every method that currently takes `PlayerState state` and
accesses physics fields through it gains a `PhysicsState physics` parameter:

```java
void applyIntent(Intent intent, PlayerState state, PhysicsState physics)
private void setTarget(String tag, Point2d target, PlayerState state, PhysicsState physics)
private void handleTrain(TrainIntent t, PlayerState state, PhysicsState physics)
private void startTraining(UnitType, String buildTag, PlayerState, PhysicsState, long absLoop)
private void drainBuildingQueues(PlayerState state, PhysicsState physics)
private void handleBuild(BuildIntent b, PlayerState state, PhysicsState physics, long absLoop)
private void markBuildingComplete(String tag, PlayerState state, PhysicsState physics)
private void executeBlink(String tag, PlayerState state, PhysicsState physics)
```

Call sites pair each `PlayerState` with its corresponding `PhysicsState`:
```java
applyIntent(intent, friendly, friendlyPhysics)
applyIntent(intent, enemy, enemyPhysics)
```

The identity check `state == friendly` (gates the race model) is unchanged.

**`reset()`** calls `friendly.clear()` + `friendlyPhysics.clear()` + `enemy.clear()` +
`enemyPhysics.clear()`.

**`tick()`** calls `friendlyPhysics.fireCompletions(gameFrame)` and
`enemyPhysics.fireCompletions(gameFrame)`.

#### `resolveCombat()` — two-step unit cleanup

After the split, `removeUnitsWhere(Predicate<Unit>)` returns the removed units. This allows
physics cleanup without a separate pre-collect pass:

```java
// Friendly deaths
List<Unit> deadFriendly = friendly.removeUnitsWhere(u -> u.health() <= 0);
deadFriendly.forEach(u -> {
    friendlyPhysics.unitTargets.remove(u.tag());
    friendlyPhysics.unitCooldowns.remove(u.tag());
    friendlyPhysics.blinkCooldowns.remove(u.tag());
    movementStrategy.clearUnit(u.tag());
});

// Enemy deaths
List<Unit> deadEnemy = enemy.removeUnitsWhere(u -> u.health() <= 0);
deadEnemy.forEach(u -> {
    enemyPhysics.unitTargets.remove(u.tag());
    enemyPhysics.unitCooldowns.remove(u.tag());
    enemyPhysics.blinkCooldowns.remove(u.tag());
    movementStrategy.clearUnit(u.tag());
    if (enemyBehavior != null) enemyBehavior.clearRetreating(u.tag());
});
```

#### `snapshot()` — three migrations

1. `friendly.unitCooldowns` → `friendlyPhysics.unitCooldowns`
2. `friendly.blinkCooldowns` → `friendlyPhysics.blinkCooldowns`
3. `enemy.stagingArea` → null-guarded via `enemyBehavior`:

```java
List<Unit> stagingArea = enemyBehavior != null
    ? enemyBehavior.stagingArea
    : List.of();
```

Both staging area references in `snapshot()` (fog path and no-fog path) use this guard.
`enemyBehavior` is null in test scenarios that use `configureWave()` without `setEnemyStrategy()`.

#### Harness methods

The following public and package-private methods on EmulatedGame access `PlayerState` fields
directly and require mechanical migration to the public API:

| Method | Change |
|---|---|
| `setSupplyCapForHarness(int supply)` | `friendly.setSupply(supply)` |
| `setVespeneForHarness(int vespene)` | `friendly.setVespene(vespene)` |
| `injectReplayBuilding(Building)` | `friendly.addBuilding(building)` |
| `injectReplayBuildingWithCost(Building)` | `friendly.deductMinerals(cost)` + `friendly.addBuilding(building)` |
| `markReplayBuildingComplete(String tag)` | `friendly.replaceAllBuildings(op)` (package-private) |
| `spawnFriendlyUnitForTesting(UnitType, Point2d)` | `friendly.addUnit(unit)` |
| `addStagedUnitForTesting(UnitType, Point2d)` | `enemyBehavior.stagingArea.add(unit)` |
| `setMineralsForTesting(int)` | `friendly.setMinerals(amount)` |
| `enemyMinerals()` | `(int) enemy.minerals()` |

---

### Visibility promotions

| Type | Before | After | Notes |
|---|---|---|---|
| `RaceModel` | package-private interface | `public interface` | Method signatures unchanged |
| `ProductionResult` | package-private enum | `public enum` | |
| `RaceModelFactory` | package-private class | `public class` | |
| `ProtossRaceModel` | package-private | package-private | SC2 internal; #74 will displace |
| `TerranRaceModel` | package-private | package-private | |
| `ZergRaceModel` | package-private | package-private | |

**`setPlayerRaceModel()` stays package-private.** External plugins can implement `RaceModel` but
have no public installation mechanism yet. #74 will either make `setPlayerRaceModel()` public or
provide an alternative CDI-based seam. This is intentional — #74 owns the full external plugin
lifecycle.

---

### Race model implementation migration

All three implementations migrate from direct field access to the typed public API. This validates
the API surface is complete before #74.

Examples:
```java
// Before
state.minerals = SC2Data.INITIAL_MINERALS;
state.units.add(new Unit(...));
state.supply += 8;

// After
state.setMinerals(SC2Data.INITIAL_MINERALS);
state.addUnit(new Unit(...));
state.addSupply(8);
```

---

### Test migration

| Test | Change |
|---|---|
| `PlayerStateTest` (game state: units, buildings, minerals, supply) | API call updates: `s.minerals` → `s.minerals()`, `s.minerals = 500` → `s.setMinerals(500)`, `s.units.add(u)` → `s.addUnit(u)` |
| `PlayerStateTest` (physics: `pendingCompletions`, `unitTargets`) | Move to new `PhysicsStateTest` |
| `PlayerStateTest` (`stagingArea` in `clear()` test) | Test `clear()` now covers game state only; `PhysicsStateTest` covers physics; `EnemyBehavior.reset()` covers staging |
| `EmulatedGameTest` | All `enemy.unitTargets.*` sites (6: lines 404, 988, 1217, 1218, 1247, 1477) → `enemyPhysics.unitTargets.*`; all `enemy.stagingArea.*` sites in tests with `enemyBehavior` set (2: lines 1525, 1679) → `enemyBehavior.stagingArea.*`; `enemy.minerals = 200` → `enemy.setMinerals(200)` |
| `EmulatedGameTest` (fog-of-war staging test) | Add `game.setEnemyStrategy(...)` before `game.addStagedUnitForTesting(...)` — `enemyBehavior` must be non-null |
| `EnemyBehaviorTest` | Unchanged — only touches game state (minerals, units, buildings) |
| `TerranEmulatedGameTest`, `ZergEmulatedGameTest` | API call updates matching the race model changes |

---

## Out of scope

- Making `ProtossRaceModel`, `TerranRaceModel`, `ZergRaceModel` public — internal SC2 implementations displaced by external plugins at #74.
- Moving `RaceModel` or `PlayerState` to a different package — `sc2/emulated/` is the correct home.
- Making `setPlayerRaceModel()` public — #74 owns the external plugin installation seam.
- API enforcement of `canProduce()` read-only contract — tracked in [#165](https://github.com/mdproctor/quarkmind/issues/165) for #74.
- Any changes to `EmulatedEngine`, `SC2Engine`, or the agent layer.

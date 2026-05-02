# Enemy Active AI — Design Spec
_2026-04-30 (revised: symmetric PlayerState / PlayerBehavior architecture)_

## Overview

Replace the current asymmetric `EmulatedGame` (friendly state scattered across fields, enemy state also scattered) with a symmetric two-player physics referee. Both players share the same `PlayerState` data shape and the same `IntentQueue` / `applyIntent()` code path. Enemy decision-making is a `PlayerBehavior` implementation — the same interface that `AgentOrchestrator` + plugins already satisfy on the friendly side.

This is an epic with three child issues:
- **Named strategies** — `EnemyStrategyLibrary`, `FixedBuildOrderStrategy`, `EnemyBehavior`, REST/random selection
- **Tech tree** — `TechTree` prerequisite graph, enemy building construction, new `BuildingType` entries
- **Reactive AI** — `ReactiveStrategy`, periodic re-evaluation, counter-pick table

---

## Core Abstraction: PlayerState + PlayerBehavior

### PlayerState

Extract all per-player mutable state from `EmulatedGame` into a plain class:

```java
class PlayerState {
    List<Unit>               units;
    List<Building>           buildings;
    List<Unit>               stagingArea;         // enemy units waiting to attack
    double                   minerals;
    int                      vespene;
    int                      supply;
    int                      supplyUsed;
    List<PendingCompletion>  pendingCompletions;
    Map<String, Point2d>     unitTargets;         // movement/attack targets by tag
    Set<String>              attackingUnits;      // tags currently in attack mode
    Map<String, Integer>     unitCooldowns;       // per-unit attack cooldown
    Map<String, Integer>     blinkCooldowns;      // per-Stalker blink cooldown
}
```

`EmulatedGame` holds two instances — `friendly` and `enemy` — and a shared `nextTag` counter (tags must be globally unique). All current scattered fields (`mineralAccumulator`, `myUnits`, `myBuildings`, `enemyUnits`, `enemyMineralAccumulator`, etc.) collapse into these two objects.

### PlayerBehavior

```java
interface PlayerBehavior {
    /**
     * Called once per tick. Implementations push Intents into the provided queue.
     * Friendly: AgentOrchestrator pushes asynchronously via the Quarkus scheduler —
     *           its adapter tick() is a no-op; the queue is filled externally.
     * Enemy:    EnemyBehavior pushes synchronously during EmulatedGame.tick().
     */
    void tick(GameState observation, IntentQueue queue);
}
```

`EmulatedGame` holds two `IntentQueue` instances — one per player — and drains both each tick via the shared `applyIntent(Intent, PlayerState)` method.

### Generalized applyIntent

```java
private void applyIntent(Intent intent, PlayerState state) {
    switch (intent) {
        case TrainIntent  t -> handleTrain(t, state);
        case BuildIntent  b -> handleBuild(b, state);
        case AttackIntent a -> setTarget(a.unitTag(), a.targetLocation(), true,  state);
        case MoveIntent   m -> setTarget(m.unitTag(), m.targetLocation(), false, state);
        case BlinkIntent  b -> executeBlink(b.unitTag(), state);
    }
}
```

Both players use the same logic. The enemy training a Zealot goes through the same `handleTrain()` path as the friendly AI training a Zealot, deducting from `enemy.minerals` and adding to `enemy.pendingCompletions`.

---

## EmulatedGame as Physics Referee

```
EmulatedGame
  ├── PlayerState    friendly
  ├── PlayerState    enemy
  ├── IntentQueue    friendlyQueue       ← filled by AgentOrchestrator (async)
  ├── IntentQueue    enemyQueue          ← filled by EnemyBehavior.tick() (sync)
  ├── MovementStrategy, TerrainGrid, VisibilityGrid  — unchanged
  └── tick()
        moveFriendlyUnits()
        visibility.recompute(...)
        moveEnemyUnits()
        resolveCombat(friendly.units, enemy.units)   ← symmetric, works for both
        tickRetreat()
        drainQueue(friendlyQueue, friendly)
        drainQueue(enemyQueue, enemy)
        fireCompletions(friendly)
        fireCompletions(enemy)
        enemyBehavior.tick(snapshot(enemy_perspective), enemyQueue)
```

Combat resolution is already symmetric — it just needs to use `friendly.units` and `enemy.units` from `PlayerState`. Retreat, completions, and supply tracking all follow the same pattern for both sides.

---

## EnemyBehavior

`EnemyBehavior implements PlayerBehavior`. Owns:
- Current `EnemyStrategy` (the active decision-making strategy)
- Retreat and attack tracking state (was in `EmulatedGame` — now here)
- Reference to `TechTree`

`EnemyBehavior(EnemyStrategy, PlayerState, TechTree)` — 3-arg constructor; all three injected at construction.

`tick()`:
1. Accumulate `enemy.minerals += strategy.mineralsPerTick()`
2. Check `strategy.shouldSwitch(obs)` — swap strategy if true
3. Ask `strategy.nextUnit(obs)` for what to train
4. If `TechTree.canTrain(unit, enemy.buildings)` → push `new TrainIntent(...)`
5. Else → push `new BuildIntent(nextRequired(...))` — queues the missing prereq; deduplicated via `pendingBuildings`
6. Check attack triggers → push `AttackIntent`s for staged units
7. Check per-unit and army-wide retreat thresholds → push `MoveIntent`s back to staging

`EnemyBehavior` at reset: picks a strategy from `EnemyStrategyLibrary` (random from race pool, or name-override from `EmulatedConfig`), places starting main structure in `enemy.buildings`.

---

## EnemyStrategy Interface

Produces intent descriptions — not raw mutations:

```java
interface EnemyStrategy {
    String name();
    Race race();
    int mineralsPerTick();
    EnemyAttackConfig attackConfig();
    Optional<UnitType> nextUnit(EnemyObservation obs);   // what to train next
    boolean shouldSwitch(EnemyObservation obs);
}

record EnemyObservation(
    List<Unit> playerUnits,
    Set<BuildingType> enemyBuildings,
    int minerals,
    long gameFrame
) {}
```

`EnemyBehavior` translates `nextUnit()` into a `TrainIntent` or `BuildIntent` after consulting `TechTree`. Strategies express intent; the behavior layer enforces constraints.

**`FixedBuildOrderStrategy`** — wraps a `List<UnitType>`, loops, `shouldSwitch()` always false.

**`ReactiveStrategy`** — re-evaluates every N frames (default 50), counts dominant player unit type, delegates `nextUnit()` to the selected counter strategy, `shouldSwitch()` returns true when switching.

`Race` is a new domain enum (`PROTOSS`, `ZERG`, `TERRAN`) added to `domain/`.

---

## TechTree

Pure domain class (`domain/`), no framework deps. Static prerequisite map covering all three races.

```java
public class TechTree {
    public boolean canTrain(UnitType unit, Set<BuildingType> built);
    public Optional<BuildingType> nextRequired(UnitType unit, Set<BuildingType> built);
}
```

Selected prerequisites (illustrative):

| Unit | Prerequisites |
|------|--------------|
| STALKER | GATEWAY, CYBERNETICS_CORE |
| IMMORTAL | ROBOTICS_FACILITY |
| COLOSSUS | ROBOTICS_FACILITY, ROBOTICS_BAY |
| ROACH | ROACH_WARREN |
| HYDRALISK | HYDRALISK_DEN |
| MUTALISK | SPIRE |
| MARINE | BARRACKS |
| MARAUDER | BARRACKS (TECH_LAB is an add-on, not a standalone BuildingType) |
| MEDIVAC | STARPORT |
| HELLION | FACTORY |

Enemy buildings are positioned in a cluster at the far corner of the map (tile ~`(50, 50)` with small offsets). The enemy starts with one free main structure per race at reset. Subsequent buildings go through `BuildIntent` → `handleBuild()` → `pendingCompletions` — the same path as friendly buildings.

`BuildingType` gains new enemy-only entries (ROACH_WARREN, BARRACKS, TECH_LAB, STARPORT, FACTORY, HYDRALISK_DEN, SPIRE, etc.) with mineral costs in `SC2Data`. Deduplication: `EnemyBehavior` only queues a building if it is neither already built nor pending.

`TechTree` is available to the friendly side too — `StrategyTask` or `EconomicsTask` can optionally consult it to validate build decisions.

---

## EnemyStrategyLibrary

Static registry. Enemy race (PROTOSS / ZERG / TERRAN) defaults to PROTOSS, configurable via `EmulatedConfig`. Strategy selected randomly from race pool at reset; REST API accepts a name override.

| Name | Race | Build order | Flavour |
|------|------|-------------|---------|
| `PROTOSS_4GATE` | Protoss | Zealot × 2 → Stalker × 4 | Fast aggression |
| `PROTOSS_BLINK_STALKER` | Protoss | Stalker mass | Tech-heavy |
| `PROTOSS_COLOSSUS_PUSH` | Protoss | Zealot → Immortal → Colossus | Slow deathball |
| `ZERG_ROACH_RUSH` | Zerg | Roach × 6 | Early pressure |
| `ZERG_MASS_LING` | Zerg | Zergling × 8 | Speed flood |
| `ZERG_HYDRA_SWITCH` | Zerg | Zergling × 4 → Hydralisk × 4 | Tech switch |
| `TERRAN_2RAX_MARINE` | Terran | Marine × 8 | Bio flood |
| `TERRAN_BIO_PUSH` | Terran | Marine × 4 → Marauder × 2 → Medivac × 1 | Standard bio |
| `TERRAN_MECH` | Terran | Hellion × 2 → Siege Tank × 2 | Slow push |
| `REACTIVE` | Any | ReactiveStrategy | Counter-pick |

ReactiveStrategy counter table:

| Player dominant unit | Counter strategy |
|----------------------|-----------------|
| Ranged (STALKER, MARINE) | random between PROTOSS_COLOSSUS_PUSH and ZERG_ROACH_RUSH |
| Melee mass (ZEALOT, ZERGLING) | TERRAN_BIO_PUSH |
| Armoured (IMMORTAL, SIEGE_TANK) | ZERG_MASS_LING |
| Mixed / unknown | random from pool |

---

## Testing

**Unit tests (no Quarkus):**
- `PlayerStateTest` — minerals deduct correctly, supply caps training, pendingCompletions fire at right tick
- `TechTreeTest` — `canTrain()` false when prereq missing; `nextRequired()` returns first missing building
- `FixedBuildOrderStrategyTest` — loops through build order correctly
- `ReactiveStrategyTest` — switches at re-evaluation interval; correct counter per dominant unit

**`EmulatedGameTest` (existing, minimal changes):**
- `applyIntent()` tests unchanged — same intents, same outcomes
- Combat tests unchanged — just read from `PlayerState` instead of direct fields
- Test helpers (`setEnemyStrategy`, `spawnEnemyForTesting`, etc.) updated to work via `EnemyBehavior`

**`EnemyBehaviorTest` (new unit tests):**
- Pushes `BuildIntent` when tech tree prereq missing; does not re-queue if already pending
- Pushes `TrainIntent` when prereq met and minerals sufficient
- Launches attack when army threshold or timer fires
- Switches strategy mid-game when reactive re-evaluation fires

---

## Child Issue Order

1. **Named enemy strategies** — `PlayerState`, `PlayerBehavior`, `EnemyBehavior` skeleton, `EnemyStrategyLibrary`, `FixedBuildOrderStrategy`, `applyIntent(Intent, PlayerState)` generalization, REST/random selection. No tech tree yet (enemy trains freely, no prereqs checked).
2. **Enemy tech tree** — `TechTree`, new `BuildingType` entries, enemy building construction + positioning, tech gating in `EnemyBehavior.tick()`.
3. **Reactive enemy AI** — `ReactiveStrategy`, `EnemyObservation`, counter-pick table, periodic re-evaluation.

---

## Design Note: Path to CaseHub-Driven Enemy

`PlayerBehavior` is the upgrade seam. A CaseHub-driven enemy would implement `PlayerBehavior`, receive a `GameState` from the enemy's perspective, and push the same `Intent` types. The physics referee (`EmulatedGame`) is unaware of which behavior drives which player — the symmetric design makes this a drop-in replacement rather than a rearchitecture.

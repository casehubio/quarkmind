# Design: Terran and Zerg EmulatedGame Mechanics

**Date:** 2026-05-30  
**Issue:** #138  
**Branch:** issue-138-terran-zerg-mechanics

---

## Problem

`EmulatedGame` is hardcoded to Protoss in three places:

1. `reset()` — seeds 12 Probes, a NEXUS, and Protoss supply/resource constants
2. `countProbesPerBase()` — filters on `UnitType.PROBE` and `BuildingType.NEXUS`
3. `tick()` income — calls `countProbesPerBase` and `SC2Data.mineralIncomePerTick`

`SC2Data` has incomplete Terran and Zerg unit stats (supplyCost, mineralCost, gasCost, trainTimeInLoops, maxHealth, damage, attributes). No larva system, no MULE mechanic, no Overlord supply, no Queen energy or inject.

Additionally, the platform vision (#74) is to make races pluggable: SC2 races become plugins, not baked-in code. This issue establishes the seam that makes that possible.

---

## Decision: RaceModel plugin seam (Approach B)

`EmulatedGame` delegates all race-specific behaviour through a `RaceModel` interface. Three SC2 implementations (`ProtossRaceModel`, `TerranRaceModel`, `ZergRaceModel`) live in the same package. When #74 lands, SC2 implementations can be replaced with YAML-backed plugins without changing `EmulatedGame`.

**Why not Approach A (race field + switch branches in EmulatedGame):** EmulatedGame is already large. Mixing Protoss + Terran + Zerg initialisation, larva regen, MULE expiry, and Queen inject inline would make it significantly harder to reason about. Each race model is independently readable and testable.

**Package-private boundary:** `RaceModel` and its implementations live in `sc2/emulated/` — same package as `PlayerState` (package-private). Implementations get direct read/write access to `PlayerState` fields. When #74 arrives and race plugins become external contributors, `PlayerState` will need a public mutator API; file a follow-up issue to track that debt (filed as #164 during this session).

---

## Interface

```java
interface RaceModel {
    void seedInitialState(PlayerState state, List<Resource> geysers);
    void tickPassive(PlayerState state, long gameLoop);
    boolean consumeProductionResource(PlayerState state, String buildingTag,
                                      UnitType unitType, Supplier<String> tagSupplier);
    void onUnitSpawned(PlayerState state, UnitType type, String unitTag, String buildingTag);
    UnitType workerType();
    Set<BuildingType> townHallTypes();
}
```

| Method | Called by | Purpose |
|--------|-----------|---------|
| `seedInitialState` | `reset()` | Seeds units, buildings, resources, supply |
| `tickPassive` | `tick()` after income | Larva regen, MULE expiry+income, Queen energy |
| `consumeProductionResource` | `handleTrain()` before queue | Larva check (Zerg), MULE short-circuit, always true otherwise. `tagSupplier` provides next tag from EmulatedGame (for EGG / MULE spawn); `unitType` identifies MULE path in TerranRaceModel |
| `onUnitSpawned` | `startTraining()` on completion | Overlord supply, EGG removal (Zerg). `buildingTag` identifies which EGG to remove |
| `workerType` | `countWorkersPerBase()` | PROBE / SCV / DRONE |
| `townHallTypes` | `countWorkersPerBase()` | Town hall building types per race |

**Worker income is race-invariant.** All three worker types mine 5 minerals per trip on the same cycle — no difference between SCV, Drone, and Probe (confirmed from SC2 mechanics research). `SC2Data.mineralIncomePerTick()` stays shared; `workerIncomePerTick` is not on the interface. MULE income is a separate flat bonus in `TerranRaceModel.tickPassive()`.

---

## SC2Data additions

All changes are new `case` entries in existing switch methods — no new methods except `trainCount` and `muleIncomePerTick`.

### New method: `trainCount(UnitType)`

```java
public static int trainCount(UnitType type) {
    return type == UnitType.ZERGLING ? 2 : 1;
}
```

One `TrainIntent(hatcheryTag, ZERGLING)` produces 2 Zerglings for 1 supply and 25 minerals. `handleTrain` deducts resources once; `startTraining` spawns `trainCount` units on completion.

### New method: `muleIncomePerTick()`

MULE mines at ~3.45× SCV rate. Flat income per active MULE per tick, derived from: 25 minerals/trip × 3.45 trips/min equivalent ÷ (60s/min ÷ seconds-per-tick). Stored as a constant in SC2Data.

### New constant: `MULE_LIFETIME_LOOPS = 1430` (64s × 22.4)

### New `UnitType` entries: `EGG`

`LARVA` is a counter in `ZergRaceModel` — not a unit entity. `EGG` is a unit entity spawned when a larva morphs; visible in the snapshot until it hatches.

### New `SC2Data` entries (partial list — complete in implementation)

| Method | New entries |
|--------|-------------|
| `trainTimeInLoops` | Terran: SCV(275 est.), Marine(563 est.), Marauder(757 est.); Zerg: Drone(275 est.), Zergling(400 est.), Roach(572 est.), Hydralisk(672 est.), Overlord(357 est.), Queen(900 est.) — all marked uncalibrated pending Terran replays from #140 |
| `supplyCost` | SCV(1), Marine(1), Marauder(2), Drone(1), Zergling(1—for the pair), Roach(2), Hydralisk(2), Overlord(0), Queen(2), MULE(0) |
| `mineralCost(UnitType)` | SCV(50), Marine(50), Marauder(100), Drone(50), Zergling(25), Roach(75), Hydralisk(100), Overlord(100), Queen(150), MULE(0) |
| `gasCost` | Marauder(25), Roach(25), Hydralisk(50), Queen(0), MULE(0) |
| `maxHealth` | SCV(45), Drone(40), Zergling(35), Overlord(200), Queen(175), MULE(60), EGG(200) |
| `damagePerAttack` | SCV(5), Zergling(5), Queen(20), MULE(0) |
| `attackCooldownInTicks` | SCV(2), Zergling(1), Queen(3) |
| `attackRange` | SCV(0.5f), Zergling(0.5f), Queen(5.0f), MULE(0f) |
| `armour` | SCV(1), Queen(1), Drone(0), Zergling(0), MULE(0) |
| `unitAttributes` | SCV: LIGHT+MECHANICAL+BIOLOGICAL; Marine: LIGHT+BIOLOGICAL; Marauder: BIOLOGICAL+ARMORED; Drone: LIGHT+BIOLOGICAL; Zergling: LIGHT+BIOLOGICAL; Roach: ARMORED+BIOLOGICAL; Hydralisk: LIGHT+BIOLOGICAL; Overlord: ARMORED+BIOLOGICAL+MASSIVE; Queen: BIOLOGICAL |
| `trainedBy` | add `MULE → ORBITAL_COMMAND` |
| `sightRange` | SCV(8), Drone(8), Overlord(11), Zergling(8), Queen(12) |

---

## EmulatedGame changes

### New field and setter

```java
private RaceModel playerRaceModel = new ProtossRaceModel();

void setPlayerRaceModel(RaceModel model) { this.playerRaceModel = model; }
```

Default is `ProtossRaceModel` — all existing tests pass without change.

### `reset()` — Protoss seed block replaced

```java
// Before:
friendly.minerals = SC2Data.INITIAL_MINERALS;
// ... 12 Probes, NEXUS, supply constants ...

// After:
playerRaceModel.seedInitialState(friendly, geysers);
miningProbesPerBase = new int[]{countWorkersPerBase(...)[0]};  // or default
```

The `geysers` list is cleared before calling `seedInitialState` — each model repopulates it.

### `countProbesPerBase()` renamed and generalised

```java
static int[] countWorkersPerBase(RaceModel model, List<Building> buildings, List<Unit> units)
```

`UnitType.PROBE` → `model.workerType()`. `BuildingType.NEXUS` filter → `model.townHallTypes()`. All call sites (two: `tick()` and public `countProbesPerBase` used by harness) updated.

The harness-facing public method becomes `countWorkersPerBase(List<Building>, List<Unit>)` (no model parameter — uses instance field) or is kept with updated name for the harness. Harness callers in `ReplayValidationHarness` pass race-appropriate worker/townhall types; this is addressed when the harness is extended for Terran/Zerg replays.

### `tick()` additions

```java
// After income loop:
playerRaceModel.tickPassive(friendly, gameFrame * SC2Data.LOOPS_PER_TICK);
```

### `handleTrain()` change

```java
// Before resource checks:
if (!playerRaceModel.consumeProductionResource(state, buildingTag)) return;
```

For Zerg this decrements larva and spawns an EGG. For MULE this spawns the unit and returns `false`, short-circuiting the rest of `handleTrain`. For all other Protoss/Terran units, returns `true` immediately.

### `startTraining()` — Zergling multi-spawn and EGG removal

On completion lambda:
```java
int count = SC2Data.trainCount(unitType);
for (int i = 0; i < count; i++) {
    String tag = "unit-" + nextTag++;
    int hp = SC2Data.maxHealth(unitType);
    state.units.add(new Unit(tag, unitType,
        new Point2d(9 + i * 0.5f, 9), hp, hp,
        SC2Data.maxShields(unitType), SC2Data.maxShields(unitType), 0, 0));
    playerRaceModel.onUnitSpawned(state, unitType, tag);
}
```

`ZergRaceModel.onUnitSpawned` removes the EGG for this building from `state.units` on the first call (i=0).

---

## ProtossRaceModel

Lifts existing hardcoded logic from `reset()` verbatim. `tickPassive`, `consumeProductionResource`, `onUnitSpawned` are no-ops. `workerType()` = PROBE. `townHallTypes()` = {NEXUS}.

---

## TerranRaceModel

**Fields:**
```java
private final Map<String, Long> muleExpiresAtLoop = new HashMap<>();
```

**`seedInitialState`:** 12 SCVs at staggered positions, COMMAND_CENTER at (8,8), minerals=50, vespene=0, supply=15, supplyUsed=12. Two geysers.

**`tickPassive(state, gameLoop)`:**
- Remove expired MULEs: iterate `muleExpiresAtLoop`, for entries where `gameLoop >= expiresAt`: remove MULE unit from `state.units`, remove entry.
- Add MULE income: `state.minerals += muleExpiresAtLoop.size() * SC2Data.muleIncomePerTick()`

**`consumeProductionResource(state, buildingTag)`:**
- If `building.type() == ORBITAL_COMMAND` and `unitType == MULE` (ZergRaceModel needs to know unit type — thread the unit type through): spawn MULE unit at OC position, register expiry `muleExpiresAtLoop.put(muleTag, gameLoop + MULE_LIFETIME_LOOPS)`, return `false` to short-circuit `handleTrain`.
- All other units: return `true`.

*Note: `consumeProductionResource` currently only takes `buildingTag` — needs `unitType` threaded through to support the MULE short-circuit. Signature update: `boolean consumeProductionResource(PlayerState state, String buildingTag, UnitType unitType)`.*

**`onUnitSpawned`:** no-op.

**`workerType()`:** SCV. **`townHallTypes()`:** {COMMAND_CENTER, ORBITAL_COMMAND, PLANETARY_FORTRESS}.

---

## ZergRaceModel

**Fields:**
```java
private final Map<String, Integer> hatcheryLarvaCount    = new HashMap<>();
private final Map<String, Long>    hatcheryNextLarvaLoop = new HashMap<>();
private final Map<String, String>  eggTagByBuilding      = new HashMap<>();
private final Map<String, Integer> queenEnergy           = new HashMap<>();
```

**`seedInitialState(state, geysers)`:** 12 Drones, HATCHERY at (8,8), OVERLORD at (14,14), minerals=50, vespene=0, supply=14 (HATCHERY=6 + OVERLORD=8), supplyUsed=12. Seed `hatcheryLarvaCount` with 3 for the initial hatchery. Two geysers.

**`tickPassive(state, gameLoop)`:**

*Larva regen:* for each complete HATCHERY/LAIR/HIVE in `state.buildings`: if `gameLoop >= hatcheryNextLarvaLoop.getOrDefault(tag, 0L)` and `hatcheryLarvaCount.getOrDefault(tag, 0) < 3`: increment count, set `hatcheryNextLarvaLoop[tag] = gameLoop + 245`.

*Queen energy regen:* for each QUEEN in `state.units`: increment `queenEnergy[tag]` by `SC2Data.QUEEN_ENERGY_REGEN_PER_LOOP * SC2Data.LOOPS_PER_TICK` (≈ 0.62 per tick at 22 loops/tick), cap at 200.

*Queen inject:* for each QUEEN with `queenEnergy[tag] >= 25`: find nearest HATCHERY/LAIR/HIVE in `state.buildings` (by distance to queen position), add 4 to `hatcheryLarvaCount` for that hatchery (cap at 19 — SC2 maximum injectable larva), deduct 25 energy.

**`consumeProductionResource(state, buildingTag, unitType)`:**
- Check `hatcheryLarvaCount.getOrDefault(buildingTag, 0) > 0`. If not: return `false`.
- Decrement larva count.
- Spawn EGG unit at hatchery position: `String eggTag = "egg-" + nextTag++; state.units.add(new Unit(eggTag, UnitType.EGG, hatcheryPos, SC2Data.maxHealth(EGG), ...))`.
- Store `eggTagByBuilding.put(buildingTag, eggTag)`.
- Return `true`.

**`onUnitSpawned(state, type, tag)`:**
- Remove EGG: look up `eggTagByBuilding` to find the EGG tag for this building (keyed via a reverse map maintained at spawn time), remove from `state.units`.
- If `type == OVERLORD`: `state.supply += 8`. Register new entry in `queenEnergy` if `type == QUEEN` (initial energy = 25, first inject is available immediately).

**`workerType()`:** DRONE. **`townHallTypes()`:** {HATCHERY, LAIR, HIVE}.

**`reset()`:** clear all fields (called by `EmulatedGame.reset()` via `seedInitialState` — the model should clear its own state at the start of `seedInitialState`).

---

## Testing strategy

All tests are plain JUnit — no `@QuarkusTest`.

**Regression guard first:** `mvn test -Dtest=EmulatedGameTest -q` must pass before any new code is written. Default `ProtossRaceModel` ensures existing suite is unaffected.

**`TerranEmulatedGameTest`** (new):
- `initialState_terran` — 12 SCVs, CC, supply=15, supplyUsed=12, minerals=50
- `mineralIncome_terran` — same saturation rate as Protoss (race-invariant)
- `trainSCV_deductsMinerals` — 50 minerals
- `trainMarine_requiresBarracks` — rejected without Barracks
- `trainMarauder_deductsGas` — 25 gas
- `muleSpawn_addsIncomePerTick` — MULE appears in snapshot, minerals rise at boosted rate
- `muleExpires_afterLifetime` — after 65 ticks, MULE gone, income normalises
- `orbitalCommandTownHall_countsForWorkerAssignment` — SCV near OC counted for income

**`ZergEmulatedGameTest`** (new):
- `initialState_zerg` — 12 Drones, HATCHERY, OVERLORD, supply=14, minerals=50, 3 larvae
- `mineralIncome_zerg` — same rate as Protoss
- `larvaRegeneration` — hatchery below 3 gains 1 per 11 ticks
- `larvaCapAt3` — no regen beyond 3
- `trainZergling_consumesLarva_spawnsEgg` — larva decrements, EGG in snapshot
- `eggHatches_spawnsTwoZerglings` — after morph time, EGG removed, 2 Zerglings present
- `trainRoach_noLarva_rejected` — rejected when larvaCount == 0
- `trainOverlord_addsSupply` — supply += 8 on spawn
- `queenEnergyRegens_perTick` — Queen energy increases each tick
- `queenInject_consumesEnergy_addsLarva` — 25 energy → 4 larvae added
- `injectLarvaCap_at19` — larvae capped at 19
- `hatcheryLair_hive_countsForWorkerAssignment` — Drone near LAIR/HIVE earns income
- `multipleHatcheries_separateLarvaCounters` — independent per-hatchery state

**SC2Data:** add `SC2DataTest` checks for `trainCount(ZERGLING)==2`, `trainCount(MARINE)==1`.

---

## Open questions / deferred

- **`consumeProductionResource` and `onUnitSpawned` signatures** — resolved in the interface definition above: `consumeProductionResource` takes `unitType` (MULE path) and `tagSupplier` (EGG/MULE tag generation, keeps tag ownership in EmulatedGame); `onUnitSpawned` takes `buildingTag` so ZergRaceModel can remove the right EGG via `eggTagByBuilding`.
- **Harness compatibility** — `countProbesPerBase` is called publicly by `ReplayValidationHarness`. Rename + Terran/Zerg harness support is deferred to the replay validation issue.
- **`PlayerState` public API for #74** — when race plugins become external, `PlayerState` needs a public mutator surface. Filed as follow-up issue #164.
- **Terran train time calibration** — all Terran unit `trainTimeInLoops` values are estimates until Terran replays are acquired in #140.

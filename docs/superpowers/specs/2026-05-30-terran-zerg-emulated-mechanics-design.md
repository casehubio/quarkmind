# Design: Terran and Zerg EmulatedGame Mechanics

**Date:** 2026-05-30  
**Issue:** #138  
**Branch:** issue-138-terran-zerg-mechanics  
**Revision:** 2 (post spec-review)

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

**Package-private boundary:** `RaceModel` and its implementations live in `sc2/emulated/` — same package as `PlayerState` (package-private). Implementations get direct read/write access to `PlayerState` fields. When #74 arrives and race plugins become external contributors, `PlayerState` will need a public mutator API. Tracked in #164.

---

## Interface

```java
interface RaceModel {
    void seedInitialState(PlayerState state, List<Resource> geysers);
    void tickPassive(PlayerState state, long gameLoop);
    ProductionResult canProduce(PlayerState state, String buildingTag, UnitType unitType);
    void onProductionCommitted(PlayerState state, String buildingTag,
                               UnitType unitType, Supplier<String> tagSupplier);
    void onUnitSpawned(PlayerState state, UnitType type,
                       String unitTag, String buildingTag);
    default int trainCount(UnitType type) { return 1; }
    UnitType workerType();
    Set<BuildingType> townHallTypes();
}

enum ProductionResult { PROCEED, HANDLED, BLOCKED }
```

| Method | Called by | Purpose |
|--------|-----------|---------|
| `seedInitialState` | `reset()` | Seeds units, buildings, resources, supply |
| `tickPassive` | `tick()` after income | Larva regen, MULE expiry+income, Queen energy |
| `canProduce` | `handleTrain()` after building check, **before** resource deduction | Check larva (Zerg), identify MULE short-circuit (Terran). Returns PROCEED (resources not yet checked — proceed to deduct), HANDLED (MULE — resources already managed, exit handleTrain), or BLOCKED (no larva — abort, no resource touch) |
| `onProductionCommitted` | `handleTrain()` after resource deduction | Consume larva, spawn EGG (Zerg). Never called if `canProduce` returned HANDLED or BLOCKED |
| `onUnitSpawned` | `startTraining()` on completion | Overlord supply, EGG removal (Zerg). `buildingTag` is lambda-captured in `startTraining` and passed through |
| `trainCount` | `startTraining()` | 2 for ZERGLING, 1 for all others. Default method; only ZergRaceModel overrides |
| `workerType` | `countWorkersPerBase()` | PROBE / SCV / DRONE |
| `townHallTypes` | `countWorkersPerBase()` | Town hall building types per race |

**Why `canProduce` / `onProductionCommitted` instead of one method:**
The original `consumeProductionResource` spec had larva consumption happen before the resource check. If minerals were insufficient, the larva was already gone and a stranded EGG existed in `state.units`. Splitting into two explicit phases — `canProduce` (query only, no state mutation) followed by `onProductionCommitted` (mutate after resources are confirmed) — eliminates the ordering hazard entirely. The `ProductionResult` enum also makes the MULE short-circuit explicit (HANDLED) rather than a surprising `false` return.

**Why `trainCount` on `RaceModel`, not `SC2Data`:** Zergling's dual-spawn is race-specific production logic, not unit physics. Placing it in the domain-layer `SC2Data` would couple the domain to race production rules and make #74 harder — a plugin cannot override what's in a static class. The default method on `RaceModel` is the correct owner.

**Worker income is race-invariant.** All three worker types mine 5 minerals per trip on the same cycle — confirmed from SC2 mechanics research (Liquipedia). `SC2Data.mineralIncomePerTick()` stays shared; `workerIncomePerTick` is not on the interface. MULE income is a separate flat bonus in `TerranRaceModel.tickPassive()`.

---

## SC2Data additions

All changes are new `case` entries in existing switch methods, plus three new items.

### New method: `muleIncomePerTick()`

MULE mines at ~3.45× SCV rate (confirmed from SC2 research). Derivation:
- SCV earns ≈50 minerals/min → MULE earns ≈172.5 minerals/min  
- Per second: 172.5 / 60 ≈ 2.875 minerals/sec  
- Per tick (22 loops at 22.4 loops/sec = 0.982s): 2.875 × 0.982 ≈ 2.82 minerals/tick  
- Round to 2.8 as a calibration estimate; mark uncalibrated pending Terran replay data from #140.

### New constant: `MULE_LIFETIME_LOOPS = 1434`

Derivation: 64s × 22.4 loops/s = 1433.6 → ceiling = 1434. Marked uncalibrated.

### New constant: `QUEEN_ENERGY_REGEN_PER_LOOP`

Standard SC2 spellcaster energy regen: 0.5625 energy/sec at Faster speed.  
Per loop: 0.5625 / 22.4 ≈ 0.02511 energy/loop.  
Per tick: 0.02511 × 22 ≈ 0.552 energy/tick.  
Stored as `QUEEN_ENERGY_REGEN_PER_LOOP = 0.02511` — `tickPassive` multiplies by `LOOPS_PER_TICK`.

### New `UnitType` entry: `EGG`

`LARVA` is a counter in `ZergRaceModel` — not a unit entity (simplification; see Known Divergences). `EGG` is a unit entity spawned when a larva morphs; visible in the snapshot until it hatches.

**EGG in divergence reports:** `EGG` units in `state.units` inflate the emulated unit count relative to replay ground truth (which doesn't expose eggs as regular units). EGGs must be filtered from unit-count comparisons in `ReplayValidationHarness`. Recommendation: filter `UnitType.EGG` from the count, or track eggs in a separate `state.eggs` collection. Addressed when Zerg harness support is implemented.

### New `SC2Data` entries

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
| `trainedBy` | add `MULE → ORBITAL_COMMAND`, `QUEEN → HATCHERY` |
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
geysers.clear();
playerRaceModel.seedInitialState(friendly, geysers);
miningProbesPerBase = countWorkersPerBase(friendly.buildings, friendly.units);
```

### `countProbesPerBase()` renamed to `countWorkersPerBase()`

```java
static int[] countWorkersPerBase(RaceModel model, List<Building> buildings, List<Unit> units)
```

`UnitType.PROBE` → `model.workerType()`. `BuildingType.NEXUS` filter → `model.townHallTypes()`.

The instance method variant used internally in `tick()` uses `playerRaceModel`:

```java
miningProbesPerBase = countWorkersPerBase(playerRaceModel, friendly.buildings, friendly.units);
```

**Harness compatibility — must update in same commit:** `ReplayValidationHarness:142` calls `EmulatedGame.countProbesPerBase(state.myBuildings(), state.myUnits())`. This call site must be updated to `EmulatedGame.countWorkersPerBase(protossModel, state.myBuildings(), state.myUnits())` (passing a static `ProtossRaceModel` instance, since the harness currently only supports Protoss replays). `ReplayValidationHarnessTest` has four tests on `ReplayValidationHarness.countProbesPerBase` — rename and update those too.

### `tick()` additions

```java
// After income loop:
playerRaceModel.tickPassive(friendly, gameFrame * (long) SC2Data.LOOPS_PER_TICK);
```

### `handleTrain()` — two-phase production check

```java
private void handleTrain(TrainIntent t, PlayerState state, long absLoop) {
    // 1. Building validation (unchanged)
    Building building = ...findBuilding...;
    if (building == null) return;
    BuildingType required = SC2Data.trainedBy(t.unitType());
    if (required != BuildingType.UNKNOWN && building.type() != required) return;

    // 2. canProduce — query only, no state mutation
    ProductionResult pr = playerRaceModel.canProduce(state, buildingTag, t.unitType());
    if (pr == ProductionResult.BLOCKED) return;
    if (pr == ProductionResult.HANDLED) return;  // MULE: model already spawned it

    // 3. Resource check (unchanged)
    int mCost = SC2Data.mineralCost(t.unitType());
    int gCost = SC2Data.gasCost(t.unitType());
    int sCost = SC2Data.supplyCost(t.unitType());
    if ((int) state.minerals < mCost || state.vespene < gCost
            || state.supplyUsed + sCost > state.supply) return;

    // 4. Resource deduction (unchanged)
    state.supplyUsed += sCost;
    state.minerals   -= mCost;
    state.vespene    -= gCost;

    // 5. onProductionCommitted — larva consume + EGG spawn (Zerg)
    playerRaceModel.onProductionCommitted(state, buildingTag, t.unitType(), this::nextTagString);

    // 6. Queue / start training (unchanged)
    if (!isBusy) startTraining(buildingTag, t.unitType(), state, absLoop);
    else         state.buildingQueues...add(t.unitType());
}
```

Where `nextTagString()` is a private helper: `() -> "unit-" + nextTag++`.

### `startTraining()` — multi-spawn and `onUnitSpawned`

```java
private void startTraining(String buildingTag, UnitType unitType, PlayerState state, long absLoop) {
    // ...existing timing + pending completion setup...
    state.pendingCompletions.add(new PlayerState.PendingCompletion(completesAt, () -> {
        state.buildingTrainingUntil.remove(buildingTag);
        int count = playerRaceModel.trainCount(unitType);
        for (int i = 0; i < count; i++) {
            String tag = nextTagString();
            int hp = SC2Data.maxHealth(unitType);
            state.units.add(new Unit(tag, unitType,
                new Point2d(9 + i * 0.5f, 9), hp, hp,
                SC2Data.maxShields(unitType), SC2Data.maxShields(unitType), 0, 0));
            playerRaceModel.onUnitSpawned(state, unitType, tag, buildingTag);
        }
        // ... existing enemy behavior notification ...
    }));
}
```

---

## ProtossRaceModel

Lifts existing hardcoded logic from `reset()` verbatim.

- `seedInitialState`: 12 Probes, NEXUS at (8,8), minerals=50, vespene=0, supply=15, supplyUsed=12. Two geysers at (5,11) and (11,5). Supply seeded directly — bypass of `supplyBonus()` is intentional; seeding is not normal game flow (consistent with original reset()).
- `tickPassive`: no-op
- `canProduce`: always returns PROCEED
- `onProductionCommitted`: no-op
- `onUnitSpawned`: no-op
- `trainCount`: default (1)
- `workerType()`: PROBE
- `townHallTypes()`: {NEXUS}

---

## TerranRaceModel

**Fields:**
```java
private final Map<String, Long> muleExpiresAtLoop = new HashMap<>();
```

**`seedInitialState`:** 12 SCVs at staggered positions, COMMAND_CENTER at (8,8), minerals=50, vespene=0, supply=15, supplyUsed=12. Two geysers. Supply seeded directly — same intentional bypass as ProtossRaceModel.

**`tickPassive(state, gameLoop)`:**
```java
// Collect expired MULE tags first — avoid ConcurrentModificationException
List<String> expired = new ArrayList<>();
muleExpiresAtLoop.forEach((tag, expiresAt) -> {
    if (gameLoop >= expiresAt) expired.add(tag);
});
expired.forEach(tag -> {
    muleExpiresAtLoop.remove(tag);
    state.units.removeIf(u -> u.tag().equals(tag));
});
// Add income for remaining active MULEs
state.minerals += muleExpiresAtLoop.size() * SC2Data.muleIncomePerTick();
```

**`canProduce(state, buildingTag, unitType)`:**
- If `unitType == MULE`: spawn MULE unit at OC position, register `muleExpiresAtLoop.put(muleTag, currentGameLoop + SC2Data.MULE_LIFETIME_LOOPS)`, return `HANDLED` (short-circuits handleTrain — no resource deduction, no queue).
- *Note: `canProduce` needs access to `currentGameLoop`. Thread it via a constructor field or store gameLoop in tickPassive.*
- All other units: return `PROCEED`.

**`onProductionCommitted`:** no-op.  
**`onUnitSpawned`:** no-op.  
**`workerType()`:** SCV.  
**`townHallTypes()`:** {COMMAND_CENTER, ORBITAL_COMMAND, PLANETARY_FORTRESS}.

**gameLoop threading for MULE:** `TerranRaceModel` stores `long currentGameLoop` updated in `tickPassive`. `canProduce` reads it. This avoids threading gameLoop into the interface.

---

## ZergRaceModel

**Fields:**
```java
private final Map<String, Integer> hatcheryLarvaCount    = new HashMap<>();
private final Map<String, Long>    hatcheryNextLarvaLoop = new HashMap<>();
private final Map<String, String>  eggTagByBuilding      = new HashMap<>();
private final Map<String, Double>  queenEnergy           = new HashMap<>();
private long currentGameLoop;
```

**`seedInitialState(state, geysers)`:**  
Clear all fields first (guard against reset after a previous game).  
Seed: 12 Drones, HATCHERY at (8,8) (complete), OVERLORD unit at (14,14), minerals=50, vespene=0, supply=14 (direct assignment — intentional bypass; Hatchery 6 + Overlord 8 = 14, consistent with ProtossRaceModel's supply=15 direct seed), supplyUsed=12. Seed `hatcheryLarvaCount.put(hatcheryTag, 3)`. Two geysers.

**`tickPassive(state, gameLoop)`:**

Store `currentGameLoop = gameLoop`.

*Larva regen:* for each complete HATCHERY/LAIR/HIVE building in `state.buildings`:
```java
if (gameLoop >= hatcheryNextLarvaLoop.getOrDefault(tag, 0L)
        && hatcheryLarvaCount.getOrDefault(tag, 0) < 3) {
    hatcheryLarvaCount.merge(tag, 1, Integer::sum);
    hatcheryNextLarvaLoop.put(tag, gameLoop + 245L);
}
```

*Queen energy regen:* for each QUEEN in `state.units`:
```java
double energy = queenEnergy.getOrDefault(tag, 25.0);
energy = Math.min(200.0, energy + SC2Data.QUEEN_ENERGY_REGEN_PER_LOOP * SC2Data.LOOPS_PER_TICK);
queenEnergy.put(tag, energy);
```

*Queen inject:* for each QUEEN with energy ≥ 25: find nearest complete HATCHERY/LAIR/HIVE in `state.buildings` by distance to Queen position. Add 4 larvae (cap per-hatchery at 19). Deduct 25 energy.

**`canProduce(state, buildingTag, unitType)`:**
```java
if (hatcheryLarvaCount.getOrDefault(buildingTag, 0) > 0) return ProductionResult.PROCEED;
return ProductionResult.BLOCKED;
```
No state mutation. EGG spawn happens in `onProductionCommitted`.

**`onProductionCommitted(state, buildingTag, unitType, tagSupplier)`:**
```java
hatcheryLarvaCount.merge(buildingTag, -1, Integer::sum);
String eggTag = tagSupplier.get();
int eggHp = SC2Data.maxHealth(UnitType.EGG);
Building hatchery = state.buildings.stream()
    .filter(b -> b.tag().equals(buildingTag)).findFirst().orElseThrow();
state.units.add(new Unit(eggTag, UnitType.EGG, hatchery.position(),
    eggHp, eggHp, 0, 0, 0, 0));
eggTagByBuilding.put(buildingTag, eggTag);
```

**`onUnitSpawned(state, type, unitTag, buildingTag)`:**
```java
// Remove EGG (first spawned unit from this building removes the egg)
String eggTag = eggTagByBuilding.remove(buildingTag);
if (eggTag != null) state.units.removeIf(u -> u.tag().equals(eggTag));
// Overlord supply
if (type == UnitType.OVERLORD) state.supply += 8;
// Register new Queen
if (type == UnitType.QUEEN) queenEnergy.put(unitTag, 25.0);
```

**`trainCount(ZERGLING)`:** returns 2. All others: default 1.  
**`workerType()`:** DRONE. **`townHallTypes()`:** {HATCHERY, LAIR, HIVE}.

---

## Testing strategy

All tests are plain JUnit — no `@QuarkusTest`.

**Regression guard first:** `mvn test -Dtest=EmulatedGameTest -q` must pass before any new code is written. Default `ProtossRaceModel` and renamed `countWorkersPerBase` ensure existing suite is unaffected (apart from the rename itself, which is mechanical).

**`TerranEmulatedGameTest`** (new):
- `initialState_terran` — 12 SCVs, CC, supply=15, supplyUsed=12, minerals=50
- `mineralIncome_terran` — same saturation rate as Protoss (race-invariant)
- `trainSCV_deductsMinerals` — 50 minerals
- `trainMarine_requiresBarracks` — rejected without Barracks
- `trainMarauder_deductsGas` — 25 gas
- `muleSpawn_addsIncomePerTick` — MULE appears in snapshot, minerals rise at boosted rate
- `muleExpires_afterLifetime` — after 65 ticks, MULE unit gone, income normalises
- `orbitalCommandTownHall_countsForWorkerAssignment` — SCV near OC counted for income

**`ZergEmulatedGameTest`** (new):
- `initialState_zerg` — 12 Drones, HATCHERY, OVERLORD, supply=14, minerals=50, 3 larvae
- `mineralIncome_zerg` — same rate as Protoss
- `larvaRegeneration` — hatchery below 3 gains 1 per 11 ticks
- `larvaCapAt3` — no regen beyond 3
- `trainZergling_consumesLarva_spawnsEgg` — larva decrements, EGG in snapshot
- `eggHatches_spawnsTwoZerglings` — after morph time, EGG removed, 2 Zerglings present
- `trainRoach_noLarva_rejected_noResourceDeducted` — rejected when larvaCount == 0; **must also assert minerals, supply, and vespene are unchanged** (guard against ordering regression)
- `trainOverlord_addsSupply` — supply += 8 on spawn
- `queenEnergyRegens_perTick` — Queen energy increases each tick
- `queenInject_consumesEnergy_addsLarva` — 25 energy → 4 larvae added
- `injectLarvaCap_at19` — larvae capped at 19
- `hatcheryLair_hive_countsForWorkerAssignment` — Drone near LAIR/HIVE earns income
- `multipleHatcheries_separateLarvaCounters` — independent per-hatchery state
- `reset_clearsZergRaceModelState` — after a full game (multiple hatcheries, queens, eggs), `reset()` clears `hatcheryLarvaCount`, `queenEnergy`, `eggTagByBuilding`, `hatcheryNextLarvaLoop`

**SC2Data / TechTree tests:**
- `trainCount_zergling_returns2`
- `trainCount_others_return1` (spot-check Marine, Drone)
- `trainedBy_queen_isHatchery`
- `trainedBy_mule_isOrbitalCommand`

---

## Known divergences (acknowledged, not fixed in #138)

**Auto-inject over-production:** The spec auto-injects from every Queen with ≥25 energy each tick. In real SC2, inject is player-commanded and routinely delayed or missed. If Zerg replay validation is added (#140 follow-on), EmulatedGame will consistently over-produce larvae relative to ground truth. Resolution options: (a) introduce `ZergInjectIntent` so inject is agent-commanded rather than autonomous; (b) harness syncs larva count from GT like it syncs supply/vespene. Deferred.

**Invisible larvae:** Real SC2 larvae are visible units in replay ground truth. The spec models larvae as a counter in `ZergRaceModel` — no `LARVA` units appear in `state.units` or the snapshot. This produces a permanent negative unit-count divergence for Zerg when compared against replay GT. Acceptable simplification for now; revisit if Zerg replay validation requires it.

---

## Open items (deferred)

- **Terran train time calibration** — all Terran unit `trainTimeInLoops` values are estimates until Terran replays are acquired in #140.
- **EGG filtering in harness** — addressed when Zerg harness support is implemented.
- **`PlayerState` public API for #74** — tracked in #164.
- **Harness multi-race support** — `ReplayValidationHarness.countProbesPerBase` rename to `countWorkersPerBase` is done in this issue; full multi-race harness (Terran/Zerg replay validation) is a follow-on.

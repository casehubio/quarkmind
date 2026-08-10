# SC2Data Unit Cost Tables — Design Spec

**Issue:** #229
**Date:** 2026-07-09

## Problem

`SC2Data.gasCost(UnitType)` covers 6 of 67 unit types (default → 0). `mineralCost(UnitType)` covers 16 (default → 100). `supplyCost(UnitType)` covers 16 (default → 2). The `default` clauses defeat Java's switch expression exhaustiveness checking — adding a new UnitType silently produces wrong values instead of a compile error.

This causes 81% undervaluation of late-game armies in `MultiFactorDominanceAssessor.armyFactor()`, wrong resource deduction in `EmulatedGame.handleTrain()`, and incorrect AI production cost checks in `EnemyBehavior.tickProduction()`.

## Root Cause

`default` clauses on enum switch expressions disable the compiler's exhaustiveness checking. The gap accumulated silently every time a UnitType was added without updating all cost methods.

### Why class-load validation, not compile-time exhaustiveness

The platform's sealed `Intent` pattern (ARC42STORIES §9.4) achieves compile-time exhaustiveness for behavioral dispatch — a new Intent subtype forces every switch expression to handle it at compile time. That pattern works because each switch arm contains distinct behavior.

UnitType costs are a data table, not behavioral dispatch. Three co-varying values (mineral, gas, supply) per unit type are better expressed as a map of records than three parallel switch expressions with identical arm structure. The tradeoff: `EnumMap` + static initializer validation catches missing entries at class-load time, not compile time. A new UnitType added without a cost entry will compile but fail when SC2Data is loaded.

This is acceptable because: (a) the data table pattern gives co-location (single entry vs. three switch arms) and single-point-of-update, which the Intent pattern cannot provide for pure data; and (b) 1305 tests transitively load SC2Data, guaranteeing the validation fires before any production path.

## Design

### UnitCosts record

New record in `io.quarkmind.domain`:

```java
/**
 * Per-individual-unit costs. For batch-trained units (Zergling, trainCount=2),
 * mineral and gas are per individual (Zergling: 25m = half of 50m batch cost).
 * Supply is per training command (Zergling: 1 = 0.5 per individual, rounded to int).
 * For all units with trainCount=1, the distinction is moot.
 *
 * Consumers that deduct costs per training command must multiply mineral/gas
 * by {@link SC2Data#trainCount} — see #234.
 */
public record UnitCosts(int mineral, int gas, int supply) {}
```

Plain Java, no framework dependencies. Lives in the domain package alongside UnitType.

### Storage in SC2Data

Replace three independent switch statements with a single `EnumMap<UnitType, UnitCosts>`:

```java
private static final Map<UnitType, UnitCosts> UNIT_COSTS;
static {
    var map = new EnumMap<UnitType, UnitCosts>(UnitType.class);
    map.put(UnitType.PROBE, new UnitCosts(50, 0, 1));
    // ...every UnitType...

    for (UnitType t : UnitType.values()) {
        if (!map.containsKey(t))
            throw new ExceptionInInitializerError("Missing UnitCosts for " + t);
    }
    UNIT_COSTS = Collections.unmodifiableMap(map);
}
```

The static initializer validates completeness at class load time. Any missing entry causes `ExceptionInInitializerError` — caught by virtually every test in the suite (1305 tests touch SC2Data transitively). The map is wrapped in `Collections.unmodifiableMap()` to prevent accidental mutation — immutability by construction, not convention.

### Public API

Existing methods become thin delegates (no caller changes):

```java
public static int mineralCost(UnitType type) { return UNIT_COSTS.get(type).mineral(); }
public static int gasCost(UnitType type)     { return UNIT_COSTS.get(type).gas(); }
public static int supplyCost(UnitType type)  { return UNIT_COSTS.get(type).supply(); }
```

New accessor for the full record:

```java
public static UnitCosts unitCosts(UnitType type) { return UNIT_COSTS.get(type); }
```

### Unit Type Categories

| Category | Examples | Cost Handling |
|----------|----------|---------------|
| Standard units | ZEALOT, STALKER, CARRIER, BATTLECRUISER | Real SC2 mineral/gas/supply |
| Workers | PROBE, SCV, DRONE | 50/0/1 |
| Transformation forms | SIEGE_TANK_SIEGED, VIKING_ASSAULT, LIBERATOR_AG, WARP_PRISM_PHASING, HELLBAT | Same as base type |
| Spawned tokens | INTERCEPTOR, LOCUST, BROODLING, CHANGELING, AUTO_TURRET, ADEPT_PHASE_SHIFT, INFESTED_TERRAN | 0/0/0 |
| Non-combat utility | OVERLORD (100/0/0), EGG (0/0/0), MULE (0/0/0) | Real SC2 values |
| UNKNOWN | Sentinel | 0/0/0 |

### Value Source

SC2 unit costs are fixed game data — deterministic, not empirical. Source: Liquipedia SC2 wiki and in-game data, targeting the final LotV balance (patch 5.0.11+, November 2020 — no cost changes since). The exhaustive test should document Liquipedia URLs per unit type for auditability. The calibration protocol (PP-20260522-572156) applies to timing constants, not costs.

### What This Does NOT Change

- `mineralCost(BuildingType)` — covers all 48 concrete types; `default` handles only the UNKNOWN sentinel. Stays as switch. Candidate for removing the `default` clause (add explicit `case UNKNOWN -> 100`) to get compile-time exhaustiveness, since this is a single switch, not a data table — tracked as part of #233.
- `gasCost(BuildingType)` — does not exist, not needed for DominanceAssessor
- Other SC2Data switch methods (maxHealth, damagePerAttack, etc.) — tracked as #233 for consolidation using the same EnumMap pattern

## Consumers Affected

| Consumer | How it uses costs | Impact |
|----------|-------------------|--------|
| `MultiFactorDominanceAssessor.armyFactor()` | `mineralCost + gasCost` per unit | Fixes 81% undervaluation |
| `EmulatedGame.handleTrain()` | Resource deduction | Correct cost deduction for all types |
| `EnemyBehavior.tickProduction()` | Production affordability check | Correct cost checks for AI |
| `SimulatedGame.applyIntent()` | `supplyCost()` for supply tracking on train completion | Correct supply values for all unit types |

All four use the existing `mineralCost()`/`gasCost()`/`supplyCost()` signatures — no call site changes. SimulatedGame does not use `mineralCost` or `gasCost` (it uses a simplified mineral trickle, not full economy simulation).

## Testing

- Exhaustive test: every UnitType has a cost assertion (mineral, gas, supply)
- Spot-check high-value units: Carrier (350/250/6), Battlecruiser (400/300/6), Colossus (300/200/6)
- Verify the fleet scenario from issue body: 4 Carriers × (350m + 250g) = 2,400 + 3 Colossi × (300m + 200g) = 1,500 + 2 Void Rays × (250m + 150g) = 800 = **4,700 total resources** (vs. current default-based 900 — the 81% undervaluation)
- Static initializer validation is implicitly tested by every test that loads SC2Data

# Multi-Factor DominanceAssessor Design

**Issue:** #223
**Date:** 2026-07-05
**Status:** Approved

## Problem

`SupplyDominanceAssessor` uses supply delta only — a single-dimensional proxy
that misses economic lead, tech advantage, army composition value, and
territorial control. Milestone attestations need a richer dominance signal to
produce meaningful intermediate trust scores.

## Design Decisions

1. **Richer return type** — `DominanceScore(double overall, Map<String, Double> factors)`
   replaces `double`. Callers see per-factor breakdown for attestation context.
2. **Four factors** — economy, army value, tech tier, base count. Map control
   deferred (requires base-location clustering — #228).
3. **Tech tier mapping** — `SC2Data.techTier(BuildingType)` method assigning
   each tech `BuildingType` to a tier (1–4). `BuildingType` values are already
   race-specific, so no race parameter is needed. Score = `maxTier + 0.1 × breadth`.
4. **Fixed configurable weights** — exposed via `MilestoneConfig.Dominance`.
   These are uncalibrated defaults based on domain reasoning — replay-validated
   weights are deferred to #227.
5. **Fog-of-war guard** — two-layer protection: a combined threshold returns
   neutral when total visible enemy entities are too sparse, and per-factor
   guards return 0.0 for factors whose specific enemy input list is empty.

## Interface Change

```java
public record DominanceScore(double overall, Map<String, Double> factors) {
    public DominanceScore {
        factors = Map.copyOf(factors);
    }
}

public interface DominanceAssessor {
    DominanceScore assess(GameState state);
}
```

Both `overall` and each factor value are in [-1.0, 1.0].

## Factor Calculations

### Economy (`economy`)

Compare own mineral income rate against estimated enemy mineral income rate.

Own side: `ownWorkerCount × SC2Data.MINERAL_TIER_RATES_PER_TICK[0]`
(flat first-tier rate per worker — avoids the per-base saturation distribution
problem of `mineralIncomePerTick()`). Worker count from `myUnits` filtered by
type PROBE/SCV/DRONE.

Enemy side: `enemyVisibleWorkerCount × SC2Data.MINERAL_TIER_RATES_PER_TICK[0]`
from visible enemy workers (filtered from `enemyUnits` by type PROBE/SCV/DRONE).

Delta = `ownIncomeRate - enemyIncomeRate`. Normalised by
`maxExpectedEconomyDelta` (configurable, default: 25.0), clamped to [-1, 1].

**Why income-only, not bank+income:** `GameState.minerals()` and `vespene()`
are the own player's resource bank. The enemy's bank is never visible — it
doesn't exist in `GameState`. Including own bank in a comparison against
enemy income creates a structurally positive bias (bank ≥ 0 always). Comparing
income rates only is symmetric — both sides use the same formula.

**Limitation:** vespene income is not modeled. A vespene income estimate
(gas buildings × workers-per-geyser × rate) is deferred until SC2Data gains
vespene income methods.

### Army Value (`army`)

Sum `SC2Data.mineralCost(type) + SC2Data.gasCost(type)` for all own combat units
(excluding workers: PROBE/SCV/DRONE) vs all visible enemy combat units (also
excluding workers). Workers are captured by the economy factor — including them
in army value would double-count.

Delta = `ownArmyValue - enemyArmyValue`. Normalised by `maxExpectedArmyDelta`
(configurable), clamped to [-1, 1].

**Prerequisite:** `SC2Data.gasCost()` currently covers only 6 unit types
(default → 0) and `mineralCost()` uses default → 100 for uncovered types.
Army value accuracy is bounded by SC2Data coverage — #229 tracks expanding
the cost tables.

### Tech Tier (`tech`)

Each `BuildingType` maps to a tier via `SC2Data.techTier(BuildingType)`:

| Tier | Protoss examples | Terran examples | Zerg examples |
|------|-----------------|-----------------|---------------|
| T1 | GATEWAY | BARRACKS | SPAWNING_POOL |
| T2 | ROBOTICS_FACILITY, STARGATE | FACTORY, STARPORT | HYDRALISK_DEN, ROACH_WARREN |
| T3 | TWILIGHT_COUNCIL, TEMPLAR_ARCHIVES, DARK_SHRINE | GHOST_ACADEMY, ARMORY | INFESTATION_PIT, LURKER_DEN |
| T4 | FLEET_BEACON, ROBOTICS_BAY | FUSION_CORE | GREATER_SPIRE, ULTRALISK_CAVERN |

Non-tech buildings (bases, supply, defence, gas) are excluded. Only completed
buildings (`isComplete == true`) count — an in-progress building does not grant
tech tier or breadth credit.

Score per side = `maxTier + 0.1 × distinctTechBuildingCount`. This weights
reaching higher tiers above building wide at the same tier.

**Note:** Zerg morphing chains (HATCHERY→LAIR→HIVE, SPIRE→GREATER_SPIRE)
produce fewer distinct tech buildings than Protoss/Terran at equivalent tech
levels. The 0.1 breadth multiplier bounds this effect to ~0.1 score difference,
which at 0.20 tech weight contributes ~0.02 to overall — negligible.

Delta = `ownTechScore - enemyTechScore`. Normalised by `maxExpectedTechDelta`
(configurable), clamped to [-1, 1].

### Base Count (`bases`)

Count own base buildings (NEXUS, COMMAND_CENTER, ORBITAL_COMMAND,
PLANETARY_FORTRESS, HATCHERY, LAIR, HIVE) where `isComplete == true`
vs visible enemy base buildings (also filtered to complete).

Delta = `ownBases - enemyBases`. Normalised by `maxExpectedBaseDelta`
(configurable), clamped to [-1, 1].

## Fog-of-War Guard

All four factors compare full-information own state against partial-information
enemy state. When enemy visibility is too sparse, the assessment is unreliable
and systematically biased positive.

**Combined threshold:** if `enemyUnits.size() + enemyBuildings.size()
< minEnemyVisibility` (configurable, default: 3), return a neutral score
(overall = 0.0, all factors = 0.0). This catches the "no scouting at all"
case.

**Per-factor guards:** above the combined threshold, individual factors still
return 0.0 (neutral) when their specific enemy data is absent:
- `economy`: return 0.0 when no enemy workers (PROBE/SCV/DRONE) are visible
  in `enemyUnits`. Seeing only combat units tells you nothing about the enemy
  economy — the economy factor must not treat "unscouted workers" as "zero workers."
- `army`: return 0.0 when `enemyUnits` is empty
- `tech` and `bases`: return 0.0 when `enemyBuildings` is empty

This prevents scenarios where the combined threshold passes (e.g., 3 enemy
units, 0 enemy buildings) but building-dependent factors have zero enemy data
and peg at +1.0. It also prevents the economy factor from producing a false
positive when only combat units are visible.

Above both guards, factor calculations proceed normally. Partial visibility
remains a known limitation: the dead zone (`|dominanceScore| < deadZoneThreshold`)
catches marginal scores, and milestone confidence (≤ 0.45 at early milestones)
bounds any individual assessment's impact on trust scores.

## Weights

Fixed, configurable via `MilestoneConfig.Dominance`:

```properties
quarkmind.milestones.dominance.weights.economy=0.30
quarkmind.milestones.dominance.weights.army=0.35
quarkmind.milestones.dominance.weights.tech=0.20
quarkmind.milestones.dominance.weights.bases=0.15
```

`overall = Σ(weight_i × factor_i)`, clamped to [-1, 1].

## New Classes

| Class | Package | Purpose |
|-------|---------|---------|
| `DominanceScore` | `io.quarkmind.domain` | Record — overall score + per-factor breakdown |
| `MultiFactorDominanceAssessor` | `io.quarkmind.agent` | CDI bean replacing `SupplyDominanceAssessor` |

## Changed Classes

| Class | Change |
|-------|--------|
| `SC2Data` | Add `techTier(BuildingType)` — returns `OptionalInt` (tier 1–4, or empty for non-tech buildings). Follows existing per-type lookup pattern (`supplyCost`, `mineralCost`, `trainedBy`, etc.) |

## Removed Classes

| Class | Reason |
|-------|--------|
| `SupplyDominanceAssessor` | Replaced by `MultiFactorDominanceAssessor` |
| `SupplyDominanceAssessorTest` | Replaced by `MultiFactorDominanceAssessorTest` |

## Config Changes

`MilestoneConfig.Dominance` gains:

```java
interface Dominance {
    @WithDefault("0.30")
    double economyWeight();
    @WithDefault("0.35")
    double armyWeight();
    @WithDefault("0.20")
    double techWeight();
    @WithDefault("0.15")
    double basesWeight();

    @WithDefault("25.0")
    double maxExpectedEconomyDelta();
    @WithDefault("3000")
    int maxExpectedArmyDelta();
    @WithDefault("2.0")
    double maxExpectedTechDelta();
    @WithDefault("3")
    int maxExpectedBaseDelta();

    @WithDefault("3")
    int minEnemyVisibility();
}
```

## Caller Updates

- `MilestoneOutcomeRecorder` — `assess()` returns `DominanceScore`;
  `deadZoneThreshold` compares against `score.overall()`.
- `MilestoneOutcomeRecorderTest` — update to use `DominanceScore`.
- Milestone trust scoring spec (#191) — `DominanceAssessor` interface definition
  updated from `double` to `DominanceScore` return type. `SupplyDominanceAssessor`
  reference replaced with `MultiFactorDominanceAssessor`.

## Testing

- `MultiFactorDominanceAssessorTest` — plain JUnit, no CDI. Tests each factor
  independently (one factor varies, others neutral), combined weighting, and
  fog-of-war guards:
  - Below-threshold visibility → neutral score (all factors = 0.0)
  - Above combined threshold but `enemyUnits` empty → economy and army = 0.0,
    tech and bases calculated normally
  - Above combined threshold, `enemyUnits` non-empty but no workers visible →
    economy = 0.0, army calculated normally
  - Above combined threshold but `enemyBuildings` empty → tech and bases = 0.0,
    economy and army calculated normally
  - All inputs populated → all factors calculated
- `SC2DataTest` — expanded with tech tier assertions: verifies tier assignments
  for all three races, ensures non-tech buildings return 0 (or `OptionalInt.empty()`).
- Existing `MilestoneOutcomeRecorderTest` — updated for `DominanceScore` return type.

## Deferred

- Map control factor — requires base-location clustering (#228)
- Phase-adaptive weights — #227, pending replay calibration data
- SC2Data unit cost table expansion — #229, prerequisite for accurate army value
- Vespene income estimation — deferred until SC2Data gains vespene income methods
- Scouting-confidence-based dominance dampening — future fog-of-war improvement
  beyond the minimum visibility threshold
- Milestone evaluation activation — blocked on engine#648

**Note:** fixed weights (economy=0.30, army=0.35, tech=0.20, bases=0.15) are
uncalibrated defaults based on domain reasoning, not replay validation. #227
tracks data-driven calibration.

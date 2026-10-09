# Chrono Boost for Protoss Economy Calibration

**Issue:** casehubio/quarkmind#391
**Branch:** issue-384-emulator-reconstitution-pipeline
**Date:** 2026-10-09

## Problem

Protoss economy playbook produces 44 Probes at tick 305 vs ~52 in real SC2. The 8-Probe gap is Chrono Boost — a Nexus ability that speeds production by 50% for 20 seconds.

## Design

### 1. AbilityIntent (new sealed Intent member)

```java
public record AbilityIntent(String casterTag, String ability, String targetTag) implements Intent {}
```

Added to `Intent permits` clause. `casterTag` uses `"r-"` prefix for auto-resolution (find a Nexus with sufficient energy). `ability` is the ability name string (e.g. `"CHRONO_BOOST"`). `targetTag` uses `"r-"` prefix for auto-resolution (find a building of the target type that's currently training).

### 2. ProtossRaceModel energy and boost tracking

Following ZergRaceModel's Queen energy pattern:

- `Map<String, Double> nexusEnergyMap` — energy per Nexus tag
- `Map<String, Long> chronoBoostedUntil` — active boost expiry per building tag
- Nexus starts at 50.0 energy on creation (`onBuildingCompleted` or seed)
- `tickPassive()` regenerates energy: `+0.5625 / 22.4` per game loop (×`LOOPS_PER_TICK` per tick), capped at 200.0
- `handleAbility()` validates energy >= 50, deducts 50, marks target boosted for 448 loops
- `trainingSpeedMultiplier(buildingTag, gameLoop)` returns 0.5 if boosted, 1.0 otherwise
- Boost expiry checked in `tickPassive()` — remove expired entries

### 3. RaceModel SPI additions

```java
default double trainingSpeedMultiplier(String buildingTag, long gameLoop) { return 1.0; }
default boolean handleAbility(PlayerState state, String casterTag, String ability,
                              String targetTag, long gameLoop) { return false; }
```

Default implementations return neutral values. Only ProtossRaceModel overrides for Chrono Boost. Other race models inherit defaults.

### 4. EmulatedGame changes

**handleAbility()** — new method handling `AbilityIntent`:
1. Resolve caster/target buildings (auto-resolve `"r-"` prefixed tags)
2. Delegate to `raceModel.handleAbility()` for validation and energy deduction
3. On success: find in-progress `PendingCompletion` for the target building, halve remaining time

**startTraining()** — query `raceModel.trainingSpeedMultiplier(buildingTag, gameFrame)`:
- If multiplier < 1.0, apply to `trainTimeInLoops`: `(long)(trainTime * multiplier)`

### 5. SC2DeliveryHandler ability action

```java
case "ability" -> doAbility(stepName, data);
```

`doAbility()`:
- Reads `data.get("ability")` for ability name (e.g. `"CHRONO_BOOST"`)
- Reads `data.get("target")` for target building type (e.g. `"NEXUS"`)
- Creates `AbilityIntent("r-ability", ability, "r-" + target.toLowerCase())`
- Returns `StepOutcome.fail` if game rejects (insufficient energy) — continuous loop retries next tick

### 6. Playbook update (economy-only-protoss.yaml)

```yaml
- ability: CHRONO_BOOST
  target: NEXUS
  loop: continuous
```

Continuously attempts Chrono Boost every tick. Succeeds when a Nexus has >= 50 energy and a Nexus is actively training a Probe. Assertion tightened:

```yaml
- assert: tick-305
  at: 305 ticks
  expect:
    units:
      PROBE: { min: 50 }
```

### SC2 Constants

| Constant | Value | Source |
|----------|-------|--------|
| Energy cost | 50 | SC2 game data |
| Duration | 20s (448 game loops) | SC2 game data |
| Speed multiplier | 0.5 (50% faster) | SC2 game data |
| Nexus starting energy | 50.0 | SC2 game data |
| Energy regen rate | 0.5625/second | SC2 game data |
| Max energy | 200.0 | SC2 game data |

### Expected calibration outcome

- First Nexus: Chronos at ~0s, ~89s, ~178s, ~267s (4 casts)
- Second Nexus (built ~tick 80): Chronos at build+0s, +89s, +178s (3 casts)
- Each Chrono saves ~8.5s of Probe training → ~7 extra Probes over 305 ticks
- Projected: 44 + 7 = ~51 Probes (above ≥50 threshold)

## Test Plan

1. **SC2DeliveryHandlerTest** (new, plain JUnit):
   - `abilityAction_chronoBoost_deductsEnergy` — verify energy deduction
   - `abilityAction_chronoBoost_insufficientEnergy_fails` — verify StepOutcome.fail
   - `abilityAction_chronoBoost_boostsTraining` — verify training time halved

2. **SC2PlaybookCalibrationTest** (update existing):
   - Tighten Protoss assertion to `PROBE: { min: 50 }` at tick 305

3. **Existing tests** must continue passing — no changes to Terran/Zerg playbooks or other tests.

## References

- ZergRaceModel.java — Queen energy pattern (lines 26-56)
- EmulatedGame.java:393 — startTraining method
- SC2DeliveryHandler.java — current 5-action switch
- SC2PlaybookRunner.java — tick loop and step parsing
- economy-only-protoss.yaml — current playbook (44 Probes at tick 305)
- PP-20260522-572156 — SC2 timing constants calibration protocol (not directly applicable to ability constants, but informs approach)
- Issue #391 — acceptance criteria and SC2 constants
- D10 — delivery handler vocabulary decision
- D12 — Chrono Boost approach decision

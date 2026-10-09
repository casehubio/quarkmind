# SC2 Playbook Delivery Type — Design Spec

**Issue:** #384 (Phase 2.5 — playbook-driven physics calibration)
**Branch:** `issue-384-emulator-reconstitution-pipeline`
**Date:** 2026-10-09

## Purpose

Close the EmulatedGame physics gap by running scripted build orders as CaseHub playbooks. The same playbook drives both real SC2 (`%sc2`) and EmulatedGame (`%emulated`). Any divergence between the two is a physics constant to calibrate.

## Architecture

### Components

1. **`SC2DeliveryHandler`** — CDI bean implementing `DeliveryHandler` (Pages SPI). Registered as delivery type `"sc2"`. Translates step actions (`train`, `build`, `research`, `morph`, `assert`) into `IntentQueue` calls.

2. **`SC2PlaybookRunner`** — CDI bean that owns the game loop for playbook execution. Drives the tick cycle: tick → observe → update metrics → evaluate conditions → dispatch intents. Replaces `AgentOrchestrator` for calibration scenarios.

3. **`SC2MetricRegistry`** — Registers three `OrcNumericPrimitive` metrics updated from `GameState` each tick:
   - `supply` — `state.supplyUsed()` (for `at: 14 supply` triggers)
   - `minerals` — `state.minerals()`
   - `gas` — `state.vespene()`

### Tick synchronisation

The playbook runner drives game ticks directly (D9):

```
for tick = 0 to limit:
    engine.tick()
    state = engine.observe()
    updateMetrics(state)        // supply, minerals, gas
    evaluateSteps(state)        // at: conditions, loop: re-entry
    dispatchIntents()           // train/build/research/morph
    checkAssertions(state)      // assert: steps at time checkpoints
```

Deterministic, single-threaded, reproducible. The playbook IS the game loop.

### Profile switching

The same playbook runs against different engines by Quarkus profile:
- `%emulated` — `EmulatedGame` (physics calibration)
- `%sc2` — real SC2 via `QuarkusSC2Transport` (ground truth capture)

The `SC2Engine` CDI interface abstracts the difference. No playbook changes needed.

## Step vocabulary

### `train:`

Trains a unit. Building resolved by `shortestQueue` (existing mechanism).

```yaml
- train: PROBE
  loop: continuous
  resource: minerals
  priority: background
```

Maps to `TrainIntent(buildingTag, UnitType.PROBE)`.

### `build:`

Builds a structure. Position is optional (defaults to a placeholder near main base).

```yaml
- build: PYLON
  at: 14 supply
```

Maps to `BuildIntent(BuildingType.PYLON, position)`.

### `research:`

Starts an upgrade research.

```yaml
- research: WARP_GATE
  at:
    - >=1 gateway_complete
```

Maps to `ResearchIntent(buildingTag, UpgradeType.WARP_GATE)`.

### `morph:`

Morphs a building or unit.

```yaml
- morph: LAIR
  source: HATCHERY
```

Maps to `MorphIntent(unitTag, "Hatchery", "Lair")`.

### `assert:`

Validates `GameState` at a checkpoint. Throws `AssertionError` on failure (D11).

```yaml
- assert:
  at: 3m
  expect:
    units:
      PROBE: { min: 30, max: 40 }
    buildings:
      NEXUS: { min: 2 }
    minerals: { min: 200 }
```

No intent — reads `GameState` and asserts.

## Playbook format

```yaml
scenario: economy-only-protoss
schema: sc2
meta:
  race: PROTOSS
  duration: 5m

steps:
  - train: PROBE
    loop: continuous
    resource: minerals
    priority: background

  - build: PYLON
    at: 14 supply

  - build: NEXUS
    at: 20 supply
    on-complete:
      - build: PYLON

  - assert:
    at: 1m
    expect:
      units: { PROBE: { min: 18 } }

  - assert:
    at: 3m
    expect:
      units: { PROBE: { min: 35 } }

  - assert:
    at: 5m
    expect:
      units: { PROBE: { min: 50 } }
```

**Key syntax points:**
- No `delivery:` key — schema `sc2` implies the delivery handler
- Step-type-as-key (`train: PROBE`) — the parser treats any non-known key as the action key
- Platform primitives handle all coordination: `at:` for supply/time gates, `loop: continuous` for ongoing production, `on-complete:` for chains, `priority:` + `resource:` for contention

## Test integration

```java
@QuarkusTest
@TestProfile(EmulatedProfile.class)
class SC2PlaybookCalibrationIT {

    @Inject SC2PlaybookRunner runner;

    @Test
    void economyOnlyProtoss() {
        runner.execute("playbooks/economy-only-protoss.yaml");
        // assert: steps inside the playbook throw on failure
    }

    @Test
    void economyOnlyTerran() {
        runner.execute("playbooks/economy-only-terran.yaml");
    }

    @Test
    void economyOnlyZerg() {
        runner.execute("playbooks/economy-only-zerg.yaml");
    }
}
```

Playbook files live in `quarkmind-sc2/src/test/resources/playbooks/`.

## Calibration workflow

1. Write a playbook with a known build order and expected checkpoints
2. Run against `%emulated` — assertions fail where physics diverge
3. Fix the physics constant (train time, income rate, etc.)
4. Re-run — assertion passes
5. Run against `%sc2` to capture a ground truth replay
6. Run the replay through `OwnReplayCalibrationTest` for full divergence report

## File placement

| File | Location |
|------|----------|
| `SC2DeliveryHandler.java` | `quarkmind-sc2/src/main/java/io/quarkmind/sc2/playbook/` |
| `SC2PlaybookRunner.java` | `quarkmind-sc2/src/main/java/io/quarkmind/sc2/playbook/` |
| `SC2MetricRegistry.java` | `quarkmind-sc2/src/main/java/io/quarkmind/sc2/playbook/` |
| `SC2PlaybookCalibrationIT.java` | `quarkmind-sc2/src/test/java/io/quarkmind/sc2/playbook/` |
| `economy-only-protoss.yaml` | `quarkmind-sc2/src/test/resources/playbooks/` |
| `economy-only-terran.yaml` | `quarkmind-sc2/src/test/resources/playbooks/` |
| `economy-only-zerg.yaml` | `quarkmind-sc2/src/test/resources/playbooks/` |

## Dependencies

| Artifact | What it provides |
|----------|-----------------|
| `casehub-platform-yaml-core` | `OrcNumericPrimitive`, `LoopDirective`, `Priority`, `PriorityOrcSemaphore` |
| `casehub-platform-yaml-step-runtime` | `DecoratorChain`, `ThresholdCondition`, `OnCompleteExpander` |
| `casehub-pages-playbook` | `DeliveryHandler`, `CompactStep`, `PlaybookCompiler`, `StepOutcome` |

## References

- `DesiredStateDeliveryHandler.java` (IoT) — delivery handler SPI precedent
- `morning-routine.yaml` (IoT) — simulation playbook precedent
- `clean-transaction.playbook.yaml` (AML) — step sequencing precedent
- `PlaybookContentParser.java` — action-key-as-step-type parser (line 153)
- Platform #562 — `at:`, `on-complete:`, `priority:`/`resource:` primitives
- Platform #563 — `loop:`, `cancel:`, `background:` primitives
- D8-D11 in `decisions.md`

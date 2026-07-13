# Phase-Adaptive Dominance Weights — Design Spec

**Issue:** #227
**Date:** 2026-07-13

## Problem

`MultiFactorDominanceAssessor` uses fixed weights (economy=0.30, army=0.35, tech=0.20, bases=0.15) regardless of game state. In SC2, the relative importance of each factor shifts as the game progresses and the situation changes — a worker count lead matters far more at minute 2 than minute 15, while army value dominates late-game decisive engagements. Fixed weights cannot express this.

### Prerequisite Relationship

Issue #227 states: "Requires replay calibration data showing where fixed weights break down." The prior spec (#223) deferred phase-adaptive weights to #227 explicitly, noting its defaults were "uncalibrated defaults based on domain reasoning, not replay validation."

This spec delivers the **SPI infrastructure** — the strategy pattern, interpolation engine, config seam, and CDI wiring — that makes phase-adaptive weights *possible*. The default configuration preserves current fixed-weight behavior (single anchor matching #223 defaults). Tuned multi-anchor values that actually shift weights by game phase are deferred to a calibration pass once replay data validates where fixed weights break down. Infrastructure first, then calibration.

## Design

### Domain Model

**`DominanceWeights`** — new record in `domain/`. Holds four factor weights with a sum-to-1.0 invariant.

```java
public record DominanceWeights(double economy, double army, double tech, double bases) {
    public DominanceWeights {
        double sum = economy + army + tech + bases;
        if (Math.abs(sum - 1.0) > 0.001) {
            throw new IllegalArgumentException("Weights must sum to 1.0, got " + sum);
        }
    }
}
```

**`WeightContext`** — new record in `agent/`. Signal bundle for weight resolution. Starts minimal, additive for future signals.

```java
public record WeightContext(long gameFrame, String currentPhase) {}
```

`currentPhase` is the `GamePhase.phase()` string from the summarisation pipeline (`EARLY_MACRO`, `MID_SKIRMISH`, `DEFENSIVE_HOLD`, `EARLY_AGGRESSION`, `TRANSITIONING`), or `null` if no phase has been published yet.

**Trade-off: pure-function `resolve()` vs assessor coupling.** The `resolve()` pure-function constraint means the assessor must subscribe to every data source any strategy might need and pass it via `WeightContext`. Each new signal requires modifying the assessor (to gather it) and `WeightContext` (to carry it). This buys testability — strategies are pure functions testable without CDI, buses, or mocks — at the cost of assessor coupling. For the current two strategies this coupling is minimal. When a Drools-based strategy arrives (#240), the design may need revisiting — strategies might need direct access to signal sources rather than pre-gathered context.

### SPI

**`DominanceWeightStrategy`** — new interface in `agent/`. The extensibility seam.

```java
public interface DominanceWeightStrategy {
    String id();
    DominanceWeights resolve(WeightContext context);
}
```

`id()` returns a stable identifier matched against config for strategy selection. `resolve()` is a pure function — no side effects, no bus subscriptions.

### Implementations

**1. `TemporalDominanceWeightStrategy`** — in `agent/`. Anchor-point linear interpolation from `gameFrame` only. Ignores `currentPhase`.

- Reads anchor list from `MilestoneConfig.Dominance.anchors()`
- Before first anchor frame → first anchor's weights
- After last anchor frame → last anchor's weights
- Between anchors → linear interpolation per weight component
- Single anchor → fixed weights (degrades to current behaviour)
- Anchors must contain at least one entry; validated at construction (fail fast, `IllegalArgumentException`). An empty list causes undefined behavior in the interpolation algorithm — fail fast, not at first `resolve()`.
- Anchors must be strictly ascending by frame (no duplicate frames); validated at construction (fail fast). Duplicate frames cause division by zero in the interpolation formula: `t = (gameFrame - anchor[i].frame) / (anchor[i+1].frame - anchor[i].frame)`.
- `id()` returns `"temporal"`

**`AnchorInterpolator`** — package-private helper in `agent/`. Extracts the anchor interpolation algorithm shared by both strategies. Takes a `List<WeightAnchor>` at construction, validates cardinality (≥ 1) and strict frame ascending order (`anchor[i].frame < anchor[i+1].frame`). Exposes a single method:

```java
DominanceWeights interpolate(long gameFrame)
```

Both `TemporalDominanceWeightStrategy` and `SituationalDominanceWeightStrategy` delegate to `AnchorInterpolator` for the temporal baseline. This is DRY without creating a CDI dependency between strategies — the interpolator is a plain helper, not a CDI bean. If the interpolation algorithm is later enhanced (smoothing, easing curves), the change is in one place.

**2. `SituationalDominanceWeightStrategy`** — in `agent/`. Temporal baseline (via `AnchorInterpolator`) modulated by `currentPhase`.

Phase modifiers (additive shifts, re-normalised to sum to 1.0):

| Phase | Economy | Army | Tech | Bases |
|-------|---------|------|------|-------|
| `DEFENSIVE_HOLD` | -0.10 | +0.15 | -0.05 | +0.00 |
| `EARLY_AGGRESSION` | -0.05 | +0.10 | -0.05 | +0.00 |
| `EARLY_MACRO` | +0.10 | -0.10 | +0.05 | -0.05 |
| `MID_SKIRMISH` | -0.05 | +0.10 | -0.05 | +0.00 |
| `TRANSITIONING` | +0.00 | +0.00 | +0.00 | +0.00 |
| `null` (no phase yet) | +0.00 | +0.00 | +0.00 | +0.00 |

Re-normalisation: after applying shifts, divide each weight by the new sum to restore the sum-to-1.0 invariant. Clamp individual weights to a minimum of 0.05 before normalising to prevent any factor from being zeroed out.

**Floor/modifier/anchor constraint:** Modifier magnitude should not exceed `min(anchor_weight_for_component) - 0.05` for any weight component across all configured anchors. When clamping fires, log at `DEBUG` with the phase name and affected component — this signals that modifier magnitudes are too large relative to the interpolated baseline at the current game frame. With the default single-anchor config (current fixed weights), no modifier triggers clamping. Future multi-anchor configs with lower component weights must be validated against the modifier table.

- `id()` returns `"situational"`

### Assessor Changes

**`MultiFactorDominanceAssessor`** — modified:

- Remove fields: `economyWeight`, `armyWeight`, `techWeight`, `basesWeight`
- Add field: `DominanceWeightStrategy strategy` (resolved at construction — see CDI wiring below)
- Add field: `volatile GamePhase cachedPhase` — `volatile` for consistency with `GamePhaseTrigger.lastSeenPhase` and future-proofing against async milestone evaluation
- Add phase subscription: lazy-subscribe to `SummarisationLifecycle.phaseBus()` using the same `Instance<SummarisationLifecycle>` + double-checked locking pattern as `GamePhaseTrigger`
- Add game-reset handler: `@Observes GameStarted` sets `cachedPhase = null`. Without this, a game ending during `DEFENSIVE_HOLD` carries stale phase data into the next game until `GamePhaseSummariser` emits the first phase (~672 frames / ~30 seconds)
- `assess()` method: build `WeightContext(state.gameFrame(), cachedPhase != null ? cachedPhase.phase() : null)`, call `strategy.resolve(context)`, use returned weights for combination
- **Observability:** log at `DEBUG` when the resolved weights differ from the previous resolve (any component delta > 0.01). Format: `[DOMINANCE] Weights shifted: economy=%.2f army=%.2f tech=%.2f bases=%.2f (strategy=%s frame=%d phase=%s)`

Factor computation methods (`economyFactor`, `armyFactor`, `techFactor`, `basesFactor`) are unchanged.

**CDI wiring:**

```java
@Inject
MultiFactorDominanceAssessor(
        @Any Instance<DominanceWeightStrategy> strategies,
        Instance<SummarisationLifecycle> summarisationLifecycle,
        MilestoneConfig config) {
    String selectedId = config.dominance().weightStrategy();
    this.strategy = strategies.stream()
        .filter(s -> s.id().equals(selectedId))
        .reduce((a, b) -> { throw new IllegalStateException(
            "Duplicate DominanceWeightStrategy id: " + selectedId); })
        .orElseThrow(() -> new IllegalStateException(
            "No DominanceWeightStrategy with id '" + selectedId + "'"));
    // ... normalisation constants from config
}
```

Both `TemporalDominanceWeightStrategy` and `SituationalDominanceWeightStrategy` are `@ApplicationScoped` CDI beans. The assessor discovers all implementations via `@Any Instance<DominanceWeightStrategy>` and matches `id()` against the `weightStrategy` config value. This is genuinely open for extension: a new strategy can be added by dropping a jar with an `@ApplicationScoped DominanceWeightStrategy` implementation — no code changes in the assessor. Fail-fast at startup: no match → `IllegalStateException`; duplicate `id()` → `IllegalStateException`.

**Test constructor:** takes `DominanceWeightStrategy` directly plus normalisation constants. No CDI, no bus subscription. Existing tests pass a lambda or construct a `TemporalDominanceWeightStrategy` with a single anchor.

### Config Changes

**`MilestoneConfig.Dominance`** — modified:

- Remove: `economyWeight()`, `armyWeight()`, `techWeight()`, `basesWeight()`
- Add: `weightStrategy()` with `@WithDefault("temporal")`
- Add: `List<WeightAnchor> anchors()` config group

```java
interface WeightAnchor {
    long frame();
    @WithName("economy-weight") double economyWeight();
    @WithName("army-weight") double armyWeight();
    @WithName("tech-weight") double techWeight();
    @WithName("bases-weight") double basesWeight();
}
```

**Default anchor config in `application.properties`:**

```properties
quarkmind.milestones.dominance.weight-strategy=temporal

# Single anchor — preserves current fixed-weight behavior (economy=0.30, army=0.35,
# tech=0.20, bases=0.15). With one anchor the temporal strategy degrades to fixed weights.
# Multi-anchor config with tuned values deferred to calibration pass (see Anchor Timing Rationale).
quarkmind.milestones.dominance.anchors[0].frame=0
quarkmind.milestones.dominance.anchors[0].economy-weight=0.30
quarkmind.milestones.dominance.anchors[0].army-weight=0.35
quarkmind.milestones.dominance.anchors[0].tech-weight=0.20
quarkmind.milestones.dominance.anchors[0].bases-weight=0.15
```

**Example multi-anchor config** (for reference — not shipped as default until calibrated):

```properties
# Multi-anchor: weights shift from economy-heavy early to army-heavy late
# quarkmind.milestones.dominance.anchors[0].frame=0
# quarkmind.milestones.dominance.anchors[0].economy-weight=0.40
# quarkmind.milestones.dominance.anchors[0].army-weight=0.20
# quarkmind.milestones.dominance.anchors[0].tech-weight=0.25
# quarkmind.milestones.dominance.anchors[0].bases-weight=0.15
#
# quarkmind.milestones.dominance.anchors[1].frame=8064
# quarkmind.milestones.dominance.anchors[1].economy-weight=0.30
# quarkmind.milestones.dominance.anchors[1].army-weight=0.35
# quarkmind.milestones.dominance.anchors[1].tech-weight=0.20
# quarkmind.milestones.dominance.anchors[1].bases-weight=0.15
#
# quarkmind.milestones.dominance.anchors[2].frame=16128
# quarkmind.milestones.dominance.anchors[2].economy-weight=0.15
# quarkmind.milestones.dominance.anchors[2].army-weight=0.50
# quarkmind.milestones.dominance.anchors[2].tech-weight=0.15
# quarkmind.milestones.dominance.anchors[2].bases-weight=0.20
```

Normalisation constants (`maxExpectedEconomyDelta`, etc.) and `minEnemyVisibility` are unchanged.

**Validation timing:** `DominanceWeights` sum validation, anchor cardinality (≥ 1), and anchor strict frame ordering (`<`, not `≤`) all fire at strategy construction time during CDI initialization. SmallRye config parses the anchor list eagerly at startup, so all config is available before the first game tick. Misconfiguration (weights not summing to 1.0, empty anchor list, anchors not strictly ascending by frame) causes fail-fast startup failure — never deferred to first `resolve()` call.

### Anchor Timing Rationale

**Default:** single anchor at frame 0 matching current fixed weights. This preserves behavioral parity with the #223 implementation. With one anchor, `TemporalDominanceWeightStrategy` returns the same weights at every game frame — identical to the current `MultiFactorDominanceAssessor`.

**Future multi-anchor timing** (deferred to calibration pass — SC2 "faster" speed: ~22.4 game frames/second):

| Anchor | Frame | Real Time | Rationale |
|--------|-------|-----------|-----------|
| 0 | 0 | 0:00 | Game start — economy and tech decisions dominate |
| 1 | 8064 | ~6:00 | Mid game — balanced; matches current fixed defaults |
| 2 | 16128 | ~12:00 | Late game — army value dominates decisive engagements |

These values are domain reasoning, not calibrated. They will be validated against replay data before shipping as defaults. The commented example config in the Config Changes section above provides a ready-to-test starting point for calibration.

### Files Changed

| File | Change |
|------|--------|
| `domain/DominanceWeights.java` | New — weight record with sum validation |
| `agent/WeightContext.java` | New — signal bundle for weight resolution |
| `agent/DominanceWeightStrategy.java` | New — SPI interface |
| `agent/AnchorInterpolator.java` | New — package-private shared anchor interpolation helper |
| `agent/TemporalDominanceWeightStrategy.java` | New — delegates to AnchorInterpolator |
| `agent/SituationalDominanceWeightStrategy.java` | New — delegates to AnchorInterpolator + phase modifiers |
| `agent/MultiFactorDominanceAssessor.java` | Modified — delegate to strategy, subscribe to phase bus |
| `agent/MilestoneConfig.java` | Modified — strategy selection + anchor list config |
| `application.properties` | Modified — replace flat weights with strategy + anchors |

### Test Plan

**`DominanceWeightsTest`** — sum validation, rejection of invalid sums, value preservation.

**`AnchorInterpolatorTest`** — before first anchor, at exact anchor, between anchors (verify interpolated values), after last anchor, single anchor (fixed weights), two anchors (simple interpolation), anchors not strictly ascending rejected (including duplicate frames), empty anchor list rejected.

**`TemporalDominanceWeightStrategyTest`** — delegates to `AnchorInterpolator` correctly, `id()` returns `"temporal"`.

**`SituationalDominanceWeightStrategyTest`** — each phase modifier applied correctly, re-normalisation preserves sum-to-1.0, null phase returns temporal baseline, minimum weight floor (0.05) respected, modifier + temporal interpolation compose correctly.

**`MultiFactorDominanceAssessorTest`** — existing tests adapted to use single-anchor strategy (same weights as current defaults, same assertions). New tests: verify weights change with gameFrame, verify phase subscription wires through to strategy.

**`MultiFactorDominanceAssessorTest` (CDI constructor)** — tests for the CDI constructor's strategy resolution logic using a mock `Instance<DominanceWeightStrategy>`. No `@QuarkusTest` needed. Three cases:
- Correct id match: one strategy with matching `id()` → selected successfully
- No id match: no strategy matches config value → `IllegalStateException` at construction
- Duplicate id: two strategies with the same `id()` → `IllegalStateException` at construction

**`MultiFactorDominanceAssessorTest` (integration)** — `@QuarkusTest` not needed; the test constructor takes a strategy directly.

**Engine#648 dependency:** `MilestoneOutcomeRecorder.evaluateMilestones()` is currently a no-op (gated on engine#648 `AttestingOutcomeRecorder` SPI). The weight strategy change has no observable runtime effect until engine#648 ships. Component-level testing (strategy logic, assessor wiring, CDI resolution) is fully exercisable. End-to-end integration testing of the full `evaluateMilestones()` → `assess()` → `strategy.resolve()` path is deferred to engine#648 activation.

### Future Work

- **Drools-based `DominanceWeightStrategy`** — #240. A DRL file that matches signals and produces weights. The SPI seam is ready; only a new impl is needed. Lower priority. Note: may require revisiting the pure-function `resolve()` constraint (see WeightContext trade-off documentation above).
- **Multi-anchor calibration** — validate the commented multi-anchor timing values against replay data. Update `application.properties` defaults once calibrated. Tracked by #227 (which remains open until calibration is complete).
- **Clamping constraint validation** — when anchors are calibrated, verify that no modifier magnitude exceeds `min(anchor_weight) - 0.05` for any component. The floor/modifier/anchor interaction is documented in the SituationalDominanceWeightStrategy section above.

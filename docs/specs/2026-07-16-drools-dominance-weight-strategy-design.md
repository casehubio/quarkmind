# Drools-Based DominanceWeightStrategy — Design Spec

**Issue:** #240
**Date:** 2026-07-16
**Status:** Approved

## Context

#227 introduced the `DominanceWeightStrategy` SPI with two implementations:
`TemporalDominanceWeightStrategy` (anchor-point interpolation) and
`SituationalDominanceWeightStrategy` (temporal + phase modifiers). #237 added
multi-archetype pattern classification with confidence scoring via
`PatternClassifier.allAssessments()`.

A Drools-based implementation provides a declarative, inspectable alternative
where weight adjustments are expressed as DRL rules matching multiple signals
(enemy archetype, game phase, temporal position) and emitting composable
weight modifiers.

## Design Decisions

1. **Separate rule unit** — `DominanceWeightRuleUnit` is independent from
   `StrategyRuleUnit`. Weight resolution and strategy selection are distinct
   concerns with different signal surfaces.

2. **Enrich WeightContext** — add `List<EnemyPatternAssessment>` to
   `WeightContext`. The assessor pulls pattern assessments from
   `ScoutingIntelBroker.current()` and passes them through the context.
   `resolve()` stays a pure function. Imperative strategies ignore the
   new field.

3. **Modifier pattern** — rules emit signed `WeightModifier` deltas applied
   to an interpolated baseline. Multiple rules compose additively. No mutual
   exclusion required.

4. **Reuse AnchorInterpolator for baseline** — the temporal baseline is
   computed in Java before firing rules. Rules only emit situational
   adjustments. Avoids reimplementing interpolation in DRL.

5. **Pre-gathered context over strategy-owned signals** — issue #240
   deferred a choice: enrich `WeightContext` vs give strategies direct
   signal-source access. Pre-gathered context is chosen because:
   (a) `resolve()` stays a pure function — strategies are stateless and
   unit-testable without mocking signal sources;
   (b) the assessor already pulls cross-cutting signals (game phase via
   `SummarisationLifecycle`) — adding `ScoutingIntelBroker` follows the
   same integration pattern;
   (c) the cost for non-consuming strategies is zero — `List.of()` is a
   singleton, and imperative strategies never iterate it.

6. **Strategy lifecycle** — `DroolsDominanceWeightStrategy` functionally
   subsumes `SituationalDominanceWeightStrategy` (temporal baseline +
   phase modifiers + pattern-assessment rules). Once validated through
   replay calibration, `situational` will be deprecated (#241).
   `temporal` remains as the minimal baseline strategy for testing.

## Architecture

### WeightContext

```java
public record WeightContext(
    long gameFrame,
    String currentPhase,
    List<EnemyPatternAssessment> patternAssessments
) {}
```

`patternAssessments` contains archetypes above the dispatch threshold (0.3),
sorted by confidence descending. Empty list when nothing is detected.

### WeightModifier

Strategy-layer record in `io.quarkmind.agent`:

```java
public record WeightModifier(
    double economyDelta,
    double armyDelta,
    double techDelta,
    double basesDelta,
    String reason
) {}
```

Signed deltas — positive increases weight, negative decreases. `reason` is
for logging/debugging.

### DominanceWeightRuleUnit

In `io.quarkmind.plugin.drools`:

```java
public class DominanceWeightRuleUnit implements RuleUnitData {
    private final DataStore<EnemyPatternAssessment> patternStore = DataSource.createStore();
    private final DataStore<String>                 phaseStore   = DataSource.createStore();

    private final List<WeightModifier> modifiers = new ArrayList<>();
}
```

- `patternStore` — all assessments from `WeightContext`
- `phaseStore` — 0 or 1 items (current phase string)
- `modifiers` — plain `List` output, rules add entries

### DroolsDominanceWeightStrategy

`@ApplicationScoped` CDI bean in `io.quarkmind.agent`:

- `id()` returns `"drools"`
- `resolve()` computes baseline via `AnchorInterpolator`, populates
  `DominanceWeightRuleUnit` from `WeightContext`, fires rules, applies
  modifiers to baseline
- `applyModifiers()` — static package-private: sum modifier deltas per
  dimension, add to baseline, floor at `MINIMUM_WEIGHT` (shared constant
  on `DominanceWeightStrategy`), normalise to sum to 1.0
- Test constructor takes `RuleUnit` + anchor list (no CDI)

### DRL Rules (DominanceWeightAdjustment.drl)

Rules match on situational signals and emit `WeightModifier` entries.
Multiple rules fire and compose additively. Default salience (0) is
intentional — the modifier pattern is additive, so firing order does not
affect the result.

Archetype matching uses `archetype().name().contains("RUSH")` for
category-level rules (consistent with `StarCraftStrategy.drl`) and
explicit enum comparison for specific archetypes.

**Rule categories:**

*Rush response:*
- High-confidence rush (≥ 0.6): economy −0.10, army +0.15, tech −0.05,
  bases 0.00
- Moderate-confidence rush (≥ 0.3, < 0.6): economy −0.05, army +0.08,
  tech −0.03, bases 0.00

*Push response (BIO_TIMING, MECH_PUSH):*
- High-confidence (≥ 0.5): economy −0.10, army +0.10, tech +0.05,
  bases −0.05

*Harass response (BANSHEE_HARASS):*
- Any confidence (≥ 0.3): economy −0.10, army +0.05, tech +0.10,
  bases −0.05

*Macro response:*
- Macro archetype (≥ 0.5): economy +0.08, tech +0.05, army −0.10,
  bases −0.03

*Phase modifiers (4 dimensions, matching `SituationalDominanceWeightStrategy`):*
- DEFENSIVE_HOLD: economy −0.10, army +0.15, tech −0.05, bases 0.00
- EARLY_AGGRESSION: economy −0.05, army +0.10, tech −0.05, bases 0.00
- EARLY_MACRO: economy +0.10, army −0.10, tech +0.05, bases −0.05
- MID_SKIRMISH: economy −0.05, army +0.10, tech −0.05, bases 0.00
- TRANSITIONING: no rule (all zeros = no modifier needed)

*Combined signals (the Drools-specific value):*
- Rush (≥ 0.5) + DEFENSIVE_HOLD: army +0.05 additional
- Macro + EARLY_MACRO: economy +0.05 additional
- Push (BIO_TIMING/MECH_PUSH, ≥ 0.5) + MID_SKIRMISH: army +0.05,
  tech +0.03 additional
- Rush (≥ 0.5) + EARLY_AGGRESSION: economy +0.03, army +0.03 additional
  (contradictory signals — shore up economy while maintaining army)

Exact delta values are initial estimates — calibrated from replay data.
Both the delta values and the rule set will expand post-calibration as
replay analysis reveals additional actionable signal combinations (#242).

### Signal Plumbing

`MultiFactorDominanceAssessor` injects `ScoutingIntelBroker` and reads
pattern assessments synchronously via `broker.current(PATTERN_ASSESSMENT)`.
No bus subscription needed — the broker stores latest payloads by type.

```java
List<EnemyPatternAssessment> assessments = broker
    .current(ScoutingIntelType.PATTERN_ASSESSMENT,
             ScoutingIntelPayload.PatternAssessment.class)
    .map(ScoutingIntelPayload.PatternAssessment::assessments)
    .orElse(List.of());
```

### Configuration

No new config properties. Existing
`quarkmind.milestones.dominance.weight-strategy=drools` activates the
strategy. Default remains `temporal`. Anchor config is reused.

## File Changes

**New files:**

| File | Package | Purpose |
|------|---------|---------|
| `agent/WeightModifier.java` | `agent` | Strategy-layer record |
| `plugin/drools/DominanceWeightRuleUnit.java` | `plugin.drools` | Rule unit data |
| `plugin/drools/DominanceWeightAdjustment.drl` | `plugin.drools` | DRL rules |
| `agent/DroolsDominanceWeightStrategy.java` | `agent` | CDI bean |
| Test: `plugin/drools/DominanceWeightRuleUnitTest.java` | | Rule unit tests |
| Test: `agent/DroolsDominanceWeightStrategyTest.java` | | Strategy bean tests |

**Modified files:**

| File | Change |
|------|--------|
| `WeightContext.java` | Add `patternAssessments` field |
| `DominanceWeightStrategy.java` | Add `MINIMUM_WEIGHT` constant |
| `MultiFactorDominanceAssessor.java` | Inject `ScoutingIntelBroker`, read assessments, pass to `WeightContext` |
| `SituationalDominanceWeightStrategy.java` | Replace hardcoded `FLOOR` with `MINIMUM_WEIGHT` |
| `MultiFactorDominanceAssessorTest.java` | Test constructor gains broker |
| `TemporalDominanceWeightStrategyTest.java` | `WeightContext` constructor adds `List.of()` |
| `SituationalDominanceWeightStrategyTest.java` | `WeightContext` constructor adds `List.of()` |

## Testing

All plain JUnit — no `@QuarkusTest` needed.

**DominanceWeightRuleUnitTest:**
- No signals → empty modifiers, baseline unchanged
- Single rush archetype → army increases, economy decreases
- Push archetype (BIO_TIMING) → army and tech increase
- Harass archetype (BANSHEE_HARASS) → tech increases
- Multiple archetypes → modifiers stack
- Phase modifier only (each of 4 active phases)
- MID_SKIRMISH phase modifier
- Combined signal (rush + DEFENSIVE_HOLD) → army stacking
- Combined signal (macro + EARLY_MACRO) → economy stacking
- Combined signal (push + MID_SKIRMISH) → army + tech boost
- Combined signal (rush + EARLY_AGGRESSION) → economy + army boost
- Confidence at boundary (0.3 fires, 0.29 doesn't)
- Floor clamping and renormalisation

**DroolsDominanceWeightStrategyTest:**
- `id()` returns `"drools"`
- Empty context returns interpolated baseline
- Pattern assessments produce modified weights
- `applyModifiers()` edge cases: empty list, single modifier, conflicting
  modifiers, floor clamping, normalisation to 1.0

**Existing test updates:** `WeightContext` constructor calls gain
`List.of()` — no logic changes.

## Garden Context

- GE-0056: DRL syntax traps with Java records and OOPath
- GE-0063: `DataSource.createStore()` in plain JUnit
- GE-20260418-58cae8: Salience controls firing order, not mutual exclusion
  (mitigated — modifier pattern is additive by design)

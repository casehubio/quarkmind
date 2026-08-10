# Zergling Batch Cost Fix + Enemy Pattern Classifier

**Issues:** #234, #183
**Branch:** issue-234-zergling-batch-and-classifier
**Date:** 2026-07-11

---

## #234 — Zergling batch cost deduction fix

### Problem

`EmulatedGame.handleTrain()` deducts `mineralCost(ZERGLING)` = 25 once per
`TrainIntent`, then spawns `trainCount(ZERGLING)` = 2 Zerglings. The actual SC2
cost for a Zergling training command is 50 minerals (25 per unit × 2 units).

Both Phase 2 (resource check) and Phase 4 (resource deduction) use un-multiplied
costs. Phase 2 allows training when the player has only 25 minerals. Phase 4
under-deducts by 25 minerals per batch.

Supply cost is already correct — Zergling supply cost is 1 per pair.

### Fix

Compute `trainCount` once alongside other cost lookups:

```java
final int count = SC2Data.trainCount(t.unitType());
```

Phase 2: check `minerals < mCost * count`, `vespene < gCost * count`.
Phase 4: deduct `mCost * count`, `gCost * count`.

**Supply is NOT multiplied** — `supplyCost()` returns the per-command cost
(1 for the Zergling pair), not the per-unit cost. The supply check and
deduction continue to use `sCost` directly. Multiplying by `count` would
double-charge supply (1 × 2 = 2 instead of the correct 1).

#### `startTraining()` enemy-path fix

`handleTrain()` is shared between the friendly and enemy paths.
`startTraining()` determines spawn count via:

```java
final int spawnCount = (model != null) ? model.trainCount(unitType) : 1;
```

For the enemy path, `model` is null (set from `playerRaceModel` which is
only available for the friendly player). The hardcoded fallback of `1` was
coincidentally consistent before the fix (enemy paid per-unit cost 25,
spawned 1). After the fix, enemy pays batch cost 50 but still spawns 1 —
a cost/spawn mismatch.

Fix: use `SC2Data.trainCount()` as the fallback:

```java
final int spawnCount = (model != null) ? model.trainCount(unitType) : SC2Data.trainCount(unitType);
```

This makes both paths consistent: enemy Zerglings pay 50 minerals and
spawn 2 per TrainIntent, matching the corrected cost computation.

---

## #183 — Enemy strategy classifier (Drools CEP, no LLM)

### Scope

Deterministic pattern classification via Drools CEP. Confidence scoring with
monotonic accumulation across ticks. Published via existing ScoutingIntelBroker
dual-stack delivery.

**Level 1 placement:** The classifier runs inside `DroolsScoutingTask` (Level 1),
not as a Level 2 component. Rationale: the classifier consumes the same raw event
data (`unitEvents`, `expansionEvents`, `armyNearBaseEvents`) as the existing
build-order detection rules — it needs per-unit-type counts and raw timing data
that are not available in the summarised L1 output payloads (`PostureUpdate`,
`TimingAlert`, `BuildOrder`). A Level 2 component consuming L1 bus events would
lack the granularity needed for confidence-weighted pattern classification.
The existing build-order fingerprinting (`ZERG_ROACH_RUSH`, `TERRAN_3RAX`,
`PROTOSS_4GATE`) is also L1 — the new classifier is a more sophisticated version
of the same function. Issue #183 text says "Level 2" — this was a planning-stage
assumption that the design phase has resolved. The issue will be updated to
reflect the actual L1 placement.

**Deferred (follow-up issues):**
- LLM fallback for novel/ambiguous builds (#235)
- Replay classification accuracy ≥ 70% acceptance criteria (#236)
- Confidence revision: decay, counter-indication, multi-archetype publishing (#237)
- Commentator/Coach prompt updates for PATTERN_ASSESSMENT (#238)

### Relationship to existing build-order detection

The existing `DroolsScoutingTask.drl` produces string labels (ZERG_ROACH_RUSH,
TERRAN_3RAX, PROTOSS_4GATE) via unit-count thresholds calibrated against replay
data. The new classifier runs **alongside** it — not replacing it. The existing
rules stay as a calibration benchmark. Once replay validation confirms the new
classifier matches or exceeds the old detection, a follow-up issue removes the
old rules.

### Data model

#### EnemyArchetype enum (`io.quarkmind.domain`)

```java
public enum EnemyArchetype {
    TERRAN_MARINE_RUSH,
    TERRAN_BIO_TIMING,
    TERRAN_MECH_PUSH,
    TERRAN_BANSHEE_HARASS,
    ZERG_ZERGLING_RUSH,
    ZERG_ROACH_RUSH,
    ZERG_MACRO,
    PROTOSS_GATEWAY_RUSH,
    PROTOSS_CANNON_RUSH,
    PROTOSS_MACRO
}
```

10 archetypes across 3 races — issue asks for ≥8.

#### EnemyPatternAssessment record (`io.quarkmind.domain`)

```java
public record EnemyPatternAssessment(
    EnemyArchetype archetype,
    double confidence,        // 0.0–1.0
    long detectedAtFrame,
    String rationale
) {}
```

#### EvidenceMarker record (`io.quarkmind.plugin.scouting`)

```java
public record EvidenceMarker(EnemyArchetype archetype, double weight, String signal) {}
```

Internal to classification engine — not published externally.

#### PatternClassificationRuleUnit (`io.quarkmind.plugin.scouting`)

```java
public class PatternClassificationRuleUnit implements RuleUnitData {

    private final DataStore<EnemyUnitFirstSeen>  unitEvents         = DataSource.createStore();
    private final DataStore<EnemyExpansionSeen>  expansionEvents    = DataSource.createStore();
    private final DataStore<EnemyArmyNearBase>   armyNearBaseEvents = DataSource.createStore();
    private final DataStore<Double>              gameTimeStore      = DataSource.createStore();

    private final List<EvidenceMarker> evidence = new ArrayList<>();

    public DataStore<EnemyUnitFirstSeen>  getUnitEvents()         { return unitEvents; }
    public DataStore<EnemyExpansionSeen>  getExpansionEvents()    { return expansionEvents; }
    public DataStore<EnemyArmyNearBase>   getArmyNearBaseEvents() { return armyNearBaseEvents; }
    public DataStore<Double>              getGameTimeStore()      { return gameTimeStore; }

    public List<EvidenceMarker> getEvidence() { return evidence; }
}
```

Follows the `ScoutingRuleUnit` pattern: DataStore fields for Drools-managed inputs,
JDK `List` for rule outputs. Per GE-0053, only DataStore and JDK types appear as
field types — `List<EvidenceMarker>` is safe because `Class.forName()` sees only
the erased type `java.util.List`, and `EvidenceMarker` is resolved by the DRL
`import` statement at rule-compile time, not by the CDI bean generator.

### Drools evidence rules

New `PatternClassificationRuleUnit` with its own DRL file
(`PatternClassification.drl`), fired by `DroolsScoutingTask.execute()` after the
existing `ScoutingRuleUnit`.

**Input:** same `unitEvents`, `expansionEvents`, `armyNearBaseEvents` DataStores
from `ScoutingSessionManager`, plus `gameTimeStore` (single-element
`DataStore<Double>` holding the current game time in minutes).

`gameTimeMin` is a DataStore rather than a global because `eval()` +
`accumulate()` in the same rule does not compile in the current Drools version
(generated lambda loses field scope). This is the same constraint documented in
`StrategyRuleUnit` and `StarCraftStrategy.drl` — DataStore pattern matching
avoids the limitation entirely.

**Output:** `List<EvidenceMarker>` (JDK List global, same pattern as
`ScoutingRuleUnit.detectedBuilds`).

Each rule emits weighted evidence for one archetype. Multiple rules per archetype
(evidence accumulates). Multiple archetypes can have evidence simultaneously —
rules are intentionally NOT mutually exclusive. Overlapping signals are expected
(e.g., early Marines + no expansion produces evidence for both
`TERRAN_MARINE_RUSH` and `TERRAN_BIO_TIMING`). The confidence formula naturally
resolves competing hypotheses by ranking archetypes by accumulated evidence
weight. Counter-indication rules (negative evidence, e.g., expansion detected
reduces rush confidence) are deferred to #237.

Example:

```
package io.quarkmind.plugin.scouting;
unit PatternClassificationRuleUnit;

import io.quarkmind.domain.UnitType;
import io.quarkmind.domain.EnemyArchetype;

rule "Evidence: Marine Rush — high marine count early"
when
    accumulate(/unitEvents[this.type() == UnitType.MARINE]; $count: count(); $count >= 5)
    /gameTimeStore[this < 4.0]
then
    evidence.add(new EvidenceMarker(EnemyArchetype.TERRAN_MARINE_RUSH, 0.6,
        $count + " Marines before 4min"));
end

rule "Evidence: Marine Rush — no expansion"
when
    not /expansionEvents
    /unitEvents[this.type() == UnitType.MARINE]
then
    evidence.add(new EvidenceMarker(EnemyArchetype.TERRAN_MARINE_RUSH, 0.3,
        "No expansion with Marines"));
end
```

### Confidence computation (Java)

After `ruleUnit.fire()`, Java aggregates evidence per archetype:

**Per-tick confidence:** `1 - ∏(1 - weight_i)` — probability-of-at-least-one
formula. Two 0.5 weights → 0.75. Naturally caps at 1.0.

**Cross-tick accumulation:** `max(cumulative, thisTick)` — confidence only goes
up. Stored in `Map<EnemyArchetype, Double> cumulativeConfidence` on
`DroolsScoutingTask`.

**No decay:** seeing Marines doesn't become less meaningful over time. Sustained
production confirms the hypothesis. This is the correct MVP behaviour —
monotonic confidence maximises sensitivity for early warning, which is the
primary value proposition of the first version.

**Known limitation:** monotonic confidence combined with single-winner dispatch
means a stale classification can persist if the enemy transitions. If
ZERGLING_RUSH reaches 0.8 at the 3-minute mark but the enemy expands and
transitions to ZERG_MACRO, the strategy layer continues receiving ZERGLING_RUSH
until ZERG_MACRO accumulates past 0.8. This is an accepted trade-off for the
MVP — confidence revision with decay, counter-indication, and potential
multi-archetype publishing is tracked in #237.

**Dispatch threshold:** 0.3 — low enough for early warning signals.

### Integration

#### ScoutingIntelType and ScoutingIntelPayload extension

New `ScoutingIntelType.PATTERN_ASSESSMENT` enum value.

New sealed variant added to `ScoutingIntelPayload` — the `permits` clause must
be updated to include `PatternAssessment`:

```java
public sealed interface ScoutingIntelPayload
        permits ScoutingIntelPayload.ThreatPosition,
                ScoutingIntelPayload.PostureUpdate,
                ScoutingIntelPayload.TimingAlert,
                ScoutingIntelPayload.ArmySize,
                ScoutingIntelPayload.BuildOrder,
                ScoutingIntelPayload.PatternAssessment {
    // ...

    record PatternAssessment(EnemyPatternAssessment assessment) implements ScoutingIntelPayload {
        public ScoutingIntelType type() { return ScoutingIntelType.PATTERN_ASSESSMENT; }
    }
}
```

Published via existing `publishIntel()` dual-stack path. Delta-only dispatch:
publish when the highest-confidence archetype changes identity, or its
confidence crosses a step threshold (0.3, 0.5, 0.7, 0.9). Only the single
top-ranked assessment is published — consumers see one classification at a time.

#### Preference and dispatch infrastructure

Following the pattern of existing intel types:

1. **`ScoutingIntelPreferences.defaultEnabled()`** — add case:
   `case PATTERN_ASSESSMENT -> true;`

2. **New dispatch preference key** in `ScoutingIntelPreferences`:
   ```java
   public static final PreferenceKey<ScoutingIntelPreference> PATTERN_ASSESSMENT_DISPATCH_ENABLED =
       new PreferenceKey<>("scouting.intel.dispatch", "pattern-assessment.enabled",
           ScoutingIntelPreference.ofBoolean(true), ScoutingIntelPreference::parseBoolean);
   ```

3. **Dispatch gate** in `DroolsScoutingTask.initThresholds()`:
   load `patternAssessmentDispatchEnabled` from preferences.

4. **Dispatch guard** in `DroolsScoutingTask.execute()`:
   gate pattern assessment publishing on `patternAssessmentDispatchEnabled`,
   matching the existing `postureDispatchEnabled` / `buildOrderDispatchEnabled`
   pattern.

5. **`DroolsScoutingTask.resetDispatchState()`** — add `prevAssessment = null;`
   and clear `cumulativeConfidence`.

#### DroolsScoutingTask changes

1. Inject second `RuleUnit<PatternClassificationRuleUnit>` via constructor
2. After existing rule unit fires, call
   `sessionManager.buildPatternRuleUnit(gameTimeMin)` to create a populated
   `PatternClassificationRuleUnit`. This follows the existing
   `buildRuleUnit()` pattern — `ScoutingSessionManager` copies its internal
   buffers into the new rule unit and inserts `gameTimeMin` into the
   `gameTimeStore` DataStore.
3. Fire pattern rules, collect evidence, compute confidence, merge cumulative
4. Publish top assessment via `publishIntel()` if changed
5. New fields: `cumulativeConfidence`, `prevAssessment`,
   `patternAssessmentDispatchEnabled`
6. Reset in `resetDispatchState()`

#### ScoutingSessionManager changes

New method `buildPatternRuleUnit(double gameTimeMin)`:

```java
public PatternClassificationRuleUnit buildPatternRuleUnit(double gameTimeMin) {
    PatternClassificationRuleUnit data = new PatternClassificationRuleUnit();
    unitBuffer.forEach(data.getUnitEvents()::add);
    expansionBuffer.forEach(data.getExpansionEvents()::add);
    armyBuffer.forEach(data.getArmyNearBaseEvents()::add);
    data.getGameTimeStore().add(gameTimeMin);
    return data;
}
```

#### Strategy layer consumption

`DroolsStrategyTask.refreshSubscriptions()` adds `PATTERN_ASSESSMENT` to the
filtered subscription set.

`StrategyRuleUnit` gets new `DataStore<EnemyPatternAssessment> patternStore`
field (unwrapped domain object, consistent with `DataStore<String> postureStore`
and `DataStore<Boolean> timingStore`). `buildRuleUnit()` reads from broker and
unwraps:

```java
broker.current(ScoutingIntelType.PATTERN_ASSESSMENT,
        ScoutingIntelPayload.PatternAssessment.class)
    .map(ScoutingIntelPayload.PatternAssessment::assessment)
    .ifPresent(data.getPatternStore()::add);
```

New imports in `StarCraftStrategy.drl`:

```
import io.quarkmind.domain.EnemyPatternAssessment;
```

New DRL rule in `StarCraftStrategy.drl`:

```
rule "Strategy: Defend — Rush Detected"
    salience 215
when
    /patternStore[this.confidence() >= 0.7,
                  this.archetype().name().contains("RUSH")]
then
    strategyDecisions.add("DEFEND");
end
```

Salience 215: between Nexus Under Attack (220) and Timing Attack (210). Rush
detection overrides timing alerts but not direct Nexus threats.

#### Commentator/Coach integration

Payload delivery is automatic — `dispatchToAdvisory()` serialises
`PatternAssessment` as JSON with a self-describing type field and sends it via
the existing Qhorus channel. Commentator and Coach LLM observers receive the
JSON without new wiring.

However, observer system prompts should be updated to reference the new
`PATTERN_ASSESSMENT` intel type so the LLM can reason about enemy strategy
classifications effectively. This is tracked in #238.

### Testing

#### #234 tests

- `ZergEmulatedGameTest`: train Zergling pair, assert 50 minerals deducted, 2
  units spawned. Test rejection: 30 minerals → training rejected.
- `ZergEmulatedGameTest` (enemy path): issue Zergling TrainIntent via enemy
  `applyIntent` overload with sufficient minerals. Assert: 2 Zerglings spawned
  (not 1), 50 minerals deducted (not 25). Regression guard for the
  `startTraining()` fallback fix — reverting to hardcoded `1` must fail this
  test.

#### #183 tests

**Plain JUnit (no Quarkus):**

- `PatternConfidenceTest` — confidence formula: single weight, multiple weights,
  cumulative merge, threshold gating. Pure Java computation, no Drools dependency.

**`@QuarkusTest` (requires Drools rule unit infrastructure — GE-0053):**

- `PatternClassificationRuleUnitTest` — fire DRL against crafted event sets per
  archetype. Cover: each of 10 archetypes, mixed competing signals, empty events.
  `@QuarkusTest` required: `DataSource.createStore()` needs Quarkus build-time
  init, consistent with `DroolsScoutingTaskIT`, `DroolsStrategyTaskTest`, and
  all other rule unit tests in the project.
- `DroolsScoutingTaskIT` additions — parallel comparison: 7+ Marines produces
  both "TERRAN_3RAX" (old) and `TERRAN_MARINE_RUSH` assessment (new).
- `DroolsStrategyL2L3Test` additions — RUSH confidence ≥ 0.7 → DEFEND.

**Replay validation (`mvn test -Preport`):**

- `ScoutingCalibrationTest` extension — log pattern classifications alongside
  existing build-order labels at 3-min mark. Data collection for calibration,
  no pass/fail assertions initially (accuracy threshold tracked in #236).

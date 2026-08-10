# Confidence Revision — Decay, Counter-Indication, Multi-Archetype Publishing

**Issue:** #237
**Date:** 2026-07-14

## Problem

The pattern classification system uses monotonic cumulative confidence (`Math::max`). Confidence never decreases. If ZERGLING_RUSH reaches 0.8 at minute 2 and the enemy transitions to macro, the stale 0.8 persists indefinitely.

Root cause: the system has two memory layers — evidence buffers (3-minute window) and cumulative map (infinite via `Math::max`). When evidence ages out of the buffers, the assessment persists anyway.

## Design

Three complementary mechanisms replace the monotonic model.

### §1 Frame-Based Decay

`PatternClassifier.mergeCumulative` gains frame-based decay. Before merging this tick's positive evidence, all cumulative values decay by `DECAY_PER_FRAME ^ framesElapsed`.

```
DECAY_PER_FRAME = 0.99948  (half-life ~1344 frames ≈ 60 seconds at 22.4 FPS)
NOISE_FLOOR = 0.01
```

Signature: `mergeCumulative(cumulative, thisTick, currentFrame, lastFrame)`.

When `lastFrame < 0` (first invocation), decay is skipped — there is no prior merge to decay from.

After merge, entries below `NOISE_FLOOR` are removed. Frame-based (not tick-based) ensures consistent behavior across all profiles.

### §2 Counter-Indication

New record: `ConfidenceRevision(EnemyArchetype archetype, double dampingFactor, String reason)` in `io.quarkmind.plugin.scouting`.

`dampingFactor` is in (0, 1). Applied multiplicatively per frame: `cumulative[arch] *= dampingFactor`. Multiplicative dampening naturally self-limits and has a well-defined effective half-life: `ln(2) / (ln(1/dampingFactor) × FRAMES_PER_SECOND)` seconds.

New `List<ConfidenceRevision>` field on `PatternClassificationRuleUnit`, populated by DRL rules:

- **Expansion vs rush:** `exists /expansionEvents` → dampingFactor 0.997 for same-race `*_RUSH` archetypes (~10s half-life when active)
- **Tech transition vs rush:** high-tech unit seen (COLOSSUS, BATTLECRUISER, BROOD_LORD, SIEGE_TANK) → dampingFactor 0.998 for same-race `*_RUSH` archetypes (~15s half-life)
- **Prediction window expiry:** `gameTimeMin > 5.0` and `not exists /armyNearBaseEvents` → dampingFactor 0.998 for all `*_RUSH` archetypes (~15s half-life). Addresses issue #237 design decision 3 (attack-didn't-materialise threshold).

New static method `PatternClassifier.applyRevisions(cumulative, revisions, framesElapsed)` applies all dampening factors. Each factor is raised to `framesElapsed` before application: `cumulative[arch] *= Math.pow(dampingFactor, framesElapsed)`. This ensures frame-accurate dampening regardless of tick interval, consistent with how `mergeCumulative` handles passive decay via `DECAY_PER_FRAME ^ framesElapsed`. Separate from `mergeCumulative` to preserve single responsibility and independent testability.

Application order in `DroolsScoutingTask`:

**Frame capture:** `DroolsScoutingTask.execute()` updates `lastFrame = frame` early (line 183) for LevelEvent tagging, ~90 lines before the pattern classification block. A local `long prevFrame = lastFrame` must be captured before this update and used for all frame-elapsed calculations below.

1. `PatternClassifier.mergeCumulative(cumulative, thisTick, currentFrame, prevFrame)` — decay all cumulative values, then merge positive evidence (`Math::max`)
2. `PatternClassifier.applyRevisions(cumulative, patternData.getRevisions(), currentFrame - prevFrame)` — apply counter-indication dampening

Negative evidence is separate from positive evidence — the `1 - Π(1 - weight)` formula is unchanged.

**Interaction with positive evidence rules:** When counter-indication conditions hold (e.g., expansion detected), existing positive rules that depend on the same condition NOT holding (e.g., "No expansion — rush likely") stop firing. No fresh positive evidence enters the merge step, so multiplicative dampening accelerates confidence decline beyond passive decay alone. Exact dampingFactor values will be calibrated via the replay validation test (AC3).

**Compounding behavior:** When multiple counter-indication rules fire for the same archetype, their dampingFactors multiply per frame. Example: expansion (0.997) × tech (0.998) × prediction window (0.998) = 0.993 effective per-frame factor, half-life ~4.4s. This is intentional — all three conditions simultaneously is strong evidence against a rush. Calibration should consider combined effects across rule combinations, not individual dampingFactor values in isolation.

`EnemyArchetype` gains a `Race race()` constructor parameter, matching `UnitType`'s existing pattern:

```java
public enum EnemyArchetype {
    TERRAN_MARINE_RUSH(Race.TERRAN), TERRAN_BIO_TIMING(Race.TERRAN),
    TERRAN_MECH_PUSH(Race.TERRAN), TERRAN_BANSHEE_HARASS(Race.TERRAN),
    ZERG_ZERGLING_RUSH(Race.ZERG), ZERG_ROACH_RUSH(Race.ZERG),
    ZERG_MACRO(Race.ZERG),
    PROTOSS_GATEWAY_RUSH(Race.PROTOSS), PROTOSS_CANNON_RUSH(Race.PROTOSS),
    PROTOSS_MACRO(Race.PROTOSS);

    private final Race race;
    EnemyArchetype(Race race) { this.race = race; }
    public Race race() { return race; }
}
```

### §3 Multi-Archetype Publishing

`topAssessment(cumulative, frame)` → `allAssessments(cumulative, frame)` returning `List<EnemyPatternAssessment>`, sorted by confidence descending, filtered to `>= DISPATCH_THRESHOLD`.

`ScoutingIntelPayload.PatternAssessment` wraps `List<EnemyPatternAssessment>` instead of a single assessment.

`DroolsScoutingTask` replaces `prevAssessment: EnemyPatternAssessment` with `prevAssessments: List<EnemyPatternAssessment>`. Dispatch triggers when any archetype appears or disappears above `DISPATCH_THRESHOLD`, or any assessment crosses a threshold boundary (0.3, 0.5, 0.7, 0.9 — in either direction).

`DroolsStrategyTask.buildStrategyData()` iterates the assessment list and adds each `EnemyPatternAssessment` to `patternStore` individually. The `DataStore<EnemyPatternAssessment>` type is unchanged — strategy DRL rules can pattern-match on multiple assessments simultaneously.

## Files Changed

| File | Change |
|------|--------|
| `PatternClassifier.java` | Decay in `mergeCumulative`, new `applyRevisions`, new `allAssessments`, remove `topAssessment` |
| `PatternClassificationRuleUnit.java` | Add `List<ConfidenceRevision> revisions` field + getter |
| `PatternClassification.drl` | Counter-indication rules (expansion vs rush, tech vs rush, prediction window expiry) |
| `ConfidenceRevision.java` | New record |
| `EnemyArchetype.java` | Add `Race race()` constructor parameter (needed by tech-transition rule) |
| `ScoutingIntelPayload.java` | `PatternAssessment` wraps `List<EnemyPatternAssessment>` |
| `DroolsScoutingTask.java` | Capture `prevFrame` before `lastFrame` update, pass to `mergeCumulative` and `applyRevisions`, use `allAssessments`, full-list change detection |
| `DroolsStrategyTask.java` | Iterate assessment list, add each to `patternStore` |
| `PatternConfidenceTest.java` | Decay tests, counter-indication tests, multi-archetype tests |
| `PatternClassificationRuleUnitTest.java` | Counter-indication DRL rule tests |
| `PatternClassificationCalibrationTest.java` | Update to use `allAssessments`, pass frame parameters to `mergeCumulative` |

## Testing

- Decay: cumulative decreases when no fresh evidence; rate matches half-life spec
- Decay + evidence: steady evidence produces stable confidence (no oscillation)
- Counter-indication: expansion reduces rush confidence immediately
- Counter-indication + decay: combined effect on transitioning enemy
- Multi-archetype: multiple assessments returned when multiple archetypes above threshold
- Prediction window expiry: rush confidence drops fast when game time exceeds window with no attack
- Calibration: `PatternClassificationCalibrationTest` accuracy ≥ 75%. Document current baseline before changes.

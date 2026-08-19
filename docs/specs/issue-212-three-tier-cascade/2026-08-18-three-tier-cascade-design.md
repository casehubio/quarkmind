# Three-Tier Confidence Cascade — Drools → ONNX → LLM Routing

Issue: casehubio/quarkmind#212
Epic: casehubio/quarkmind#208

## Overview

Wire a `CascadingPatternClassifier` CDI bean that orchestrates three classification tiers — Drools (fast, deterministic), ONNX (fast, learned), and LLM (slow, flexible) — routing based on configurable confidence thresholds. The cascade publishes a single `PatternAssessment` per classification cycle, regardless of which tier resolves it.

## Current State

All classification logic is embedded inline in `DroolsScoutingTask.execute()` (lines 306–376):

1. **Drools evidence** (lines 306–319): `PatternClassificationRuleUnit` fires, `PatternClassifier` static methods compute per-archetype confidences via multiplicative combination, merge into a cumulative `EnumMap<StrategyArchetype, Double>`, and apply decay + counter-indication revisions.
2. **Assessment publishing** (lines 321–332): Assessments above `DISPATCH_THRESHOLD` (0.3) are published via `ScoutingIntelBroker`.
3. **LLM fallback trigger** (lines 335–362): When all cumulative confidences are below threshold and time/cooldown gates pass, a trigger is written to CaseContext.
4. **LLM result integration** (lines 364–376): On a later tick, the async LLM result is read from CaseContext and injected into cumulative confidence.

`PatternClassifier` is a static utility class (not CDI, not injectable). There is no ONNX tier — the trained model exists in neocortex (#76 closed) but no quarkmind integration exists.

## Design

### Architecture

```
DroolsScoutingTask
  │
  │  fires PatternClassificationRuleUnit → evidence markers
  │  builds ONNX features via StrategyFeatureExtractor
  │
  ├──► CascadingPatternClassifier.classify(evidence, features, frame, prevFrame, ctx)
  │       │
  │       ├── Tier 1: Drools confidence (from evidence markers)
  │       │     confidence ≥ droolsThreshold → return assessment(DROOLS)
  │       │
  │       ├── Tier 2: ONNX classification (from feature tensor)
  │       │     available && confidence ≥ onnxThreshold → return assessment(ONNX)
  │       │
  │       ├── Tier 3: LLM fallback trigger (async, fire-and-forget)
  │       │     gates pass → write trigger to CaseContext, return empty
  │       │
  │       └── LLM result integration (on subsequent tick)
  │             CaseContext has result → inject into cumulative → return assessment(LLM)
  │
  └──► publish assessments via ScoutingIntelBroker
```

### New Classes

#### `CascadingPatternClassifier` — `io.quarkmind.plugin.scouting.CascadingPatternClassifier`

`@ApplicationScoped` CDI bean. Procedural orchestrator — not polymorphic. Knows the specifics of each tier.

**Owns:**
- `EnumMap<StrategyArchetype, Double> cumulativeConfidence` — moved from `DroolsScoutingTask`
- Confidence decay and revision application (absorbed from `PatternClassifier` static methods)
- Tier routing logic with configurable thresholds
- LLM fallback state: cooldown tracking, last trigger frame, result integration
- Per-tier metrics (Micrometer counters and histograms)

**Constructor dependencies:**
- `Instance<TensorClassifier>` — optional ONNX tier via CDI programmatic lookup. Checked with `instance.isResolvable()`. When the ONNX model is absent or neocortex inference modules are excluded, the tier is skipped with a WARN log at startup. (D5)
- `@ConfigProperty` for confidence thresholds
- No dependency on `ChatModel` or `LlmPatternClassifierWorkerFactory` — the LLM tier uses the existing CaseContext trigger/polling pattern, not direct invocation.

**Primary method:**

```java
ClassificationResult classify(
    List<EvidenceMarker> evidence,
    Map<String, float[][]> onnxFeatures,  // null when extractor unavailable
    long frame,
    long prevFrame,
    CaseContext ctx                        // for LLM trigger/result via CaseContext keys
)
```

Returns `CascadeResult` — a new record wrapping `List<PatternAssessment>` (the assessments above dispatch threshold) and a `boolean llmTriggered` flag. (Named `CascadeResult` to avoid collision with neocortex's `ClassificationResult`.)

**Cascade logic:**

```
1. Apply decay to cumulative confidence (frame-based, DECAY_PER_FRAME = 0.99948)
2. Merge Drools evidence into cumulative (from evidence markers)
3. Apply counter-indication revisions
4. Check Drools confidence: if any archetype ≥ droolsThreshold (0.7) → build assessments, return
5. If ONNX available and features non-null:
     Run TensorClassifier.classify(features) → neocortex ClassificationResult
     Merge ONNX top-1 into cumulative
     If any archetype ≥ onnxThreshold (0.5) → build assessments, return
6. Check LLM result from previous tick (CaseContext polling)
     If result present → integrate into cumulative, build assessments, return
7. If LLM gates pass (all confidences < llmFallbackThreshold, time gate, cooldown)
     → write trigger to CaseContext, set llmTriggered = true
8. Build assessments from whatever cumulative state exists (may be empty)
```

Steps 1–3 always run (decay and evidence merging happen every tick). Steps 4–7 are the cascade routing.

#### `StrategyFeatureExtractor` — `io.quarkmind.plugin.scouting.StrategyFeatureExtractor`

Plain Java class (no CDI — instantiated by the cascade or scouting task). Transforms game state observations into the fixed-length tensor expected by the 1D-CNN model from neocortex#76.

**Input:** game state observations available from `ScoutingSessionManager` — observed unit types, counts, timings, game frame.

**Output:** `Map<String, float[][]>` matching the ONNX model's named input tensors.

**Test surface:** unit tests verify feature encoding correctness against known game state snapshots, independently of cascade routing. Feature schema must align with neocortex#76's training pipeline.

#### `AssessmentSource` enum — `io.quarkmind.domain.AssessmentSource`

```java
public enum AssessmentSource { DROOLS, ONNX, LLM }
```

#### `PatternAssessment` record — modified

```java
public record PatternAssessment(
    StrategyArchetype archetype,
    double confidence,
    long detectedAtFrame,
    String rationale,
    AssessmentSource source    // NEW — which tier resolved this classification
) {}
```

All existing call sites (Java code, DRL rules) must be updated in the same commit. DRL rules that pattern-match on `PatternAssessment` (e.g., `StarCraftStrategy.drl`, `DominanceWeightRuleUnit`'s `patternStore`) need the constructor updated but no semantic changes — they match on `archetype()` and `confidence()`, not on `source()`.

### Changes to Existing Classes

#### `DroolsScoutingTask` — lines 306–376 extracted

The classification block (lines 306–376) is replaced with:

```java
CascadeResult result = cascadingClassifier.classify(
    patternData.getEvidence(),
    featureExtractor != null ? featureExtractor.extract(sessionManager, gameTimeMin) : null,
    frame, prevFrame, ctx);

if (!result.assessments().isEmpty()) {
    ctx.set(QuarkMindCaseFile.SCOUTING_FINAL_ASSESSMENT, result.assessments());
    boolean changed = assessmentsChanged(prevAssessments, result.assessments());
    if (changed && patternAssessmentDispatchEnabled
            && (broker.isSubscribed(ScoutingIntelType.PATTERN_ASSESSMENT) || advisoryEnabled)) {
        prevAssessments = result.assessments();
        publishIntel(new PatternAssessmentPayload(result.assessments()));
    }
}
```

**Fields removed from `DroolsScoutingTask`:**
- `cumulativeConfidence` → moved to `CascadingPatternClassifier`
- `lastLlmFallbackFrame` → moved to `CascadingPatternClassifier`
- `lastProcessedLlmArchetype` → moved to `CascadingPatternClassifier`
- `llmFallbackConfidenceThreshold`, `llmFallbackMinGameTimeFrames`, `llmFallbackCooldownFrames` → config in cascade

**Fields retained in `DroolsScoutingTask`:**
- `prevAssessments` — dispatch change detection stays with the publisher
- `patternAssessmentDispatchEnabled` — dispatch toggle stays with the publisher
- `llmFallbackEnabled` — passed through to cascade or read by cascade from config

#### `PatternClassifier` — removed

The static utility class is absorbed into `CascadingPatternClassifier`. Its five methods (`computeTickConfidence`, `computeAllConfidences`, `mergeCumulative`, `applyRevisions`, `allAssessments`) become instance methods or private helpers in the cascade. The constants (`DISPATCH_THRESHOLD`, `DECAY_PER_FRAME`, `NOISE_FLOOR`) move with them.

### Configuration

```properties
# Cascade confidence thresholds
quarkmind.classifier.drools.confidence-threshold=0.7
quarkmind.classifier.onnx.confidence-threshold=0.5

# LLM fallback (existing config keys, now read by cascade)
quarkmind.classifier.llm.fallback.confidence-threshold=0.5
quarkmind.classifier.llm.fallback.min-game-time-frames=2160
quarkmind.classifier.llm.fallback.cooldown-frames=500

# ONNX model (neocortex inference-quarkus config)
casehub.inference.models.strategy-classifier.model-path=${QUARKMIND_MODEL_DIR}/strategy-classifier.onnx
```

### Graceful Degradation

The cascade degrades gracefully when tiers are unavailable (D5):

| Configuration | Active tiers | Behaviour |
|---|---|---|
| Full (Drools + ONNX + LLM) | All three | Cascade as designed |
| No ONNX model | Drools + LLM | `Instance<TensorClassifier>.isResolvable() == false`, ONNX step skipped |
| No LLM ChatModel | Drools + ONNX | `llmFallbackEnabled=false`, LLM trigger never fires |
| Drools only | Drools | Current behaviour, no regression |

### Metrics

Per-tier Micrometer metrics injected via `MeterRegistry`:

| Metric | Type | Tags |
|---|---|---|
| `quarkmind.classifier.invocations` | Counter | `tier=drools\|onnx\|llm` |
| `quarkmind.classifier.resolutions` | Counter | `tier=drools\|onnx\|llm` |
| `quarkmind.classifier.latency` | Timer | `tier=drools\|onnx\|llm` |

**Hit rate** = `resolutions[tier] / sum(resolutions)` — computed from counters, not a separate metric. Satisfies #213's tier hit rate analysis requirement.

### Dependencies

**New Maven dependencies in `quarkmind-sc2/pom.xml`:**

```xml
<dependency>
    <groupId>io.casehub.neocortex</groupId>
    <artifactId>casehub-neocortex-inference-api</artifactId>
</dependency>
<dependency>
    <groupId>io.casehub.neocortex</groupId>
    <artifactId>casehub-neocortex-inference-tasks</artifactId>
</dependency>
<dependency>
    <groupId>io.casehub.neocortex</groupId>
    <artifactId>casehub-neocortex-inference-quarkus</artifactId>
</dependency>
<!-- Runtime only — ONNX native libs -->
<dependency>
    <groupId>io.casehub.neocortex</groupId>
    <artifactId>casehub-neocortex-inference-runtime</artifactId>
    <scope>runtime</scope>
</dependency>
```

### Testing Strategy

**Unit tests (plain JUnit, no CDI):**

- `CascadingPatternClassifierTest` — cascade routing logic:
  - Drools confidence ≥ 0.7 → assessment with `source=DROOLS`, ONNX not called
  - Drools at 0.5, ONNX at 0.6 → assessment with `source=ONNX`
  - Both below threshold → LLM triggered, no assessment this tick
  - ONNX unavailable → Drools → LLM fallback (two-tier)
  - LLM result integration on subsequent tick → assessment with `source=LLM`
  - Threshold boundary tests: exactly at threshold, epsilon below
  - Decay and revision application (moved from PatternClassifierTest)
- `StrategyFeatureExtractorTest` — feature tensor construction from game state snapshots
- `PatternAssessmentTest` — record with new `source` field

**Integration tests (`@QuarkusTest`):**

- `CascadingPatternClassifierIT` — full CDI wiring with mock tiers:
  - All three tiers available → cascade routes correctly
  - ONNX disabled (no model) → two-tier degradation
  - Verify exactly one assessment per cycle
- Existing `DroolsScoutingTaskIT`, `PatternClassificationCalibrationTest` — must pass with the extracted cascade (no behavioural regression)

## Assumptions

- The neocortex dependency chain (neocortex#76 trained model, neocortex#77 raw tensor SPI) is available, per the epic #208 dependency graph. All three neocortex issues are closed.
- The ONNX model file will be distributed separately (not bundled in the jar). Path configured via `casehub.inference.models.strategy-classifier.model-path`.

## References

- `DroolsScoutingTask.java:306-376` — current inline classification
- `PatternClassifier.java` — static utility to absorb
- `LlmPatternClassifierWorkerFactory.java` — LLM advisory worker factory
- `PatternAssessment.java` — domain record to extend
- `ScoutingIntelBroker.java` — assessment publisher
- neocortex `TensorClassifier.java` — ONNX classification API
- neocortex `InferenceModel.java` — ONNX Runtime SPI
- neocortex `InferenceModelProducer.java` — CDI integration
- Issue #212 — acceptance criteria
- Issue #213 — tier hit rate analysis (downstream consumer of `AssessmentSource`)
- Protocol `competing-strategy-implementations-concrete-injection.md` — CDI injection patterns
- Protocol `scouting-consumer-postconstruct-required.md` — broker subscription init

---
layout: post
title: "Wiring the Middle Brain"
date: 2026-08-19
entry_type: note
subtype: diary
projects: [quarkmind]
tags: [onnx, inference, cascade, pattern-classification, neocortex]
series: issue-212-three-tier-cascade
---

# Wiring the Middle Brain

The [previous entry](2026-08-18-the-cascade-that-reviewed-itself.md) left the cascade half-wired. `CascadingPatternClassifier` ran Drools evidence through cumulative confidence and triggered the LLM fallback — functionally identical to the pre-refactor inline code, just properly extracted. The ONNX tier was a placeholder comment on line 85.

The gap it fills is specific: builds that are *variations* of known archetypes but don't match hand-authored Drools rules. A 12-pool with an unusual gas timing looks like macro to Drools because no rule fires for the exact signature. The trained model has seen thousands of these — it classifies by learned feature patterns, not authored rules.

## Three inference paradigms in one tick

The cascade now orchestrates three different classification mechanisms at game-loop speed:

- **Drools** (tier 1): fires in microseconds, deterministic, recognises exactly what the rules describe. Confidence threshold 0.7.
- **ONNX** (tier 2): runs a `TensorClassifier` from casehub-neocortex over a feature tensor — unit type counts and game time encoded by `StrategyFeatureExtractor`. Confidence threshold 0.5. Runs when Drools is below threshold and the model is available.
- **LLM** (tier 3): async fire-and-forget. Triggers when both deterministic tiers are below confidence. Result integrates on a later tick via CaseContext polling.

Each assessment is tagged with `AssessmentSource` — DROOLS, ONNX, or LLM — so tier hit rate analysis against the replay corpus is a counter query, not a pipeline.

## The optionality problem

The ONNX tier might not be there. The model file might not exist on disk. The neocortex inference runtime might not be on the classpath. The cascade has to degrade gracefully through all of these states without crashing at startup.

CDI's `Instance<InferenceModel>` handles this cleanly. The cascade constructor takes `@Inference("strategy-classifier") Instance<InferenceModel>` and probes it:

```java
if (onnxModelInstance.isResolvable()) {
    try {
        var model = onnxModelInstance.get();
        var labels = Arrays.stream(StrategyArchetype.values())
                .map(Enum::name).toList();
        resolved = new TensorClassifier(model, labels);
    } catch (Exception e) {
        log.warnf("[CASCADE] ONNX tier unavailable — %s", e.getMessage());
    }
}
```

`isResolvable()` returns true when the CDI producer exists — it doesn't guarantee the model file is configured. The `get()` call is where the config lookup actually happens. Wrapping it in try/catch gives the cascade three-state degradation: full three-tier, Drools + LLM (no model), or Drools-only (no LLM config either). The cascade doesn't know or care which state it's in — it just skips null tiers.

Unit tests pass `null` for the classifier and verify the cascade falls through to LLM. Tests with a mock `InMemoryInferenceModel` verify ONNX routing resolves with the correct `AssessmentSource`.

## What this opens up

Issue #213 can now run the cascade against the IEM10 and AI Arena replay datasets and measure what the ONNX model adds over Drools alone. Micrometer counters on each tier — `quarkmind.classifier.invocations` and `quarkmind.classifier.resolutions` tagged by tier — make this a Grafana query rather than a log-scraping exercise. The feature tensor encoding in `StrategyFeatureExtractor` is deliberately simple (unit type counts + game time) — calibration against the neocortex training pipeline will refine it once we have the model accuracy numbers.

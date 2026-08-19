---
title: "The Cascade That Reviewed Itself"
date: 2026-08-18
type: diary
tags: [design, pattern-classification, onnx, cascade, adversarial-review]
---

# The Cascade That Reviewed Itself

QuarkMind's pattern classification has been two tiers since July: Drools CEP fires evidence markers for known archetypes, and an LLM fallback catches anything Drools can't reach sufficient confidence on. The gap between them — builds that are variations of known archetypes but don't match hand-authored rules — is where a trained model belongs. Issue #212 wires the ONNX middle tier into a `CascadingPatternClassifier` that routes Drools → ONNX → LLM based on confidence thresholds.

The interesting part wasn't the cascade logic. It was the design process that shaped it.

## The provisional assessment that didn't survive review

I originally chose a hybrid approach for the LLM async path: when neither Drools nor ONNX reaches confidence threshold, publish the best-available assessment as "provisional" and trigger the LLM. Consumers get something immediately rather than a gap. The LLM result upgrades or replaces the provisional on a later tick.

The adversarial decision review killed it in round 1. The reviewer pointed to #212's acceptance criterion: "ScoutingIntelBroker receives exactly one `EnemyPatternAssessment` per classification cycle." A provisional assessment followed by an LLM-confirmed assessment is two assessments per cycle. The reviewer was right — we'd changed a hard constraint into a soft one and hoped consumers would cope.

We reverted to fire-and-forget. The argument that sold it: adding the ONNX tier dramatically shrinks the window where no assessment is available. Most classifications will resolve synchronously through Drools or ONNX. The few that fall through to LLM are genuinely ambiguous builds — consumers already handle gaps during early game and novel compositions.

## Six decisions, three rounds, two new decisions surfaced by the review

The review caught genuine gaps in the original design. ONNX tier optionality — what happens when the model file is missing — was an implicit assumption that needed an explicit CDI decision (`Instance<TensorClassifier>` programmatic lookup, D5). The naming collision between the existing `PatternClassifier` static utility and the issue's aspirational "all implement `PatternClassifier`" interface needed resolving rather than ignoring (D6 — absorb the utility, reject the polymorphic design).

## The extraction

Implementation of Batches 1 and 2 went cleanly. The `PatternAssessment` record gained an `AssessmentSource` field — 63 constructor call sites across 13 test files plus 2 production files and a DRL import. The `PatternClassifier` static utility class (5 methods, 3 constants) was absorbed into `CascadingPatternClassifier` as instance methods. The LLM fallback trigger and result integration moved from `DroolsScoutingTask` into the cascade.

The refactor reduced `DroolsScoutingTask.execute()` by about 60 lines. What remains is observation processing, CEP rule firing, and a single `cascadingClassifier.classify()` call — scouting does scouting, the cascade does classification.

## What's left

Batch 3 wires the ONNX tier: neocortex inference Maven dependencies, a `StrategyFeatureExtractor` for tensor construction, the `Instance<TensorClassifier>` CDI integration, and Micrometer per-tier metrics. The cascade currently runs as Drools-only with the LLM fallback — functionally identical to before the refactor, just properly extracted. The ONNX tier slots in at the placeholder in `classify()`.

After #212 closes, #213 runs the three-tier cascade against the IEM10 and AI Arena replay datasets to measure what the ONNX model actually adds over Drools alone. The `AssessmentSource` enum makes tier hit rate analysis trivial — count assessments by source across the corpus.

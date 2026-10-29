# ADR-0010: Bayesian Evidence Fusion Replaces Multi-Class ONNX

**Status:** Accepted
**Date:** 2026-10-18
**Issue:** #411

## Context

The CascadingPatternClassifier used a three-tier cascade: Drools rules (tier 1) -> multi-class ONNX model (tier 2) -> LLM fallback (tier 3). The ONNX tier used per-race multi-class classifiers (TensorClassifier) to map game-state features to a single StrategyArchetype. This approach had three problems:

1. Multi-class models required all archetype labels in a single training run -- adding a new archetype meant retraining
2. Binary detectors (trained per-archetype) showed complementary signals (correlations 0.2-0.6) but multi-class collapsed them to a single prediction
3. No principled way to combine ONNX output with Drools evidence -- they ran independently

## Decision

Replace the multi-class ONNX tier with Bayesian evidence fusion through a standalone Junction Tree:

- Extract the Bayesian Junction Tree from `drools-beliefs` into `quarkmind-core` (32 files, zero external dependencies)
- `EvidenceFusionEngine` wraps the BJT with a domain-agnostic interface
- `DetectorOrchestrator` fuses binary detector outputs (soft evidence) and Drools markers (likelihood on the Strategy node) through a Naive Bayes star topology
- `TemporalAssessmentTracker` replaces exponential decay with evidence windowing for temporal stability
- CPTs derived from training metrics (P(detector=HIGH | strategy=archetype))

## Consequences

- Binary detectors are independently trained and pluggable -- adding a new detector requires no retraining
- Fusion outperforms best individual detector in 8/9 phase x matchup combinations (+6.8% avg accuracy)
- The BJT extraction is standalone and reusable beyond SC2 classification
- Multi-class ONNX infrastructure removed from the production path (TensorClassifier, OnnxLabelMapping, @Inference annotations)
- OnnxLabelMapping retained for legacy calibration tests until those are retired

## Superseded (partial)

The `TemporalAssessmentTracker` windowing approach has been superseded by
decay-based temporal blending (#471). Windowing was correct at the time
but transition-aware priors and phase-window decay make decay blending
more appropriate for strategy evolution where recent evidence should
dominate.

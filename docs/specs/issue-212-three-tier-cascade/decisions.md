## D1: Extract classification into CascadingPatternClassifier vs inline ONNX tier

**Choice:** Extract all classification logic from `DroolsScoutingTask` into a new `CascadingPatternClassifier` CDI bean
**Alternatives:**
- Inline ONNX tier alongside existing Drools and LLM code in `DroolsScoutingTask.execute()` — simpler but conflates scouting and classification responsibilities, makes cascade routing harder to test
**Rationale:** Single responsibility (scouting vs classification), testability (threshold boundary tests without CDI context), graceful degradation (tier availability is an orchestrator concern), and the async LLM state management is complex enough to warrant its own home. Cumulative confidence state moves with the classifier.
**Trade-offs:** More classes, an additional CDI injection in DroolsScoutingTask, slightly more indirection in the classification flow
**Sources:** `DroolsScoutingTask.java:306-376` (current inline classification), `PatternClassifier.java` (static utility), `LlmPatternClassifierWorkerFactory.java` (async LLM)
**Exploration:** quick
**Status:** captured

**Design constraint:** The cascade is a procedural orchestrator, NOT a polymorphic chain. The three tiers are too different (rule evidence vs tensors vs async advisory workers) for a common interface. The bean knows each tier's specifics. This intentionally diverges from issue #212's aspirational language ("all implement PatternClassifier") — see D6 for the explicit naming and polymorphism resolution.

## D2: Feature extraction boundary — inside cascade vs separate class

**Choice:** Separate `StrategyFeatureExtractor` class for ONNX tensor construction
**Alternatives:**
- Feature extraction inside `CascadingPatternClassifier` — self-contained but cascade tests need game state fixtures just to test routing logic
- Feature extraction in neocortex, co-located with the training pipeline that defines the feature schema — rejected because the features are game-domain-specific (observed buildings, units, army composition, game frame) and require quarkmind's domain types. Neocortex provides the inference SPI and model, not game-state-to-tensor conversion. Schema drift is mitigated by a shared encoding spec (JSON or protobuf) as recommended by #211, not by moving the extraction code.
**Rationale:** Feature extraction is its own concern — correctness of encoding, alignment with the training pipeline's feature format, and numerical stability are all testable independently. Separating it means cascade routing tests use pre-built tensors, and feature tests use game state fixtures without cascade wiring.
**Trade-offs:** One more class; the feature schema must be kept in sync with neocortex's training pipeline. A shared encoding spec (per #211's recommendation) mitigates this.
**Sources:** neocortex#76 (trained 1D-CNN defines expected features), neocortex#77 (raw tensor SPI — `InferenceModel`), #211 (`ObservationToTensorConverter` — the actual integration surface for tensor construction)
**Exploration:** quick
**Status:** revised (R1-01, R1-05: removed phantom TensorClassifier.java reference, added neocortex co-location as evaluated alternative, documented schema drift mitigation)

## D3: LLM async handling — fire-and-forget vs hybrid provisional vs CDI event

**Choice:** Fire-and-forget — no assessment when all tiers below threshold; LLM fires asynchronously and result integrates on a later tick via cumulative confidence
**Alternatives:**
- Hybrid provisional (publish best-effort provisional assessment + trigger LLM) — rejected: violates #212's "exactly one assessment per classification cycle" acceptance criterion; adds provisional→confirmed state transitions that downstream consumers must handle; consumers already operate without classification during early game, so the gap is tolerable
- CDI event callback for LLM result — more reactive but adds complexity for marginal latency gain over CaseFile polling
**Rationale:** The existing fire-and-forget pattern (trigger key to CaseContext, worker runs async, result read back on next tick via `processLlmFallbackResult`, integrated into cumulative confidence) is proven and game-loop-friendly. The three-tier cascade with Drools + ONNX significantly reduces the window where no assessment is available — most classifications are resolved synchronously. The few cases that fall through to LLM are genuinely ambiguous, and consumers already handle gaps.
**Trade-offs:** No classification output for the tick(s) while LLM is in flight. This is acceptable — consumers are designed for absence of assessments (early game, novel builds).
**Sources:** `DroolsScoutingTask.java:335-376` (current async LLM flow), `processLlmFallbackResult` (CaseContext polling pattern), issue #212 AC ("exactly one EnemyPatternAssessment per classification cycle")
**Exploration:** quick
**Status:** revised (R1-02: changed from hybrid provisional to fire-and-forget to satisfy #212 AC and preserve the proven existing async pattern)
**Depends on:** D1 (cascade owns LLM fallback state)

## D4: Source tracking representation — enum field vs separate types

**Choice:** Add `AssessmentSource` enum field to `PatternAssessment`
**Alternatives:**
- Separate `ProvisionalAssessment` and `ConfirmedAssessment` record types — no longer relevant since D3 revision removes provisional semantics
- No source field, track tier hit rates via cascade-level metrics — indirect and requires additional plumbing; per-assessment source is simpler and more composable
**Rationale:** The `source` enum (`DROOLS`, `ONNX`, `LLM`) records which tier resolved the classification. With fire-and-forget (revised D3), every published assessment is final — no provisional flag needed. The source field directly satisfies #213's tier hit rate analysis: count assessments by source across the replay corpus. DRL rules that match on `PatternAssessment` (e.g., `DominanceWeightRuleUnit`'s `patternStore`, `StarCraftStrategy.drl`) need constructor updates but no semantic changes — they match on `archetype()` and `confidence()`, not on source.
**Trade-offs:** Existing consumers of `PatternAssessment` need updating (record gains one field). The record constructor changes — all call sites (including DRL rules that construct `PatternAssessment` and generated Drools code with `Predicate1<PatternAssessment>` and `DataStore<PatternAssessment>`) must be updated in the same commit.
**Sources:** `PatternAssessment.java` (current 4-field record), issue #213 (tier hit rate analysis), `StarCraftStrategy.drl` (imports PatternAssessment), `DominanceWeightRuleUnit.java` (DataStore<PatternAssessment>)
**Exploration:** quick
**Status:** revised (R1-04, R1-08: removed `boolean provisional` after D3 revision, acknowledged DRL blast radius, clarified how source field satisfies #213)
**Depends on:** D3 (fire-and-forget means no provisional flag needed)

## D5: CDI optionality for ONNX tier — mechanism for graceful degradation

**Choice:** `Instance<OnnxClassifier>` for programmatic CDI lookup
**Alternatives:**
- Direct injection (`@Inject OnnxClassifier`) — fails at startup with unsatisfied dependency when ONNX model is absent or neocortex excluded
- `@IfBuildProfile` conditional bean registration — couples cascade to specific Quarkus profiles; fragile when profiles change
- Optional CDI producer with `@ConfigProperty`-guarded `@Produces` — more moving parts than necessary for a simple optionality check
**Rationale:** `Instance<OnnxClassifier>` is the standard CDI pattern for optional dependencies. The cascade constructor takes `Instance<OnnxClassifier>` and checks `instance.isResolvable()` before attempting ONNX classification. This satisfies both #212 ("When a tier is disabled, skip it — two-tier or single-tier operation is valid") and #211 ("Graceful startup when model file is missing — warning log, ONNX tier disabled, no crash") without coupling to profiles or requiring additional producer beans.
**Trade-offs:** Programmatic lookup is slightly less type-safe than direct injection, but the optionality requirement makes this the right trade-off.
**Sources:** Issue #212 (tier optionality), issue #211 (graceful startup), CDI `Instance<T>` spec
**Exploration:** quick (surfaced by reviewer R1-07)
**Status:** captured

## D6: Naming resolution — PatternClassifier collision and polymorphism rejection

**Choice:** Procedural orchestrator (not polymorphic); existing static `PatternClassifier` absorbed into `CascadingPatternClassifier`; `PatternAssessment` name retained
**Alternatives:**
- Polymorphic design where `PatternClassifier` becomes an interface implemented by all three tiers — rejected: the tiers have fundamentally different input shapes (rule evidence markers vs float tensors vs async text prompts), lifecycle characteristics (synchronous rule fire vs synchronous tensor inference vs async worker), and invocation patterns. A common interface forces a grab-bag parameter type and makes the LLM tier's async nature invisible at the type level.
- Rename `PatternAssessment` to `EnemyPatternAssessment` per issue #212's text — rejected: `PatternAssessment` is the established codebase name, used across DRL rules, Drools rule units, agents, and tests. The rename adds blast radius for no semantic gain (assessments are always about enemy patterns). Issue #212's naming is aspirational.
**Rationale:** The existing static `PatternClassifier` (with `computeTickConfidence`, `computeAllConfidences`, `mergeCumulative`, `applyRevisions`, `allAssessments`) contains the cumulative confidence logic that belongs to the cascade orchestrator. These methods move into `CascadingPatternClassifier` as instance methods. The static utility class is removed entirely — no collision. Issue #212's "all implement PatternClassifier" language does not survive contact with the actual tier differences: Drools fires rule units and produces evidence markers; ONNX converts game state to tensors and runs `session.run()`; LLM dispatches an async advisory worker that returns on a later tick. These cannot share a meaningful synchronous interface.
**Trade-offs:** Diverges from issue #212's stated architecture — the issue must be updated to reflect the procedural orchestrator design. `PatternAssessment` name is kept, creating a minor discrepancy with issue text that uses `EnemyPatternAssessment`.
**Sources:** `PatternClassifier.java` (static utility — 5 static methods, final class), issue #212 ("all implement PatternClassifier", "CascadingPatternClassifier replaces the direct PatternClassifier injection point")
**Exploration:** quick (surfaced by reviewer R1-03, R1-10)
**Status:** captured

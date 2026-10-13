# Decisions — #410 Relabel Python Rules

> **Scope note (R1-03, R2-03):** The original brainstorming produced
> aspirational architecture decisions (D1, D3, D4, D5) for an "ONNX
> Detector Library" that goes beyond #410's scope. These have been marked
> **deferred** and require a separate architectural issue with deep-analysis
> exploration — particularly given they touch territory covered by #306's
> deeply-explored decisions. D2 is the only decision revised to scope to
> #410's actual requirement: re-labelling reconstituted replays using Python
> building-timing rules. D6 retains its deep-analysis status but its
> applicability is conditional on D1.
>
> **Evaluation gate (R2-04):** The deferred architecture work (D1, D3, D4,
> D5, D7, D8) must be gated on #388 results. The chain is: #410 (relabel
> replays) → #388 (retrain existing multi-class models with better labels)
> → evaluate accuracy → decide if architecture changes are needed. If
> retrained models achieve sufficient accuracy, the existing #306 cascade
> architecture is the default path and the detector library becomes moot.

## D1: Detector decomposition axis

**Choice:** Hybrid — phase gates which detectors run, but each detector is situation-specific. Phase is a scheduling constraint, not a model boundary.
**Alternatives:**
- Situation-scoped only — no phase gating, all detectors always eligible. Simpler but wastes inference on irrelevant detectors.
- Phase-scoped — one multi-class model per phase. Fewer models but each is still multi-class within its phase, which is the same problem we're solving.
- Phase-aware multi-class (reviewer R1-02) — one model per (matchup × phase), preserving cross-dimensional interactions per #306-D2. 9 models max.
**Rationale:** Keeps each model binary and focused while the orchestration layer handles temporal scheduling. Detectors train on phase-specific data naturally.
**Trade-offs:** More orchestration complexity — each detector needs activation conditions defined. Relationship with #306-D2 (single model per matchup with modality dropout) requires explicit reconciliation — see D7.
**Sources:** CascadingPatternClassifier.java, PatternClassification.drl, FeatureIndexMaps.java
**Exploration:** quick
**Status:** deferred — requires separate issue with deep-analysis. Touches #306-D2 territory; D7 must be resolved first.

## D2: Training label source

**Choice:** Use rules.py `rule_based_label()` output as ground truth for ONNX retraining. rules.py provides building-timing classification with extensive fallback chains covering all three races.
**Alternatives:**
- Multi-signal fusion (original choice) — combine rules.py labels with game-state features for detector-specific binary labels. Deferred: adds labelling pipeline complexity beyond what #410 requires.
- New detector-specific rules — precise but duplicates effort already done in rules.py.
- Improve rules.py for finer-grained labels (reviewer R1-04) — extend rules.py with more specific checks when needed, keeping a single label source of truth.
**Rationale:** Issue #410's premise is that rules.py labels are the correct labelling approach. The existing rules.py covers all races, classifies virtually every replay, and produces labels matching the COARSE_HIERARCHY used for ONNX training. If finer-grained labels are needed for future detectors, extend rules.py rather than building a parallel labelling pipeline.
**Trade-offs:** Labels limited to what rules.py can express via building timing. For detector-specific labels (e.g., "aggressive army arrives before 3 min"), rules.py should be extended with those checks rather than adding a fusion layer.
**Sources:** quarkmind-classifier/src/labelling/rules.py, config.py COARSE_HIERARCHY
**Exploration:** quick
**Status:** revised — scoped to #410. Original multi-signal fusion deferred.

## D3: Feature dimensionality

**Choice:** Tiered feature sets — 2-3 standard feature sets that detectors select from, based on natural groupings in FeatureIndexMaps.
**Alternatives:**
- Per-detector custom subset — maximum reduction but N different feature extraction paths.
- Shared full vector — simplest pipeline but full FEATURES_PER_WINDOW for a binary classifier is wasteful.
**Rationale:** Natural groupings exist in FeatureIndexMaps by domain: Stats (N_STATS=13), Composition (N_BUILDINGS=53 + N_UNITS=56 = 109), Spatial (N_SPATIAL=7), Ratios (N_RATIOS=3), Deltas (N_DELTAS=4), Upgrades (N_UPGRADES=82, dynamic). Per-player total: N_FEATURES_PER_PLAYER = 218. Full window: FEATURES_PER_WINDOW = 2 × 218 + 2 = 438 (dual-player + army_gap + has_vision). Any feature tier must account for the dual-player structure — a "Composition" tier is 218 features (109 × 2 players), not 109.
**Trade-offs:** Tier boundaries are natural domain boundaries but the dual-player structure means tiers are larger than single-player counts suggest.
**Sources:** FeatureIndexMaps.java (N_STATS=13, N_BUILDINGS=53, N_UNITS=56, N_SPATIAL=7, N_RATIOS=3, N_DELTAS=4, N_UPGRADES=82)
**Exploration:** quick
**Status:** deferred — feature tiers only relevant when detector library architecture is designed. Counts corrected per R1-06.

## D4: Detector catalog scope

**Choice:** Map the full StrategyArchetype taxonomy (68 archetypes across 3 races × 3 phases), build detectors incrementally as training data becomes available.
**Alternatives:**
- Build all detectors at once (original choice) — infeasible given 80% UNKNOWN labels are the reason #410 exists. Rare archetypes (PROTOSS_MOTHERSHIP_FLEET, ZERG_VIPER_SUPPORT) may have <1% representation in training data.
- Start minimal (3-5) — lower risk but architecture may not generalise.
**Rationale:** The architecture must support all 68 archetypes from day one, but building detectors requires positive training examples. Current label coverage differs between systems: ARCHETYPES in config.py (what rules.py produces) has 8 vs_terran, 7 vs_zerg, 9 vs_protoss labels; OnnxLabelMapping (what ONNX models currently output) has 6/6/8. Missing from ONNX: PROXY and MACRO_ECONOMY (vs_terran), TECH_RUSH (vs_zerg), MACRO_ECONOMY (vs_protoss). Build order should be gated by #388 accuracy evaluation — see scope note.
**Trade-offs:** Some archetypes remain undetectable until sufficient training data exists. Prioritise high-frequency archetypes first.
**Sources:** StrategyArchetype.java (68 values), ARCHETYPES in config.py, COARSE_HIERARCHY in config.py, OnnxLabelMapping.java
**Exploration:** quick
**Status:** deferred — detector catalog scope is architecture work, not #410 relabelling. Gated on #388 accuracy evaluation per R2-04.

## D5: Multi-detector output model

**Choice:** Detector signal vector — each detector publishes its confidence independently. Strategy router reasons over the full vector of active detector signals.
**Alternatives:**
- Dominant detector wins — highest confidence determines archetype. Loses multi-detector insight.
- Weighted composite — explicit weighting rules combine detectors. More complex but deterministic.
- Fix the consumer (reviewer R1-05) — CascadingPatternClassifier.allAssessments() already returns List<PatternAssessment> with all archetypes above DISPATCH_THRESHOLD. The signal vector exists; SC2StrategyRouterTask.execute() only reads getFirst(). Fix the router to consume multiple assessments rather than rebuilding the producer.
**Rationale:** The strategy router should see all active signals. Multiple concurrent threats are real.
**Trade-offs:** Strategy routing must handle richer input. CBR queries currently use single archetype — extending to vector features requires CbrQuery API changes.
**Sources:** SC2StrategyRouterTask.java (line 127: pa.assessments().getFirst()), CascadingPatternClassifier.allAssessments(), ScoutingIntelBroker.java
**Exploration:** quick
**Status:** deferred — the existing cascade already produces multiple assessments. Addressing the router's single-signal consumption is a separate concern from #410.

## D6: Activation and orchestration mechanism

**Choice:** YAML playbooks for the full detector orchestration lifecycle — phase tracking (StateMachine), detector activation (EventRouter + guards), concurrent execution (SpawnedTask + Latch), result aggregation (Accumulator/StepResultStore), timeout safety (Deadline scopes), signal flow (Channel). Drools for evidence accumulation from raw game events into the facts that YAML pattern-matches on.
**Alternatives:**
- All Drools — Drools reasons over facts but can't declare coordination patterns (concurrent spawned tasks, deadline-bounded correlation, channel-based signal flow). Different paradigm.
- All YAML — lacks temporal accumulation / CEP primitives for evidence production from raw events. Drools will gain streaming features over time and scales better for large rule sets.
- activateIf() predicates on TaskDefinition (reviewer R1-07) — handles simple phase-gated activation (e.g., `activateIf(ctx -> ctx.getGamePhase() == EARLY)`). Sufficient for "run detector X during phase Y" scheduling. Does not provide concurrent multi-detector execution with correlation, timeouts, aggregation, or channel-based signal flow needed for full orchestration.
**Rationale:** The YAML coordination system is a real orchestration runtime with Go-style channels, correlation scopes, deadline-bounded concurrent execution, and pattern-matched event routing. These are precisely the primitives needed for multi-detector orchestration — and they're things Drools can't declare. Drools stays where it's strongest: accumulating evidence from raw game events. YAML coordinates actors; Drools matches patterns over facts.
**Trade-offs:** Two coordination languages in the system. Clear boundary: YAML orchestrates concurrent detector lifecycle, Drools accumulates evidence from game events. Epic #600 (event sequence patterns) landed — ConsumerHandle, race-step, temporal patterns (absence, heartbeat), recursive composition are all available. 1,253 tests across yaml-core + yaml-step-runtime.
**Depends on:** D1 (hybrid decomposition requires an activation mechanism)
**Sources:** yaml-core/playbook-schema.ts (ScenarioScope, EventRouter, Channel, CorrelationScope, SpawnedTask, Deadline), PatternClassification.drl, DroolsScoutingTask.drl
**Exploration:** deep-analysis (revised after deep review of YAML coordination primitives)
**Status:** captured — analysis stands independently but applicability conditional on D1. If D1 is deferred, the triggering context for YAML orchestration is gone.

## D7: Binary vs multi-class classification

**Choice:** Not yet decided — requires deep-analysis exploration.
**Alternatives:**
- One-vs-rest binary detectors per archetype — each model is a focused binary classifier. Independent confidence scores. Allows multiple concurrent detections.
- Multi-class with softmax — enforces mutual exclusivity at model level. Calibrated probabilities that sum to 1. Matches the current single-model-per-matchup design (#306-D2).
- Hierarchical softmax matching COARSE_HIERARCHY — coarse-to-fine classification preserving competition structure. 5 coarse classes per matchup, then fine-grained archetypes per coarse group.
- Phase-scoped multi-class (matchup × phase) — 3 races × 3 phases = 9 models, each multi-class within its phase. Preserves cross-dimensional interactions per #306-D2.
**Rationale:** This implicit decision underlies D1 but was never explicitly debated. For SC2 strategies that are mutually exclusive within a game phase (cannot simultaneously rush AND macro), multi-class has a structural advantage: softmax competition ensures calibrated probabilities. Binary detectors can both fire at high confidence for mutually exclusive strategies, requiring external arbitration.
**Trade-offs:** Multi-class enforces competition but cannot detect compound strategies (rush + hidden tech). Binary allows compound detection but loses calibration guarantees.
**Sources:** StrategyArchetype.java, CascadingPatternClassifier.java
**Exploration:** not yet explored
**Status:** captured — surfaced by reviewer (R1-08). Requires deep-analysis before D1 can proceed.

## D8: CascadingPatternClassifier disposition

**Choice:** Not yet decided — depends on D1, D7, and #388 evaluation results.
**Alternatives:**
- Keep cascade as-is (default path) — 3-tier (Drools → ONNX → LLM) with single multi-class ONNX model per matchup. This is the #306 architecture, proven and tested. **Evaluate retrained models from #388 before opening the detector library architecture issue. If accuracy with improved labels is sufficient, this alternative becomes the default path and D1/D3/D4/D5/D7 become moot.**
- Extend cascade — add detector models alongside existing ONNX multi-class models. Cascade manages both.
- Replace cascade — detector library replaces the ONNX tier entirely. Drools evidence rules and LLM fallback remain.
- Wrap cascade — detector library as a new tier in the cascade, between ONNX and LLM.
**Rationale:** CascadingPatternClassifier is load-bearing: cumulative confidence tracking, temporal decay, transition detection, LLM fallback logic, per-tier metrics. Any architectural change must address what happens to this component and its responsibilities. Design exploration is not free — it creates momentum toward adoption even when the simpler alternative would suffice. The existing architecture with better training data (#388) should be evaluated first.
**Trade-offs:** Replacing the cascade risks losing temporal confidence tracking, decay model, and transition detection. Extending it preserves these but adds complexity.
**Depends on:** D1, D7, #388 accuracy evaluation
**Sources:** CascadingPatternClassifier.java (364 lines), DroolsScoutingTask.java
**Exploration:** not yet explored
**Status:** captured — surfaced by reviewer (R1-09). Gated on #388 results per R2-04. Must be resolved before any detector library work proceeds.

## D9: Detector selection, aggregation, and meta-learning

**Choice:** Pluggable detector selector AND aggregator (dual SPI). The selector decides which detectors to activate. The aggregator reconciles their outputs — including competing results from mutually exclusive detectors. Both are pluggable with heuristic-first implementations. Record detector activation chains + outcomes in CBR for meta-learning. Eventually train a meta-ONNX from CBR cases.

**Selector SPI** — determines which detectors to run given current game state:
- Heuristic rules (YAML guards) — "Spawning Pool seen → activate rush + ling-bane detectors"
- CBR retrieval — "in similar past games, which detector combinations were predictive?"
- Feature-based selector — lightweight ML model outputs a detector activation mask
- Always-on with pruning — run all phase-eligible, prune after low-confidence ticks

**Aggregator SPI** — reconciles multiple concurrent detector outputs:
- Competition — mutually exclusive detectors suppress each other (softmax-like across the ensemble). Rush at 0.85 + macro at 0.7 → rush wins, macro suppressed.
- Composition — compatible detectors stack. Rush at 0.85 + hidden-tech at 0.6 → compound signal: aggressive with surprise element.
- Confidence weighting — historical accuracy of each detector weights its contribution.
- Temporal priority — more recent activations override older ones as game state evolves.

**Meta-learning loop:**
1. Individual detectors produce signals
2. YAML orchestration runs selected detectors (via selector SPI)
3. Aggregator SPI reconciles competing/composing results
4. CBR records full chain: [game state → selected detectors → signals → aggregated result → strategy response → outcome]
5. CBR retrieval improves future selection AND aggregation
6. Meta-ONNX eventually learns the selection+aggregation pattern directly from CBR cases

**Alternatives:**
- Heuristics only — YAML rules select and aggregate. No SPI abstraction. Simpler but couples the architecture to one mechanism.
- Defer until experimentation — build detectors first, design selector/aggregator later. Risks architecture that doesn't support the patterns that emerge.
- Single SPI — combine selection and aggregation into one interface. Loses the ability to evolve them independently.
**Rationale:** Selection and aggregation are distinct concerns. What to run (selector) and how to reconcile results (aggregator) evolve at different rates and may use different mechanisms. Heuristic selection + competition aggregation is the simplest starting point. CBR and meta-ONNX are evolutionary steps that the SPI enables without architectural change.
**Trade-offs:** Dual SPI adds abstraction before we know which mechanisms work. Mitigated by starting with the simplest implementations and only adding complexity when game data exists to drive it. The competition/composition split in the aggregator requires declaring which detectors are mutually exclusive — this is domain knowledge that must be authored.
**Depends on:** D1 (detectors must exist before selection/aggregation matters), D6 (YAML orchestration runs the selected detectors), D7 (binary vs multi-class determines how much external aggregation is needed)
**Sources:** SC2CbrRetentionObserver.java, SC2StrategyRouterTask.java, CascadingPatternClassifier.cumulativeConfidence, yaml-core ScenarioScope primitives
**Exploration:** quick
**Status:** captured

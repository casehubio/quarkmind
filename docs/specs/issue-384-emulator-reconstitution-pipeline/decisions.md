## D1: Calibration strategy

**Choice:** Triage-guided fix ordering, building on existing baseline root cause analysis
**Alternatives:**
- Brute-force per-category sweep — simpler to plan but risks fixing symptoms instead of root causes
- Tournament-replay-driven TDD — most rigorous but heavy test infrastructure overhead before any fix work
- Single diagnostic test (original) — rediscovers what the existing triage already identified
**Rationale:** The existing root cause triage in `emulated-game-accuracy-baseline.md` identifies at least 7 independent failure modes across three categories (units, upgrades, economy), already categorised by dependency order: race model/extraction completion blocks unit accuracy; mining rate physics are independent; upgrade extraction is independent. Fix ordering follows dependency order rather than starting from a blank diagnostic slate. Per-race validation runs after each fix batch quantify extraction vs physics gaps iteratively.
**Trade-offs:** Relies on triage accuracy — if the triage missed a root cause, fixes may not close the gap. Mitigated by running the accuracy baseline after each fix batch.
**Sources:** docs/benchmarks/emulated-game-accuracy-baseline.md, ReplayValidationHarness.java, EmulatedGameAccuracyBaselineTest.java
**Exploration:** quick
**Status:** revised (R1-01: acknowledged multiple independent root causes; reframed from "run diagnostic" to "use existing triage, fix in dependency order")

## D2: Reconstitution architecture

**Choice:** EmulatedGame-physics reconstitution path, separate from existing command-based extraction, writing to parallel output directory
**Alternatives:**
- Python-side emulation — duplicates physics code across languages, divergence risk
- Modify StrippedReplayFeatureExtractor in place — loses A/B comparison baseline; conflates command-based and physics-based extraction in one path
- Extend ReplayFeatureExtractor with a third path — cleaner integration but still overwrites existing reconstituted output
**Rationale:** The existing `ReconstitutionExportTest` uses `ReplayFeatureExtractor` → `StrippedReplayFeatureExtractor` for stripped replays, which extracts features directly from CmdEvents without EmulatedGame physics. The new path uses `ReplayValidationHarness` + `EmulatedGame` to produce physics-quality features (saturation-curve economy, race-model-driven unit production, building completion timing). A separate output directory (`data/reconstituted_emulated/`) preserves the command-based output for A/B comparison.
**Trade-offs:** ~10-15GB additional disk for a second reconstituted dataset (151K replays × feature JSON). JSON schema must stay aligned between command-based and emulator-based paths.
**Sources:** ReconstitutionExportTest.java, StrippedReplayFeatureExtractor.java, ReplayValidationHarness.java, reconstitution_pipeline.py, DATA_SOURCES.md
**Exploration:** quick
**Status:** revised (R1-02: clarified distinction from existing command-based extraction; corrected disk estimate from ~300GB to ~10-15GB)

## D3: Model architecture for expanded training data

**Choice:** Defer architecture decision until Phase 3 data characterisation is complete
**Alternatives:**
- Increase attention depth — stack 2-3 attention blocks with residual connections (incremental, ONNX-safe)
- Cross-attention between player and opponent streams — replace the learned gate with attention that lets the model learn which opponent features are diagnostic given the player's state
- Dilated convolutions — wider receptive fields without increasing kernel size for multi-scale temporal patterns
- Hyperparameter tuning of existing architecture — higher conv_channels, more attention heads, tuned dropout (may be sufficient with 200K+ samples)
**Rationale:** The current `StrategyClassifier` already has Conv1d temporal encoding, sinusoidal positional encoding, multi-head self-attention (4 heads with residual + LayerNorm), dual-stream architecture with player/opponent encoders, and hierarchical classification. The original decision was factually incorrect about the current architecture — it is not a "flat MLP." Architecture changes should be evaluated against the expanded dataset once Phase 3 produces reconstituted training data with known sample counts, class distributions, and feature quality.
**Trade-offs:** Delays architecture work. Mitigated by the fact that premature architecture changes risk wasted effort if the expanded data changes what's needed.
**Sources:** quarkmind-classifier/src/model.py (StrategyClassifier, ConvEncoder, SinusoidalPositionalEncoding, HierarchicalStrategyClassifier)
**Exploration:** quick
**Status:** revised (R1-03, R1-05: withdrew original; current model already has self-attention; deferred to post-data characterisation)

## D4: Data download strategy

**Choice:** Issue-driven download tasks per source, prioritised by accessibility
**Alternatives:**
- Single download script — simpler to run but harder to debug per-source failures; Google Drive access issues block everything
- Desirability-first ordering — Blizzard ladder first (highest data quality) but blocked by API credential setup and unknown rate limits
**Rationale:** Each download is traceable via its own issue, respects DATA_SOURCES.md retention policy. Priority ordering by accessibility: (1) SC2ReplayStats REST API — 4.4M replays, documented API, most accessible; (2) remaining Spawning Tool packs via alternative download for HSC 2026 (Google Drive access issues unresolved); (3) AI Arena Data API — token-based, straightforward; (4) Blizzard Ladder API — highest value but requires Developer Portal account, client key + secret, EULA acceptance, unknown rate limits. Blizzard credential setup should start in parallel but not block data acquisition from other sources.
**Trade-offs:** More issues to manage, but each is self-contained. SC2ReplayStats may require Elite membership for filtered search. Blizzard API is flagged as a risk rather than discovered at download time.
**Sources:** DATA_SOURCES.md, docs/benchmarks/restoration-coverage.md
**Exploration:** quick
**Status:** revised (R1-04: added accessibility-first prioritisation; flagged Blizzard API auth risk; evaluated alternative sources)

## D5: Architecture decision timing

**Choice:** Defer model architecture changes to post-Phase 3 data characterisation
**Alternatives:**
- Decide upfront (original implicit assumption) — risks committing to unnecessary complexity before data quality and volume are known
- Iterative refinement during pipeline construction — spreads architecture work across phases but lacks a clean evaluation point
**Rationale:** D3's sample count assumption (200K+) depends on D2 and D4 succeeding. If Blizzard ladder download fails or reconstitution accuracy is poor, the training set may be far smaller. Model architecture should be shaped by actual data properties — sample count, class distribution, feature quality — not projections.
**Sources:** D3, D2, D4, reconstitution_pipeline.py
**Exploration:** quick (surfaced by R1-05)
**Status:** captured

## D6: Extraction quality tracking in training pipeline

**Choice:** Tag reconstituted JSON with sourceType (tracker/stripped/emulator) and incorporate quality awareness in training
**Alternatives:**
- Treat all samples equally — simplest but risks noise injection from low-accuracy stripped features alongside perfect tracker features
- Weight samples by extraction quality — retains all data but reduces impact of noisy samples
- Separate models per source type — highest quality per model but fragments the training set
**Rationale:** The `ReplayFeatureExtractor` has a silent dual-path: tracker-event extraction (ground truth, ~100% accuracy) vs stripped-replay extraction (reconstructed, 36% unit accuracy at 5-min). Both feed into `reconstitution_pipeline.py` which treats all JSON files identically. Training on heterogeneous quality without distinguishing samples is a noise injection risk. Adding `sourceType` to reconstitution metadata enables quality-aware training — sample weighting, era-like feature, or curriculum learning.
**Sources:** ReplayFeatureExtractor.java, reconstitution_pipeline.py, emulated-game-accuracy-baseline.md
**Exploration:** quick (surfaced by R1-07)
**Status:** captured

## D7: Race model completion scope

**Choice:** Complete TerranRaceModel and ZergRaceModel production mechanics as Phase 2 prerequisite, scoped by D1 triage findings
**Alternatives:**
- Fix extraction only — if ReplayCommandExtractor ability ID mapping is the sole bottleneck, model changes may be unnecessary
- Fix models and extraction in parallel — fastest but risks wasted work if only one path is broken
**Rationale:** The accuracy baseline shows 0% for all Terran/Zerg units despite `RaceModelFactory.forRace()` correctly wiring per-race models via `resolvePlayerRace()`. `TerranRaceModel.canProduce()` unconditionally returns PROCEED (no production gates), and `ZergRaceModel` has larva mechanics with a real `canProduce()` gate. Yet both produce 0 emulated units beyond initial seed (SCV initial count only). The gap is likely in `ReplayCommandExtractor` not mapping non-Protoss ability IDs to training intents, but may also require race model enhancements. D1's triage-guided approach will confirm the primary bottleneck before committing to model or extraction fixes.
**Sources:** TerranRaceModel.java, ZergRaceModel.java, RaceModelFactory.java, ReplayValidationHarness.java, emulated-game-accuracy-baseline.md
**Exploration:** quick (surfaced by R1-06)
**Status:** captured

## D8: SC2 playbook execution environment

**Choice:** Inside Quarkus as a CDI bean
**Alternatives:**
- Standalone test harness — plain JUnit, no CDI, simpler but can't drive real SC2
- Both (CDI + test adapter) — more code for maximum flexibility
**Rationale:** CDI bean gives direct @Inject access to IntentQueue, SC2Engine, GameState. The same playbook runs against %emulated or %sc2 by profile-switching the engine bean. No adapter layer needed.
**Trade-offs:** Requires @QuarkusTest for playbook tests (boot cost). Acceptable since calibration tests already use @QuarkusTest.
**Sources:** DeliveryHandler SPI (pages/playbook), DesiredStateDeliveryHandler (IoT precedent)
**Exploration:** quick
**Status:** captured

## D9: Playbook-to-game-loop synchronisation

**Choice:** Playbook drives game ticks
**Alternatives:**
- Game loop drives playbook — playbook as a plugin in AgentOrchestrator.gameTick(). Reuses existing tick infrastructure but couples to the orchestrator.
- Separate threads, join on tick — matches concurrency discussion but adds unnecessary complexity for calibration.
**Rationale:** The playbook runner IS the game loop: tick → observe → update metrics → evaluate conditions → dispatch intents. Deterministic, single-threaded, no concurrency to manage. For calibration, reproducibility is paramount.
**Trade-offs:** Can't run alongside AgentOrchestrator plugins (strategy/economics). That's fine — calibration playbooks replace the agent pipeline.
**Sources:** EmulatedGame.tick(), ReplayValidationHarness.run() (same pattern)
**Exploration:** quick
**Status:** captured

## D10: SC2 delivery handler step vocabulary

**Choice:** Full vocabulary from day one — train, build, research, morph, assert
**Alternatives:**
- train + build + assert only — covers Layer 1 economy calibration but requires rework for Layers 2-4
- train + build only — absolute minimum, no inline validation
**Rationale:** The additional cost of research and morph steps is minimal (each is a switch case in the delivery handler). Having the full vocabulary avoids revisiting the delivery handler for each calibration layer.
**Trade-offs:** More code to test upfront. Mitigated by the fact that each action maps to a single Intent type.
**Sources:** Intent sealed hierarchy (TrainIntent, BuildIntent, ResearchIntent, MorphIntent)
**Exploration:** quick
**Status:** captured

## D11: Assertion reporting

**Choice:** JUnit assertions — assert failures are test failures
**Alternatives:**
- Report-style output — playbook always completes, divergences printed
- Both — report mode default, strict mode via playbook property
**Rationale:** Playbooks run inside @QuarkusTest. Assert failures produce clear messages ("3m checkpoint: probes expected >=30 but was 22") and fail CI. Fits existing test infrastructure.
**Trade-offs:** First assertion failure stops the playbook. Acceptable for calibration — fix one thing at a time.
**Sources:** OwnReplayCalibrationTest (report style), DivergenceRegressionTest (JUnit assert style)
**Exploration:** quick
**Status:** captured

## D12: Chrono Boost implementation approach

**Choice:** Full ability infrastructure — AbilityIntent, SC2DeliveryHandler ability action, ProtossRaceModel energy tracking, with mid-training adjustment
**Alternatives:**
- ProtossRaceModel auto-cast only — simpler (no Intent, no handler change) but diverges from issue acceptance criteria and doesn't establish the pattern for #392/#393
- New trainings only (no mid-training adjustment) — simpler but ~1 fewer Probe, borderline for ≥50 threshold
**Rationale:** Establishes the ability infrastructure pattern reused by MULE (#392) and Inject Larva (#393). Energy tracking follows ZergRaceModel's Queen energy pattern. Mid-training adjustment (halving remaining completion time) needed for ≥50 Probe target — without it, half each Chrono's duration is wasted on the current in-progress Probe.
**Trade-offs:** More code than auto-cast, but the infrastructure serves three issues. Sealed Intent hierarchy grows by one record.
**Sources:** ZergRaceModel.java (Queen energy pattern), EmulatedGame.startTraining(), SC2DeliveryHandler.java, ProtossRaceModel.java, issue #391
**Exploration:** quick
**Depends on:** D10 (delivery handler vocabulary)
**Status:** captured

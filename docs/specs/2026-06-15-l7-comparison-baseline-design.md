# L7 Comparison Baseline — Design Spec

**Issue:** casehubio/quarkmind#159  
**Branch:** issue-159-l7-comparison-baseline  
**Date:** 2026-06-15 (revised 2026-06-16)

## Purpose

Validate the QuarkMind harness design by closing Chapter 6 (C6) of the Game Agent Coordination journey in ARC42STORIES.MD. This is an analytical validation record, not a narrative piece. A blog entry may emerge opportunistically if something insightful surfaces.

## Deliverables

Four concrete outputs:

1. **`docs/benchmarks/2026-06-16-l7-harness.md`** — `GameLoopBenchmarkTest` results: harness dispatch overhead per tick (Phase 1 bridge configuration, mock plugins)
2. **`docs/benchmarks/2026-06-16-l7-emulated.md`** — `EmulatedGameBenchmarkTest` results: full EmulatedGame engine-tick throughput with representative combat load
3. **ARC42STORIES.MD §9.2 + §9.3 Chapter 6** — four touch-points:
   - §9.2 Chapter Index table: C6 status row `🔲 pending (#159)` → `✅ complete`
   - §9.2 Flowchart node: `style C6 fill:#D3D3D3,color:#000` → `fill:#90EE90,color:#000` (same green as C1)
   - §9.2 Layer × Chapter matrix column header: `C6 🔲` → `C6 ✅`
   - §9.3 Chapter 6 entry: validation record following C1 template (condensed — no gaps closed, no production code)
4. **ARC42STORIES.MD §9.4 Layer — Comparison Baseline** — replaces the existing stub; header fields updated (`Completed: ✅ 2026-06-16 (#159)`), plus: capability comparison table, latency section, win-rate disposition, current state note

## Benchmark 1 — Harness dispatch overhead

Run `GameLoopBenchmarkTest` via `mvn test -Pbenchmark`. Measures per-phase tick timings across the full plugin chain in `%test` profile (MockEngine — minimal computation). Answers: what does `createAndSolve()` dispatch add per tick, measured against the 400ms P99 quality requirement (§11)?

Output writes automatically to `target/benchmark-results.txt`. **Manual step:** copy into `docs/benchmarks/2026-06-16-l7-harness.md`.

**Transitional note:** Numbers reflect Phase 1 bridge overhead — poc `CaseEngine.createAndSolve()` → bridge `execute(CaseFile)` → `testActivation(CaseFileContext)` → `execute(CaseContext)`. Phase 2 removes all bridge indirection; numbers should be re-run at Phase 2 close.

## Benchmark 2 — EmulatedGame throughput

`GameLoopBenchmarkTest` cannot measure this: it is `@QuarkusTest` (forces `%test` profile, loads MockEngine; `EmulatedEngine` is `@IfBuildProfile("emulated")` and cannot be loaded in the test profile).

**New class: `EmulatedGameBenchmarkTest`** — plain JUnit, `@Tag("benchmark")`, package `io.quarkmind.sc2.emulated` (required: `RaceModelFactory.forRace()` is package-private).

### Setup — must mirror EmulatedEngine.joinGame()

The benchmark must wire terrain, pathfinding, race model, and enemy AI exactly as `EmulatedEngine.joinGame()` does — otherwise the tick does no real work (empty-game tick is mineral accumulation only):

1. `TerrainGrid grid = TerrainGrid.emulatedMap()`
2. `game.setMovementStrategy(new PathfindingMovement(grid))`
3. `game.setTerrainGrid(grid)`
4. `game.setPlayerRaceModel(RaceModelFactory.forRace(Race.PROTOSS))`
5. `game.setEnemyBehavior(new EnemyBehavior(EnemyStrategyLibrary.forName("PROTOSS_4GATE"), game.enemy, new TechTree()))`
6. `game.reset()`
7. Run approximately 120 warmup ticks (past the PROTOSS\_4GATE first-attack trigger at ~100 ticks) so A\*, combat resolution, and enemy AI are all active during the measured window. Discard warmup timings.
8. Measure 50 ticks. This window (ticks ~120–170) captures steady-state combat — enemy Zealots attacking player Probes. 30 ticks (as used by `GameLoopBenchmarkTest`) risks trailing post-death frames once Probes are eliminated, producing near-empty ticks in the same run and making throughput numbers non-reproducible across runs.

Note: no `configureWave()` call is needed. `configureWave()` is a separate mechanism that `EmulatedEngine.joinGame()` never uses. PROTOSS\_4GATE with FAST\_PUSH (minWaveSize=4, mineralThreshold=150, mineralsPerUnit=25, 2 minerals/tick) triggers the first enemy attack at approximately tick 75–100 without it.

### Tick measurement

Each measured tick must replicate the full `EmulatedEngine.tick()` sequence:
```
game.setUnitSpeed(1.0);
game.tick();
game.observeVisibility();   // discard result — computation must run
```

Calling `game.tick()` alone omits `observeVisibility()`, which `EmulatedEngine` always runs. The throughput claim is only valid for the full sequence.

Output: print ticks/sec to stdout, **manually** record in `docs/benchmarks/2026-06-16-l7-emulated.md`.

## §9.4 Layer — Comparison Baseline Entry

### Column definitions

- **L1 Naive** — the conceptual baseline documented in §9.4 Naive Game Loop: a hypothetical SC2 bot calling plugins directly per tick, no CaseHub harness. No production code exists or was intended; its purpose is to name the accountability gaps L2–L6 close.
- **SC2 API bot (no coordination layer)** — any SC2 bot using the SC2 API without a coordination layer, regardless of client library. An analytical characterisation of the coordination-free pattern. Note: QuarkMind itself removed ocraft-s2client-bot transport in #185 (replaced by `QuarkusSC2Transport`); "ocraft raw" would misrepresent both ocraft's callback model and QuarkMind's actual architecture.
- **QuarkMind L7** — full harness as built; Phase 1 bridge configuration (poc CaseEngine dispatch).

### Capability comparison table

| Dimension | L1 Naive | SC2 API bot (no coordination) | QuarkMind L7 |
|---|---|---|---|
| Plugin dispatch | Direct method calls per tick | Direct calls or callbacks | CaseEngine blackboard (`createAndSolve()`) |
| Inter-plugin state within tick | None | None | Shared CaseFile read/write within tick |
| Typed comms between plugins | None | None | casehub-qhorus channels |
| Formal out-of-scope signal | Silent no-op or throws | Silent no-op or throws | Structured DECLINE speech act (L3) |
| Audit trail | None | None | casehub-ledger attestation per plugin decision |
| Adaptive plugin selection | Fixed | Fixed | Binding-condition dispatch (L5) |
| Trust-weighted routing | None | None | Bayesian Beta strategy selection per opponent context (L6) |
| Outcome tracking | None | None | WIN→ENDORSED, LOSS→CHALLENGED, TIE→SOUND; UNKNOWN→skipped. Real SC2 games produce directional trust signals; mock/emulated/replay produce UNKNOWN and are skipped — correct, no meaningful win/loss in emulation |
| Observability | None | None | CaseFile key trace per tick; casehub-ledger attestation per decision; trust scores per strategy per opponent context; real-time GameState via WebSocket `/ws/gamestate` (dev/QA only — `@UnlessBuildProfile("prod")`) |

### Latency section

- **Configured agent tick interval:** 500ms (`starcraft.tick.interval=500ms`)
- **Quality requirement (§11):** P99 < 400ms (100ms headroom for SC2 I/O)
- **Harness dispatch overhead:** `[from GameLoopBenchmarkTest — target/benchmark-results.txt]`
- **Assessment:** does P99 harness dispatch fit within 400ms budget?
- **EmulatedGame full-tick throughput:** `[from EmulatedGameBenchmarkTest]` — confirms physics tick rate is not a constraint on plain JUnit tests driving EmulatedGame directly (distinct from @QuarkusTest, where CDI startup is the bottleneck)
- **Aspirational:** could the harness run at native SC2 physics rate (22Hz / 45ms per tick)? Distinct from the configured 500ms design. Benchmark 1 answers this for the dispatch layer; Benchmark 2 answers it for the physics layer. Combined overhead is not directly measured but can be inferred if both fit comfortably within 45ms individually.

### Win-rate disposition

The §9.4 stub committed to "document win-rate delta and latency delta attributable to each layer." Win-rate comparison is not tractable and is explicitly deferred:

- **L1 has no production code** — intentional; L1 is a conceptual baseline whose purpose is to name accountability gaps, not to be deployed. There is no L1 harness to run SC2 games against.
- **Comparing to a different SC2 bot** would measure strategy quality (which build order, which aggression timing), not harness contribution. The harness is the coordination layer; strategy quality is orthogonal.
- **L6 trust routing** needs a sufficient SC2 game corpus to yield meaningful Bayesian Beta score deltas. No such corpus exists — real SC2 games are slow to accumulate, and emulated games produce UNKNOWN (skipped) outcomes by design.

Win-rate comparison remains a future item contingent on: (a) a real-SC2 game corpus of sufficient depth, and (b) a baseline bot with comparable strategy quality to isolate the harness contribution.

### Current state note

Phase 1 migration complete (#193) — all plugins implement `io.quarkmind.agent.TaskDefinition` with new API (`execute(CaseContext)`, `activateIf()`, `requires()`, `produces()`). Dispatch still runs through poc `CaseEngine.createAndSolve()` via bridges. Phase 2 (engine#483 + engine#484) replaces poc engine — harness capabilities unchanged, dispatch infrastructure and benchmark numbers change.

## §9.3 Chapter 6 Entry

C6 is an analytical layer — no new production code, no accountability gaps closed. The entry follows the C1 template in condensed form; sections that would be substantive for capability-adding chapters collapse to single statements here.

**Journey:** Game Agent Coordination | **Sequence:** 6 of 6 | **Status:** ✅ complete  
**Delivered:** 2026-06-16 | **Issues:** #159 | **Blog:** none (opportunistic — written if benchmarks surface something insightful)

**What this delivers**  
Analytical validation that the L1–L7 layer sequence delivered its stated capabilities. No new harness capability is added. Harness dispatch overhead is measured against the 400ms P99 quality requirement (§11); EmulatedGame full-tick throughput is established as a baseline. Win-rate comparison deferred — see §9.4 win-rate disposition.

**Accountability gaps closed**  
None. All accountability gaps were closed by L2–L6. C6 validates the closures, it does not introduce new ones.

**Layer Impact**

| Layer | Delta |
|---|---|
| L7 Comparison Baseline | Low — documentation and benchmarks only; no production code |

**Known limitations carried forward:**
- Phase 2 dispatch pending: poc CaseEngine in use; SequenceWorker (engine#484) and `signalAndAwaitSync` (engine#483) not yet wired; benchmark numbers are Phase 1 transitional
- C2–C5 §9.3 chapter entries are stubs (layers complete; entries pending — filed as #198)

**Refs:** §9.4 Layer — Comparison Baseline; `docs/benchmarks/2026-06-16-l7-harness.md`; `docs/benchmarks/2026-06-16-l7-emulated.md`

## Out of Scope

- Tutorial README — dropped; ARC42STORIES.MD is the architecture record
- Blog entry — opportunistic only; written if benchmark surfaces something insightful
- Coordination patterns comparison — CBR territory (#192), not yet built
- LAYER-LOG.md — retired (#166); not referenced here
- C2–C5 chapter entries — stubs; filed as #198

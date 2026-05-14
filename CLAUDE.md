# quarkmind Workspace

**Physical path:** `/Users/mdproctor/claude/quarkmind/CLAUDE.md`
**Symlinked at:** `/Users/mdproctor/claude/public/quarkmind/CLAUDE.md`
**Project repo:** `/Users/mdproctor/claude/quarkmind`
**Workspace:** `/Users/mdproctor/claude/public/quarkmind`
**Workspace type:** public

## Session Start

Run `add-dir /Users/mdproctor/claude/quarkmind` before any other work.

## Artifact Locations

| Skill | Writes to |
|-------|-----------|
| brainstorming (specs) | `specs/` |
| writing-plans (plans) | `plans/` |
| handover | `HANDOFF.md` |
| idea-log | `IDEAS.md` |
| design-snapshot | `snapshots/` |
| java-update-design / update-primary-doc | `design/JOURNAL.md` (created by `epic`) |
| adr | `adr/` |
| write-blog | `blog/` |

## Structure

- `HANDOFF.md` — session handover (single file, overwritten each session)
- `IDEAS.md` — idea log (single file)
- `specs/` — brainstorming / design specs (superpowers output)
- `plans/` — implementation plans (superpowers output)
- `snapshots/` — design snapshots with INDEX.md (auto-pruned, max 10)
- `adr/` — architecture decision records with INDEX.md
- `blog/` — project diary entries with INDEX.md

## Git Discipline

Two git repositories are active in every session:
- **Workspace** (`/Users/mdproctor/claude/public/quarkmind`) — plans, blog (staging), snapshots, handover
- **Project repo** (`/Users/mdproctor/claude/quarkmind`) — source code, ADRs (`docs/adr/`), specs

Never rely on CWD for git operations — the session may have started in either repo. Always use explicit paths:
```bash
git -C /Users/mdproctor/claude/public/quarkmind ...     # workspace artifacts
git -C /Users/mdproctor/claude/quarkmind ...            # project artifacts
```
The file path determines the repo: if the file lives under `Workspace`, use the workspace path; if under `Project repo`, use the project path.

## Rules

- All methodology artifacts go here, not in the project repo
- Promotion to project repo is always explicit — never automatic
- Workspace branches mirror project branches — switch both together

## Routing

| Artifact   | Destination | Notes |
|------------|-------------|-------|
| adr        | project     | lands in `docs/adr/` — promoted at epic close |
| specs      | project     | lands in `docs/superpowers/specs/` — promoted at epic close |
| blog       | workspace   | staged here; published to mdproctor.github.io via publish-blog |
| plans      | workspace   | stay in workspace permanently |
| design     | workspace   | epic journal stays in workspace |
| snapshots  | workspace   | stay in workspace permanently |
| handover   | workspace   | |

**Blog directory:** `/Users/mdproctor/claude/public/quarkmind/blog/`

## Context Management

If the conversation is getting very long or you notice context pressure,
proactively suggest writing a handover before continuing.

---

# QuarkMind Project

## Project Type

**Type:** java

## Agentic Harness Goals

**Read first:** `~/claude/casehub/parent/docs/AGENTIC-HARNESS-GUIDE.md`

**Primary goal:** Living lab — a working testbed demonstrating that the CaseHub agentic harness pattern holds outside regulated enterprise domains, at millisecond game-loop granularity. The SC2 layer is domain-specific; the harness underneath (CaseFile blackboard, plugin coordination, adaptive agent selection) is the same pattern as AML, clinical, and devtown.

**Secondary goal:** LLM and human tutorial material for the harness layer (not the SC2 layer). The tutorial structure applies to the CaseHub integration, independent of SC2 knowledge.

**LAYER-LOG.md** (`LAYER-LOG.md` at project root) covers the harness layers only. A layer is not complete until its entry is written. See the AML reference implementation and `docs/protocols/universal/layer-log.md` in casehub-parent for the format.

---

## Repository Purpose

**QuarkMind** — a Quarkus-based StarCraft II agent platform and CaseHub living lab. An agentic harness for game AI: coordinates plugin agents (strategy, economics, tactics, scouting) via CaseHub's case engine and blackboard. Intelligence is provided by swappable plugins; the platform provides scaffolding, SC2 connection, and the CaseHub control loop.

Deep-dive: `~/claude/casehub/parent/docs/repos/quarkmind.md`

See `docs/superpowers/specs/` for the design spec and `docs/library-research.md` for the library evaluation log.

## Development Commands

**Build:**
```bash
mvn compile
```

**Test (all):**
```bash
mvn test
```

**Test (single class):**
```bash
mvn test -Dtest=SimulatedGameTest -q
```

**Run (mock mode, no SC2 needed):**
```bash
mvn quarkus:dev
# Game loop starts automatically on boot (MockStartupBean)
```

**Run (replay mode, no SC2 needed):**
```bash
mvn quarkus:dev -Dquarkus.profile=replay
# Default replay: Nothing_4720936.SC2Replay — override with -Dstarcraft.replay.file=...
```

**Build jar for Electron viewer (must use replay profile — @IfBuildProfile("replay") is build-time):**
```bash
mvn package -DskipTests -Dquarkus.profile=replay -q
```

**Launch Electron viewer:**
```bash
cd electron-app && npm start
```

**NEVER redirect Quarkus server stdout to a file without size limits.**
The game loop logs every tick; an overnight run fills the disk.
- Wrong: `mvn quarkus:dev ... > /tmp/server.log 2>&1 &`
- Right: `mvn quarkus:dev ...` (console only — Ctrl+C to stop)
- Right for background: `mvn quarkus:dev ... > /dev/null 2>&1 &`
- If you need logs in background: use the profile's `quarkus.log.file.*` config (already rotation-capped at 4G globally).

**Run (emulated physics, no SC2 needed):**
```bash
mvn quarkus:dev -Dquarkus.profile=emulated
# Opens visualizer at http://localhost:8080/visualizer.html
# Logs to /tmp/quarkmind-emulated.log (rotation configured — max 20M, 3 backups)
```

**Stopping emulated mode / cleaning log files:**
```bash
# Always kill Java BEFORE deleting log files.
# Deleting /tmp/quarkmind-emulated.log while Java has it open leaves an invisible
# open file descriptor — disk space is not freed until the JVM exits.
# Symptoms: du shows nothing, but df shows disk full. Visible via: lsof -c java | grep deleted
pkill -f 'quarkus:dev' && sleep 2 && rm -f /tmp/quarkmind-emulated.log*
```

**Run (real SC2):**
```bash
mvn quarkus:dev -Dquarkus.profile=sc2
```

**If `quarkus:dev` fails with `ClassTooLargeException`:** run `mvn clean` first. Occurs after large additions to enums or switch statements cause the Quarkus-generated startup class to exceed JVM bytecode limits. Clean removes the stale augmentation cache.

## Quarkus Profiles

| Profile | SC2 needed | Purpose |
|---|---|---|
| `%mock` (default) | No | Development and unit testing against SimulatedGame |
| `%emulated` | No | Physics simulation — EmulatedGame with real mechanics (movement, combat, enemy active AI) |
| `%replay` | No | Agent loop against a real `.SC2Replay` — observe-only |
| `%sc2` | Yes | Real SC2 integration |
| `%test` | No | @QuarkusTest — scheduler disabled |
| `%prod` | — | Production — QA endpoints stripped |

## Testing Patterns

**Unit tests** (no Quarkus, fast):
- Instantiate classes directly via `new` — no CDI
- Tests: `SimulatedGameTest`, `ReplaySimulatedGameTest`, `IEM10JsonSimulatedGameTest`, `ReplaySimulatedGameUnitTypeTest`, `ReplayEngineTest`, `BasicEconomicsTaskTest`, `BasicStrategyTaskTest`, `IntentQueueTest`, `MockPipelineTest`, `ScenarioLibraryTest`, `GameStateTranslatorTest`, `GameStateTest`, `DroolsTacticsTaskTest`, `DroolsScoutingTaskTest`, `BlinkMechanicsTest`, `GameStateInvariantTest`, `EmulatedGameTest`, `TechTreeTest`, `EnemyBehaviorTest`, `FixedBuildOrderStrategyTest`, `ReactiveStrategyTest`, `TerrainGridTest`, `AStarPathfinderTest`, `PathfindingMovementTest`, `SC2BotAgentTerrainTest`
- Package-private static methods on CDI beans are tested from the same package without CDI — make them `static` (not `private`) to enable this.

**Integration tests** (`@QuarkusTest`, full CDI context):
- Use `@Inject` to get beans; scheduler is disabled — call `orchestrator.gameTick()` directly
- Tests: `QaEndpointsTest`, `FullMockPipelineIT`, `DroolsStrategyTaskTest`, `EconomicsFlowTest`, `DroolsTacticsRuleUnitTest`, `DroolsTacticsTaskIT`, `DroolsScoutingRulesTest`, `DroolsScoutingTaskIT`
- Flow integration tests emit to a SmallRye channel and assert after `Thread.sleep(300)` — the flow processes asynchronously

**Playwright render tests** (`@QuarkusTest` + `@Tag("browser")`, excluded from default surefire run — need Chromium installed):
- `VisualizerRenderTest` — asserts sprite counts, positions, HUD text, on-screen projection, and pixel colour via `window.__test` API
- **For tests that click specific sprites:** get the unit/building tag from `simulatedGame.snapshot()` *before* calling `engine.observe()`, then wait with `unitHasTag(tag)` / `buildingHasTag(tag)`.
- Install Chromium once: `mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI -D exec.args="install chromium"`
- Run mock-mode visual tests: `mvn test -Pplaywright`
- Excluded from default surefire run via `excludedGroups=benchmark,browser`

**Replay visual pixel tests** (`ReplayVisualizerIT`, `@Tag("browser")`):
- Run with: `mvn test -Pplaywright-replay`
- **Run after any change to `visualizer.js`, `GameState`, or the domain model**

**WebSocket integration tests** (`@QuarkusTest`, run in normal suite):
- `GameStateWebSocketTest` — connects via `java.net.http.WebSocket`, calls `engine.observe()` directly

**Never use `@QuarkusTest` for tests that can be plain JUnit** — boot cost is significant.

## Native Quarkus — Policy

**Native mode is the end goal, but pragmatism comes first.**

Non-native dependencies and implementations are acceptable at any phase provided they are:
1. **Self-contained** — encapsulated behind a CDI interface or plugin seam
2. **Decoupled** — the rest of the system is unaware of the non-native implementation detail
3. **Tracked** — recorded in `NATIVE.md` with a note on what would be needed to replace them

**Do not block progress on native compatibility. Do block native builds from shipping until `NATIVE.md` shows all critical dependencies resolved.**

See `NATIVE.md` for the per-dependency compatibility tracker.

## Code Organisation

```
src/main/java/io/quarkmind/
  domain/              Plain Java records — no framework deps, always native-safe
  sc2/                 SC2Engine seam — IntentQueue, GameStarted/GameStopped events, sealed Intent interface
  sc2/real/            Live SC2 implementation — RealSC2Engine, SC2BotAgent, ObservationTranslator, ActionTranslator
  sc2/mock/            Mock SC2 implementation — SimulatedGame, MockGameObserver, MockCommandDispatcher
  sc2/mock/scenario/   ScenarioLibrary — living specification of SC2 behaviour
  agent/               CaseHub intelligence layer — QuarkMindCaseFile keys, GameStateTranslator, AgentOrchestrator
  agent/plugin/        Plugin seam interfaces (StrategyTask, EconomicsTask, TacticsTask, ScoutingTask)
  plugin/              Active plugin implementations (DroolsStrategyTask, FlowEconomicsTask, DroolsTacticsTask, BasicScoutingTask)
  plugin/scouting/     Drools CEP scouting — DroolsScoutingTask, ScoutingSessionManager, event records
  plugin/tactics/      GOAP planning + CDI strategy interfaces
  plugin/flow/         Quarkus Flow integration — EconomicsFlow, EconomicsDecisionService, EconomicsLifecycle
  qa/                  QA REST endpoints — dev/test only (@UnlessBuildProfile("prod"))
```

## Plugin Architecture

Each plugin seam (`StrategyTask`, `EconomicsTask`, `TacticsTask`, `ScoutingTask`) is a CDI interface extending CaseHub's `TaskDefinition`. Swap an implementation by providing a new `@ApplicationScoped` `@CaseType("starcraft-game")` bean — no wiring changes elsewhere.

## CaseHub Dependency

CaseHub (`io.casehub:casehub-core:1.0.0-SNAPSHOT` + `casehub-persistence-memory`) must be installed to the local Maven repo before building:

```bash
cd /Users/mdproctor/claude/casehub && mvn install -DskipTests -Dquarkus.build.skip=true
```

## Replay Library Dependency

The SC2 replay parser (`scelight-mpq` + `scelight-s2protocol`) is built from the Scelight fork:

```bash
cd /Users/mdproctor/dev/scelight && ./scripts/publish-replay-libs.sh
```

Run this when setting up a new environment or after any change to the `feature/standalone-modules` branch.

## Writing Style Guide

**The writing style guide at `~/claude-workspace/writing-styles/blog-technical.md` is mandatory for all blog and diary entries.** Load it in full before drafting. Complete the pre-draft voice classification (I / we / Claude-named) before generating any prose.

## Architecture Decision Records

`docs/adr/` holds ADRs for significant architectural choices. Reference ADR-0001 (Quarkus Flow placement) when deciding where new framework integrations belong.

## Performance Benchmarking

Two benchmark tests run via `mvn test -Pbenchmark`:
- `GameLoopBenchmarkTest` — per-phase tick timings across the full plugin chain. Run before/after any change that could affect game loop latency; paste results into `docs/benchmarks/`.
- `ScoutingCalibrationTest` — runs all replay datasets to 3-min mark and prints enemy unit count statistics per matchup.

**When to run `GameLoopBenchmarkTest`:**
- Adding or modifying a plugin
- Changing `AgentOrchestrator.gameTick()` or `caseEngine.createAndSolve()` timeout
- Adding new Drools rules or growing the fact base significantly
- Any change to `EmulatedGame.tick()` physics
- After a dependency upgrade (Drools, Quarkus Flow, CaseHub)

## Key Conventions

- **Domain model** (`domain/`) must remain plain Java — no CDI, no Quarkus imports, no framework dependencies.
- **SC2 interfaces** (`sc2/`) are contracts only — no implementation logic.
- **QA endpoints** (`qa/`) carry `@UnlessBuildProfile("prod")` — they must never appear in production.
- **`SimulatedGame`** is the living specification of SC2 behaviour. When real SC2 surprises us, update `SimulatedGame` to replicate the quirk and write a test.
- **`QuarkMindCaseFile`** holds all CaseFile key constants. Never use raw string keys elsewhere.
- **CaseFile key namespaces:** `game.*` for SC2 observation state, `agent.*` for plugin-written reasoning state.
- **Commit attribution:** Do not add `Co-Authored-By` trailers to commits.

## Blog Resources

### SC2 Image Index
`docs/sc2-image-index.md` is a living index of SC2 image URLs and assets. **Check it before searching the web for any SC2 image.** Contains Liquipedia URLs for all Protoss units and buildings, race icons, wallpaper collection links, and already-downloaded assets in `docs/blog/assets/`.

### Replay Index
`replays/replay-index.md` is a living index of SC2 replay datasets. Two datasets available: IEM10 Taipei 2016 (30 games) and AI Arena bot replays (29 `.SC2Replay` files).

## Project Artifacts

| Path | What it is |
|------|------------|
| `CLAUDE.md` | Project conventions (build, test, naming) |
| `docs/adr/` | Architecture decision records |
| `docs/DESIGN.md` | Design document |

## Work Tracking

**Issue tracking:** enabled
**GitHub repo:** mdproctor/quarkmind
**Changelog:** GitHub Releases (run `gh release create --generate-notes` at milestones)

**Automatic behaviours (Claude follows these at all times in this project):**
- **Before implementation begins** — check if an active issue or epic exists. If not, run issue-workflow Phase 1 to create one **before writing any code**.
- **Before writing any code** — check if an issue exists. If not, draft one and assess epic placement before starting.
- **Before any commit** — run issue-workflow Phase 3 to confirm issue linkage.
- **All commits should reference an issue** — `Refs #N` (ongoing) or `Closes #N` (done). If the user explicitly says to skip, ask once to confirm before proceeding.

---
title: "Surgical extraction — quarkmind-core gets a real agency framework"
date: 2026-08-14
author: Mark Proctor
projects: [quarkmind]
tags: [quarkmind-core, agency, extraction, multi-module, refactoring]
entry_type: note
subtype: diary
status: draft
issue: 278
---

QuarkMind started as a StarCraft II agent. The restructure spec (#272) laid out a vision: split the mono-module into quarkmind-core (shared agency framework) and quarkmind-sc2 (game-specific code), so that Town, Minecraft, Evennia, and Sonaria could all build on the same foundation. #272 created the module skeleton and dropped in marker interfaces — empty shells with the right names in the right packages. Today we filled those shells.

The extraction had a hard constraint: SC2 tests stay green after every commit. No big-bang refactor. Each move is atomic — relocate one class, update imports, verify, commit. IntelliJ's `ide_move_file` handled the import rewrites across 30+ files per move, which is exactly the kind of mechanical cross-cutting change that breaks if you do it by hand.

Three classes moved from `io.quarkmind.agent` (SC2) to `io.quarkmind.agency.*` (core):

- **TaskDefinition** — the plugin contract (`requires`/`activateIf`/`execute`/`produces`). Every SC2 plugin seam extends this. It had no SC2 knowledge in it — pure framework.
- **MapCaseContext + MutableMapCaseContext** — read-only and writable CaseContext implementations backed by a plain `Map<String, Object>`. Used everywhere in tests and by `PluginDispatchBroker` for pre-engine activation checks. The writable version tracks mutations separately so the orchestrator can return only the delta.
- **MilestoneTracker + MilestoneSession** — fire-once milestone tracking with concurrent collections. Generic enough for any world that has checkpoints.

The interesting discovery was the Jandex dependency. `MilestoneSession` carries `@ApplicationScoped` — a CDI annotation. When it lived in quarkmind-sc2, Quarkus found it during augmentation because the application module is always indexed. After moving to quarkmind-core (a library jar), it vanished from CDI's perspective. No compilation error. No runtime warning. Just a `@QuarkusTest` augmentation failure that blames a completely different class — `MilestoneOutcomeRecorder` can't inject `MilestoneSession` because CDI doesn't know it exists. The fix is `jandex-maven-plugin` in the library module's POM, which generates the `META-INF/jandex.idx` that Quarkus needs.

A second CDI surprise: `MomentDetectionBattleTest` has a `static` inner class extending `MomentDetectionTask` (a CDI bean). The inner class has a constructor parameter (`List<GameMoment>`) that CDI can't satisfy. Quarkus augmentation scans test classes too — and tries to register the inner class as a bean. The error surfaces in a completely unrelated `@QuarkusTest`, making it look like a test infrastructure failure rather than a bean discovery issue. `@jakarta.enterprise.inject.Vetoed` on the inner class tells CDI to skip it.

Beyond the moves, we fleshed out the marker interfaces with real method signatures extracted from SC2 patterns:

- **InteractionTrigger** gets `evaluate(AgencyContext) → Optional<TriggerEvent>` — the pattern behind SC2's advisory, coaching, and commentary trigger builders. Each evaluates context at a tick and optionally fires.
- **MomentDetector** gets `detect(AgencyContext) → List<MomentEvent>` — from `MomentDetectionTask`'s battle FSM and supply-block detection.
- **VisibilitySPI** becomes generic: `VisibilitySPI<E>` with `visible()` and `remembered()`. SC2 will use `VisibilitySPI<Unit>`, Town might use `VisibilitySPI<Character>`.
- **LlmRequestQueue** gets `submit(LlmRequest)`, `pendingCount()`, `hasCapacity()` — the rate-limiting, prioritisation contract from D9.
- **AgencyContext** expanded with `tick()` and a key-value state bag, so triggers and phases can propagate data without coupling to CaseContext.
- **AgencySession** added — the generic session identity concept (`UUID id()`, `reset()`, `setId()`), extracted from `GameSession`.

What's deliberately left out: SC2 doesn't implement these core SPIs yet. `AgentOrchestrator` still uses `SC2Engine` directly, not `WorldBridge`. The advisory trigger builders don't implement `InteractionTrigger`. That's follow-up work for #273–#277 — when Town is the first non-SC2 world to use the framework, the SPIs get validated against a genuinely different domain. Extracting from one example and validating against a second is how you avoid premature abstraction.

quarkmind-core now has 54 tests across 11 test classes. The full suite across all modules runs at 2380 tests, zero failures — including `@QuarkusTest` integration tests that previously failed due to the CDI issues we fixed.

# SC2 Engine Architecture — Roadmap

**Created:** 2026-04-06  
**Updated:** 2026-05-07  
**Status:** Living document — update as phases complete

---

## Vision

The platform evolves from "one hard-wired SC2 connection" to a **pluggable engine
system** where the same agent and plugin stack can run against any backend:
a hand-crafted simulation, a real replay, a Quarkus-native emulator, a local SC2
process, or a remote SC2 process over a network bridge. Swapping engines requires
no changes to plugins or the agent orchestrator.

---

## Architecture Invariant

**The agent orchestrator and all plugins are engine-agnostic.** They read
`GameState` and emit `Intent` objects. The engine is the only thing that knows
how to produce one and consume the other. Changing engines is a one-bean swap.

---

## Phase 0 + 1 — Bootstrap ✅ Complete

Established the three-seam model (`SC2Client` / `GameObserver` / `CommandDispatcher`)
and two working engine stacks: `MockEngine` → `SimulatedGame` and `RealSC2Engine`
→ ocraft-s2client.

---

## Phase 2 — Engine Abstraction ✅ Complete

Introduced `SC2Engine` as a single CDI interface encapsulating all three concerns
(`connect`, `observe`, `dispatch`, `tick`). Profile selection now controls one
bean instead of three. All tests pass unchanged.

---

## Phase 3 — ReplayEngine ✅ Complete

`ReplayEngine` wires `ReplaySimulatedGame` into the engine system. The full agent
loop fires against real `.SC2Replay` data; `dispatch()` records what the agent
*would have done* without applying intents back. The `%replay` Quarkus profile
selects it. Replay terrain extracted from `.SC2Map` MPQ files; playback
pause/seek/speed controls exposed via `/qa/replay/*`.

**Current limitation:** observe-only. Intents are logged but not applied — no
closed-loop play against replay data yet (see Phase 6).

---

## Phase 4 — Network Bridge / HttpSC2Engine (future)

Decouple the SC2 machine from the orchestrator machine over HTTP/gRPC. Deferred
until the local emulation path is accurate enough to reduce SC2 dependency.

---

## Phase 5 — EmulatedEngine ✅ Substantially complete

`EmulatedEngine` / `EmulatedGame` provides closed-loop play without SC2. The
`%emulated` profile selects it; a Three.js visualizer renders live state over
WebSocket.

**Implemented so far (E1–E6):**

| Stage | What | Status |
|---|---|---|
| E1 | Unit movement, mineral harvest, build times, core infrastructure | ✅ |
| E2 | Vector movement, scripted enemy wave, full intent handling, `EmulatedConfig` | ✅ |
| E3 | Shields, two-pass simultaneous combat, unit death | ✅ |
| E4 | Symmetric `PlayerState`×2, `EnemyBehavior`, 9 strategies + `ReactiveStrategy`, `TechTree` | ✅ |
| E5 | A* pathfinding, terrain-aware edge costs, sub-tile LOS path smoothing | ✅ |
| E6 | Building collision — circular footprint radii, entry-only semantics | ✅ |

**Not yet implemented:**
- Friendly auto-engage (units stop and fight without explicit `AttackIntent`)
- Realistic worker mineral collection model (currently flat +5/tick)
- Parallel training queues (one per building)

**Accuracy goal (open):** the emulated engine is not yet validated against real
SC2 replay data — see Phase 6.

---

## Phase 6 — Replay-Accurate Forward Simulation (planned — Approach 1)

**Goal:** feed real player commands from a replay into `EmulatedGame`, compare
resulting state to `ReplaySimulatedGame` (the ground truth), and assert within
defined tolerances. This validates the engine incrementally and enables
replay-driven plugin evaluation.

**Design decision:** Approach 1 — incremental, economic first:

| Layer | Validation | Tolerance |
|---|---|---|
| Economic (build, train, resources) | Deterministic — exact match | Exact |
| Movement | Near-deterministic | Within 0.5 tiles per unit |
| Combat | Approximately deterministic (control RNG) | Within 10% HP |

**What already exists:**
- `ReplaySimulatedGame` — ground truth, plays back real state tick-by-tick
- `GameEventStream` — extracts move/attack `CmdEvent`s from replay MPQ
- `UnitOrderTracker` — applies movement orders to unit positions using real SC2 speeds
- `ReplayEngine` — wires the above as an `SC2Engine`

**What's missing:**
- Ability command extraction (build, train) from `CmdEvent` ability IDs
- `ReplayValidationHarness` — runs both `ReplaySimulatedGame` and `EmulatedGame`
  in parallel, comparing state at each tick and recording divergence metrics
- Plugin evaluation layer (B): compare agent intents against human player decisions

---

## Engine Selection Summary

| Engine | Closed loop | Real data | SC2 needed | Status |
|---|---|---|---|---|
| `MockEngine` | ✅ | ❌ hand-crafted | ❌ | ✅ Done |
| `ReplayEngine` | ❌ observe only | ✅ | ❌ | ✅ Done |
| `RealSC2Engine` | ✅ | ✅ | ✅ | ✅ Done |
| `EmulatedEngine` | ✅ | ❌ simulated | ❌ | 🔄 E1–E6 done, not yet replay-validated |
| `HttpSC2Engine` | ✅ | ✅ | remote | ⏳ Future |
| Replay-accurate fwd sim | ✅ | ✅ | ❌ | 📋 Phase 6 planned |

---

## Open Questions

- `SC2Engine.tick()` ownership — who owns the tick loop when real SC2 is
  connected? Scheduler-driven vs engine-driven. Open since Phase 0.
- `HttpSC2Engine` protocol: REST vs gRPC? Streaming game state suggests gRPC,
  but REST is sufficient at 500ms tick rates. Deferred.
- Phase 6 tolerance thresholds — what's "close enough" for movement and combat?
  Depends on the use case (plugin evaluation vs physics correctness).

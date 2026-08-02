# quarkmind — Consumer Guide

> StarCraft II game AI application and CaseHub agentic harness living lab.

**GitHub:** [casehubio/quarkmind](https://github.com/casehubio/quarkmind)
**Tier:** Application — Living Lab

---

## Purpose

A StarCraft II game AI application built on the CaseHub agentic harness. Coordinates plugin agents (strategy, economics, tactics, scouting) via CaseHub's case engine and blackboard, with Drools for rule-based reasoning and Quarkus Flow for durable task execution.

Explicitly a living lab — a testbed for CaseHub, Drools, and Quarkus Flow integration patterns in a real-time domain. The SC2 game loop is domain-specific; the CaseHub harness underneath (CaseFile blackboard, plugin coordination, adaptive agent selection) is the same foundation as AML, clinical, and devtown.

Its primary value in the application family is as a **proof of generality**: the same harness pattern that coordinates AML investigation specialists and clinical trial monitors also coordinates real-time game AI agents — without changing the foundation. QuarkMind demonstrates that the harness holds outside regulated enterprise domains, across diverse timing characteristics (game AI operates at millisecond tick granularity vs days for case management).

**Note:** Transferred from `mdproctor/quarkmind` to `casehubio/quarkmind`. A personal project using the CaseHub pattern — does not participate in the casehubio CI pipeline.

## Module Structure

QuarkMind is a single-module Quarkus application. Key structural areas:

| Area | What it covers |
|------|---------------|
| `domain/` | SC2 domain model — game state, units, buildings, actions, intents; `SC2Data` (all game constants) |
| `agent/` | CaseHub intelligence layer — `QuarkMindCaseFile` keys, `GameStateTranslator`, `AgentOrchestrator` |
| `agent/plugin/` | Plugin seam interfaces — `StrategyTask`, `EconomicsTask`, `TacticsTask`, `ScoutingTask` |
| `plugin/` | Active plugin implementations — Drools and Flow-based |
| `sc2/` | SC2 engine seam — `IntentQueue`, events, sealed `Intent` interface |
| `sc2/emulated/` | Full physics simulation — `EmulatedGame` with combat, mining, pathfinding |
| `qa/` | QA REST endpoints — dev/test only |

## Key Consumer APIs

### Plugin Seam Interfaces

Each plugin extends CaseHub's `TaskDefinition`:

- **`StrategyTask`** — high-level strategic decisions (expand, attack, defend)
- **`EconomicsTask`** — build order execution and resource management
- **`TacticsTask`** — unit micro and combat engagement
- **`ScoutingTask`** — intelligence gathering and threat assessment

### Domain Types

- **`QuarkMindCaseFile`** — all CaseFile key constants; never use raw string keys
- **`Intent`** (sealed interface) — exhaustive set of game actions; switch exhaustiveness at compile time
- **`SC2Data`** — all game constants (costs, timings, ranges, armour, attributes)

### Quarkus Profiles

| Profile | SC2 needed | Purpose |
|---------|-----------|---------|
| `%mock` (default) | No | Development and unit testing against SimulatedGame |
| `%emulated` | No | Physics simulation with real mechanics |
| `%emulated-sc2` | No | Full-stack testing — SC2 protocol over EmulatedGame |
| `%replay` | No | Agent loop against a real `.SC2Replay` |
| `%sc2` | Yes | Real SC2 integration |
| `%coach` | No | Human plays, AI observes and advises |

## Dependencies

```
quarkmind
  → casehub-engine            (CaseFile blackboard, TaskDefinition, adaptive plugin dispatch)
  → casehub-persistence-memory (in-memory store for fast game-loop ticks)
  → casehub-qhorus            (advisory channel for LLM observers)
  → casehub-ledger            (trust-weighted strategy routing)
  → Drools                    (rule-based strategy, tactics, scouting)
  → Quarkus Flow              (durable economics build order execution)
```

## What It Does NOT Do

Everything below belongs in the foundation:

- Trust scoring computation (casehub-ledger)
- Commitment lifecycle (casehub-qhorus)
- Human task inbox (casehub-work — not applicable at game AI tick granularity)

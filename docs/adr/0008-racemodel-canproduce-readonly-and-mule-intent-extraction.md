# 0008 — RaceModel.canProduce read-only enforcement and MULE calldown intent extraction

Date: 2026-06-01
Status: Accepted

## Context and Problem Statement

`RaceModel.canProduce()` was required by contract to be a pure read-only query, but the
contract was enforced only by documentation — the method signature accepted a mutable
`PlayerState`. Worse, `TerranRaceModel.canProduce()` violated the spirit of the contract
by mutating state (spawning a MULE unit) when returning `HANDLED`, because MULE calldown
had been modelled as `TrainIntent(oc, UnitType.MULE)`. This conflated two semantically
distinct operations: unit training (queued, resource-costed) and ability calldown
(instant, energy-based).

## Decision Drivers

* Structural enforcement preferred over doc-only contracts at plugin seam boundaries
* MULE calldown is semantically an Orbital Command ability use, not a train order
* `ProductionDecision` should be a pure query result — not a command carrier
* External plugin authors (#74) must not be able to accidentally mutate state in `canProduce`

## Considered Options

* **Option A: Doc-only enforcement** — keep the current design, strengthen the Javadoc
* **Option B: `PlayerStateView` only** — introduce a read-only interface, keep `HANDLED` with a `Consumer<PlayerState>` action carrier
* **Option C: `PlayerStateView` + `MuleCalldownIntent` + enum `ProductionDecision`** — extract MULE to its own intent type, making `canProduce` a clean two-value query

## Decision Outcome

Chosen option: **Option C**, because extracting `MuleCalldownIntent` eliminates the
root cause (MULE modelled as training). This makes `ProductionDecision` a clean
`{PROCEED, BLOCKED}` enum, `canProduce` a structurally read-only query, and
`TerranRaceModel.canProduce` trivially simple. Option B left a doc-only constraint
on the `Handled(Consumer<PlayerState>)` write path; Option A changed nothing.

### Positive Consequences

* `canProduce(PlayerStateView, ...)` cannot mutate state — enforced by the compiler
* `ProductionDecision` is a two-value enum reflecting what a query can return
* `MuleCalldownIntent` sits correctly alongside `BlinkIntent` and `TrainIntent` in the sealed hierarchy — all three are semantically distinct game actions
* `TerranRaceModel.canProduce` becomes a one-liner (`return PROCEED`)
* `RaceModel.onCalldown` provides a clean extension point for future ability-use mechanics

### Negative Consequences / Tradeoffs

* MULE calldown in `ActionTranslator` (real SC2 path) is a stub — the OC ability mapping is not yet wired; this was an existing gap, now made explicit
* Enemy-state MULE calldown silently drops (no enemy race model exists) — acceptable for the current emulated path

## Pros and Cons of the Options

### Option A — Doc-only enforcement

* ✅ Zero code change
* ❌ External plugins can still silently corrupt state via `canProduce`
* ❌ Does not fix the semantic error (MULE modelled as training)

### Option B — `PlayerStateView` + `Handled(Consumer<PlayerState>)`

* ✅ `canProduce` read-only by construction for PROCEED/BLOCKED paths
* ❌ `Handled` variant still carries a write action — doc-only constraint on resource fields
* ❌ Sealed interface over-engineered once MULE is extracted: three stateless variants where an enum suffices

### Option C — `PlayerStateView` + `MuleCalldownIntent` + enum

* ✅ Structural read-only at all paths
* ✅ `ProductionDecision` as an enum reflects the domain: queries return decisions, not commands
* ✅ Sealed `Intent` hierarchy correctly classifies game actions
* ❌ ActionTranslator stub for real SC2 path (existing gap, now visible)

## Links

* Supersedes partial design from [ADR-0007](0007-racemodel-plugin-seam.md) §Production check (HANDLED variant replaced)
* Refs #165, #74
* Spec: `docs/superpowers/specs/2026-06-01-racemodel-canproduce-readonly-design.md`
* Protocol: `docs/protocols/emulated-plugin-seam-visibility.md` (PP-20260601-5fa812)

# 0009 — EigenTrust omission for single-attestor deployment

Date: 2026-06-02
Status: Accepted

## Context and Problem Statement

`casehub-ledger` maintains four trust score types per actor: GLOBAL, CAPABILITY, DIMENSION,
and CAPABILITY_DIMENSION. The two scoring algorithms that populate them are:

1. **Bayesian Beta** — per-agent Beta(α, β) scoring from observed success/failure ratios.
   Produces CAPABILITY scores. Lives in `casehub-ledger` runtime; always available when the
   module is on the classpath.
2. **EigenTrust** — graph-based peer trust propagation via power iteration on the attestation
   matrix. Produces GLOBAL scores. Computed by the nightly `TrustScoreJob` inside
   `casehub-engine-ledger`.

These are independent concerns bundled in the same module:

* **`casehub-engine-ledger`** provides both `TrustWeightedAgentStrategy @Priority(1)`
  (the routing strategy that reads CAPABILITY scores) and `TrustScoreJob` (the EigenTrust
  GLOBAL computation). They cannot be separated at the module level, but the routing
  strategy reads CAPABILITY scores for plugin selection — GLOBAL (EigenTrust) scores are
  not consumed for capability-based routing.

Layer 4 (#156) adds `casehub-ledger` runtime; Bayesian Beta CAPABILITY scores accumulate
from plugin outcome attestations. Layer 6 (#158) adds `casehub-engine-ledger` so
`TrustWeightedAgentStrategy` can activate trust-weighted dispatch using those scores.

The question is whether `TrustScoreJob`'s EigenTrust computation provides value alongside
Bayesian Beta in QuarkMind's deployment, or whether it should be treated as inert overhead.

QuarkMind's harness has a single attestor: `AgentOrchestrator`. It observes plugin
performance (strategy, economics, tactics, scouting) via CaseFile keys and records the
outcome of each decision. No plugin attests to any other plugin — attestation is exclusively
top-down from the harness to its plugins.

## Decision Drivers

* EigenTrust's GLOBAL score requires a peer attestation graph to add information beyond
  direct observations; with a single attestor, GLOBAL converges to the same values as
  CAPABILITY (direct attestations) with extra computation
* QuarkMind's plugin pool is exactly 4 actors — within the small-graph pathology zone
  documented in GE-20260421-09d636 where the dangling-node fallback creates 3-cycles
* `TrustWeightedAgentStrategy` for plugin selection reads CAPABILITY scores; EigenTrust
  GLOBAL scores are not consumed by the capability routing path
* The platform's trust maturity model handles cold-start correctly via Phase 0 availability
  routing — EigenTrust is not needed for routing correctness

## Considered Options

* **Option A: EigenTrust active** — `casehub-engine-ledger` on classpath; `TrustScoreJob`
  runs nightly; GLOBAL (EigenTrust) scores computed alongside CAPABILITY (Bayesian Beta).
* **Option B: EigenTrust inert** — `casehub-engine-ledger` on classpath (required for L6
  routing); `TrustScoreJob` runs but GLOBAL scores are not consumed for plugin routing;
  `TrustWeightedAgentStrategy` dispatches on CAPABILITY scores only.
* **Option C: No trust scoring** — stay at Phase 0 (availability routing) indefinitely.

## Decision Outcome

Chosen option: **Option B — EigenTrust inert**, because EigenTrust GLOBAL scores duplicate
Bayesian Beta CAPABILITY scores in a single-attestor deployment and carry 3-cycle convergence
risk at 4 actors. `casehub-engine-ledger` is still added at L6 (#158) for
`TrustWeightedAgentStrategy`; EigenTrust computation runs but its output is not used.

### Why EigenTrust GLOBAL scores do not add value here

**Single attestor collapses the peer graph.** EigenTrust propagates trust through a weighted
adjacency matrix `C` where `C[i][j]` represents actor `i`'s normalised direct trust in
actor `j`. With one attestor (the harness), the harness is the only actor with non-zero
rows. All plugin rows are zero, triggering the pre-trusted distribution fallback for every
plugin — precisely the condition that creates 3-cycle non-convergence.

When all plugin rows are zero simultaneously, the dangling-node fallback fires for all four
actors in every power-iteration step. With the pre-trusted set `{harness}`, each plugin's
weight flows back to the harness, and the harness's weight flows forward to all plugins
equally. This creates a structurally inevitable oscillation: the matrix is periodic with
period 3, and dampening (α=0.15) decays it slowly — requiring far more iterations than
typical max-iteration limits to resolve (GE-20260421-09d636). The GLOBAL scores produced,
if the job is ever run to completion, are numerically identical to the harness's direct
observations — which is exactly what Bayesian Beta CAPABILITY scores already express.

**CAPABILITY scores are what routing reads.** `TrustWeightedAgentStrategy` selects among
plugin implementations based on CAPABILITY trust scores (Bayesian Beta per-plugin per-task
ratios). GLOBAL scores are not part of the capability routing path. EigenTrust would only
influence routing if a future consumer explicitly read GLOBAL scores — there is no such
consumer in the current design.

### Positive Consequences

* L4 (#156): `casehub-ledger` runtime only; Bayesian Beta CAPABILITY scores accumulate
* L6 (#158): `casehub-engine-ledger` added for routing; EigenTrust computation is inert
  (runs, produces GLOBAL scores identical to direct observations, not consumed for routing)
* No additional module beyond `casehub-engine-ledger` required; Flyway cost is limited to
  whatever migrations `casehub-engine-ledger` ships (if any) — EigenTrust schema joins
  are in the V2000+ range and do exist but carry no logic obligation

### Negative Consequences / Tradeoffs

* `TrustScoreJob` runs nightly even though its output is not consumed — this is inert
  overhead, not harmful; it can be disabled via scheduler config if needed
* If quarkmind later introduces a consumer of GLOBAL scores (e.g., cross-session agent
  ranking), this ADR must be revisited; EigenTrust would need to be treated as active
  rather than inert

## Pros and Cons of the Options

### Option A — EigenTrust active (GLOBAL scores consumed)

* ✅ Full trust score coverage (all four types populated and read)
* ❌ GLOBAL scores add no information over CAPABILITY in a single-attestor deployment
* ❌ `TrustScoreJob` is in the structurally inevitable 3-cycle oscillation zone for
  4 actors (all plugins trigger dangling-node fallback simultaneously)
* ❌ Requires a consumer of GLOBAL scores that does not exist in the current design

### Option B — EigenTrust inert (GLOBAL scores computed but not consumed)

* ✅ `casehub-engine-ledger` added cleanly for L6 routing
* ✅ `TrustWeightedAgentStrategy` routes by CAPABILITY scores — correct for the deployment
* ✅ EigenTrust pathology is irrelevant because GLOBAL scores are not read
* ❌ `TrustScoreJob` runs as inert overhead (mitigable via scheduler config)

### Option C — No trust scoring

* ✅ Simplest deployment
* ❌ Forfeits observable plugin performance differentiation; routing never improves from
  Phase 0 regardless of plugin track records

## Revisit Condition

Revisit this decision when any of the following become true:

1. A consumer of GLOBAL (EigenTrust) scores is added to quarkmind (e.g., cross-session
   agent ranking, inter-plugin trust propagation)
2. Cross-plugin attestation is introduced (plugins rating each other's outputs) — at that
   point the peer graph is no longer degenerate and EigenTrust adds information
3. The plugin pool grows to a scale where 3-cycle non-convergence is statistically
   improbable (GE-20260421-09d636 establishes ~50 actors as the threshold below which
   exact 3-cycles are common; above that scale, the original paper's analysis holds)

## Links

* Refs #168
* Gates: L4 (#156) activates Bayesian Beta; L6 (#158) adds `casehub-engine-ledger` for
  routing; this ADR documents why EigenTrust GLOBAL scores are treated as inert at both layers
* GE-20260421-09d636 — EigenTrust 3-cycle non-convergence in small graphs (garden: java/)
* `casehub-ledger` capability table in `casehub-parent/docs/PLATFORM.md` — "Actor trust
  scoring (Bayesian Beta + EigenTrust)": EigenTrust component produces GLOBAL scores not
  consumed for plugin capability routing in this deployment
* ARC42STORIES.MD §L4, §L6 stubs — Bayesian Beta is the active routing signal;
  EigenTrust computation is inert

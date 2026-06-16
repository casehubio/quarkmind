# Design: ARC42STORIES.MD §9.3 Chapter Entries — C2–C5

**Date:** 2026-06-16 (revised 2026-06-17 ×5)
**Issue:** #198
**Branch:** issue-198-arc42-c2-c5-chapters

## Context

§9.3 chapter entries for C2–C5 are stubs — "🔲 Full Chapter entry at CN close." Their corresponding layers are complete (L3 #155, L4 #156, L5 #157, L6 #158) but narrative entries were never written. C1 and C6 are complete and serve as structural references.

## Template

Each entry follows this structure (Option B — C1 template + Known limitations where applicable):

```
#### Chapter N — <Title>

**Journey:** ... | **Sequence:** N of 6 | **Status:** ✅ complete
**Delivered:** <date> | **Issues:** #NNN | **Blog:** `blog/<filename>.md`

**What this delivers**
2–3 sentences on what capability this chapter introduces and what it enables architecturally.

**Accountability gaps closed**
- <Gap (source)> → <what closes it>
- <Still-open gap> → 🔲 CN  ← forward pointer if applicable

**Layer Impact**
| Layer | Delta |
|---|---|
| LN <name> | <impact> |

**Known limitations / open gaps**  ← omit if chapter has none
- <specific caveat or deferred item>
```

Chapters with a Known limitations section: **C2** (DECLINE not wired), **C4** (canActivate() override), **C5** (Phase 2 migration, win/loss detection). C3 is clean.

## Chapter Content

### C2 — Plugin Intel Channels

**Title note:** The original C2 title "Formal Plugin Obligation" was planned to cover commitment lifecycle, structural DECLINE, and `MessageLedgerEntry` per speech act. None of that was delivered. What was delivered is the dual-stack intel distribution mechanism. The chapter is retitled "Plugin Intel Channels" to match actual delivery. The §9.2 chapter table, Mermaid diagram, and sequencing rationale also need updating (see Quality Sweep).

**Header (copy-paste):**
`**Journey:** Game Agent Coordination | **Sequence:** 2 of 6 | **Status:** ✅ complete`
`**Delivered:** 2026-06-01, formalised 2026-06-04 (#177) | **Issues:** #155, #177 | **Blog:** blog/2026-06-01-mdp01-phase5-complete.md; blog/2026-06-04-mdp01-three-cleanups-strategy-upgrade.md`

**What this delivers:** `ScoutingIntelBroker` dual-stack — a synchronous in-memory store (Stack 1) for plugin-to-plugin intel delivery, and a Qhorus channel (Stack 2, `quarkmind-scouting-intel`) as the advisory surface for external consumers (LLM advisors, Commentator). Plugins — `DroolsTacticsTask`, `DroolsStrategyTask`, `FlowEconomicsTask` — implement `ScoutingIntelConsumer` and read typed scouting intel via `broker.current()` (Stack 1 only); the Qhorus channel carries scouting intel payloads for advisory consumers, not for plugin-to-plugin coordination. `TacticsIntelCache` and `TacticsMessageBridge` removed; `MessageObserver` wiring eliminated.

**Accountability gaps closed:**
- No typed inter-plugin intel delivery (L1 gap) → `ScoutingIntelBroker` Stack 1 synchronous in-memory delivery to plugin consumers

**Layer Impact:**
| Layer | Delta |
|---|---|
| L3 casehub-qhorus | High — `ScoutingIntelBroker` dual-stack; `ScoutingIntelConsumer` interface; `quarkmind-scouting-intel` Qhorus channel (advisory); `TacticsIntelCache`, `TacticsMessageBridge` removed |
| L2 casehub-engine | Low — dispatch unchanged; plugin intel flows via broker alongside CaseFile |

**Known limitations / open gaps**
- Quality Goal 2 (Formal DECLINE) not closed: DECLINE speech act is platform-defined in casehub-qhorus but not wired in QuarkMind game-loop dispatch. See new issue created during Quality Sweep.

---

### C3 — Outcome Tracking

**Header (copy-paste):**
`**Journey:** Game Agent Coordination | **Sequence:** 3 of 6 | **Status:** ✅ complete`
`**Delivered:** 2026-06-05 | **Issues:** #156 | **Blog:** blog/2026-06-02-mdp03-eigentrust-inert-single-attestor.md; blog/2026-06-06-mdp01-layer4-ledger-integration.md`

**What this delivers:** every plugin decision is recorded via casehub-ledger with plugin ID, decision context, and game state. Bayesian Beta trust scores accumulate per plugin over time. Writes are async non-blocking — game-loop latency is unaffected. In-memory backend used for mock and emulated profiles (lightweight mode — tamper-evident guarantees not needed for game agent decisions). EigenTrust inert; single-attestor mode is correct for this domain (ADR-0009).

Note: the §9.2 sequencing rationale "C2 before C3: plugin obligation records (L3 commitment chain) are the ledger entries that make outcome tracking meaningful in C3" is wrong on two counts — no commitment chain was delivered in C2, and C3's recording mechanism is `PluginOutcomeAuditor` (which observes `PluginDecisionEvent` asynchronously and calls `outcomeRecorder.record()`), not `GameOutcomeRecorder` (which is C5's per-game strategy outcome recorder). `PluginOutcomeAuditor` has no runtime dependency on the broker; it only consumes CDI events fired by plugins. The §9.2 sequencing rationale must be corrected (see Quality Sweep).

**Accountability gaps closed:**
- No audit trail (L1 gap) → casehub-ledger attestation per plugin decision
- No outcome tracking (L1 gap) → Bayesian Beta trust scores accumulated per plugin via casehub-ledger

**Layer Impact:**
| Layer | Delta |
|---|---|
| L4 casehub-ledger | High — `PluginOutcomeAuditor` (@ObservesAsync `PluginDecisionEvent`) writes ledger entries; Bayesian Beta trust scoring; async non-blocking writes; in-memory backend for dev profiles |
| L2 casehub-engine | None |

No Known limitations section — delivery clean.

---

### C4 — Adaptive Plugin Selection

**Header (copy-paste):**
`**Journey:** Game Agent Coordination | **Sequence:** 4 of 6 | **Status:** ✅ complete`
`**Delivered:** 2026-06-03 | **Issues:** #157 | **Blog:** blog/2026-06-03-mdp01-layer5-adaptive-plugin-selection.md; blog/2026-06-04-mdp01-three-cleanups-strategy-upgrade.md`

**What this delivers:** two structurally distinct gate mechanisms replace hardwired all-plugins-every-tick dispatch. (1) `requires()` — CaseFile key-presence ordering dependency: `ENEMY_ARMY_SIZE` on `DroolsStrategyTask` ensures scouting always runs before strategy in the CaseEngine re-evaluation loop. (2) `activateIf()` broker state gate — `broker.current(THREAT_POSITION).isPresent()` on `DroolsTacticsTask` skips tactics when no threat position is known; `broker.current(POSTURE).isPresent()` on `DroolsStrategyTask` suppresses strategy in early game until scouting has classified the enemy posture. The Pattern 2 broker gates were introduced by the #177 dual-stack redesign (delivered under C2), replacing the earlier CaseFile `NEAREST_THREAT` key gate that existed at the initial #157 commit; they belong to the L5 story per §9.4 as the final form of the conditional skip pattern. `canActivate(CaseFile)` combines `requires()` and `activateIf()` via `testActivation()`. `GameTickExecutor` extracted from `AgentOrchestrator.gameTick()` to surface `CaseFile` state for testing.

**Accountability gaps closed:**
- Fixed all-plugins-every-tick dispatch (L1 gap) → binding-condition dispatch via `requires()` + `activateIf()`

**Layer Impact:**
| Layer | Delta |
|---|---|
| L5 Adaptive Plugin Selection | High — `requires()` ordering gates and `activateIf()` CDI-state gates on all plugins; `GameTickExecutor` extraction |
| L2 casehub-engine | Medium — `AgentOrchestrator.gameTick()` refactored; `testActivation()` override pattern established across all plugins |

**Known limitations / open gaps**
- `canActivate(CaseFile)` in the installed poc casehub-core snapshot returns `true` unconditionally — does not call `testActivation()`. All four plugin classes explicitly override `canActivate()` with `testActivation(new CaseFileContext(caseFile))`, which evaluates both `requires()` and `activateIf()`. Overrides can be removed once the foundation corrects the default.

---

### C5 — Trust-weighted Routing

**Header (copy-paste):**
`**Journey:** Game Agent Coordination | **Sequence:** 5 of 6 | **Status:** ✅ complete`
`**Delivered:** 2026-06-10 | **Issues:** #158 | **Blog:** blog/2026-06-12-mdp01-wiring-playerresult-to-trust.md; blog/2026-06-12-mdp02-trust-routing-is-degenerate-cbr.md; blog/2026-06-15-mdp01-casehub-engine-phase1.md`

**What this delivers:** among three competing `StrategyTask` implementations, `StrategyTrustRouter` selects using opponent-context-keyed Bayesian Beta scores from casehub-ledger. A four-phase trust maturity model (BOOTSTRAP phaseScore=0.5, QUALIFIED threshold 0.838) ensures any candidate that has accumulated qualified outcomes beats any unproven one — correct cold-start behaviour without requiring prior game data. `strategy.drools` is the designated fallback, winning all ties and exempt from BORDERLINE exclusion. At game start, routing uses `STRATEGY_VS_UNKNOWN`; at the first mid-game checkpoint (when `DroolsScoutingTask` fires `EnemyPostureClassifiedEvent`), the router re-selects for the classified opponent context. `LedgerLifecycleAdapter` removed — it was clearing the in-memory ledger between games, capping trust accumulation at one decision.

**Accountability gaps closed:**
- No per-outcome trust signal (L4 gap, C3) → `GameStopped` carries `GameResult`; WIN→ENDORSED, LOSS→CHALLENGED, TIE→SOUND, UNKNOWN→skipped; Bayesian Beta scores evolve from real game outcomes

Note: trust-weighted routing is not closure of an L1 gap. L1 gaps were fully closed by C1–C4. C5 introduces a new architectural capability — opponent-context-keyed strategy selection — enabled by L4 trust data that did not exist until C3.

**Layer Impact:**
| Layer | Delta |
|---|---|
| L6 Trust Routing | High — `StrategyTrustRouter`, `StrategyTrustObserver`, `StrategySelector`, `GameOutcomeRecorder`; four-phase trust maturity model; `strategy.drools` designated fallback; `EarlyPressureStrategyTask` and `EconomicExpansionStrategyTask` introduced as competing implementations; `DroolsStrategyTask.activateIf()` extended to add `strategySelector.isSelected(getId())` alongside the existing #177 POSTURE broker gate — enforces exactly one strategy fires per tick |
| L4 casehub-ledger | Medium — `TrustGateService` drives routing decisions; trust scores read per opponent context |
| L2 casehub-engine | Low — Phase 1 migration (#193): `StrategyTrustRouter` implements `io.quarkmind.agent.TaskDefinition`; `execute(CaseContext)` writes selected strategy ID to `agent.strategy.selected.id` |

**Known limitations / open gaps**
- Phase 1 configuration: `StrategyTrustObserver` (CDI event observer) still drives selection; Phase 2 migration to `SequenceWorker` (engine#484) will make trust routing structural and event-log-visible
- Win/loss detection: mock, emulated, and replay profiles produce `UNKNOWN` and are skipped — correct, no meaningful win/loss signal outside real SC2. Trust scores only evolve from real SC2 games.

## Source Material

- §9.4 layer entries in `ARC42STORIES.MD` — primary source for all four chapters
- Blog entries listed per chapter above
- Source code verified via IntelliJ MCP: `ScoutingIntelBroker`, `DroolsTacticsTask`, `DroolsStrategyTask`, `GameOutcomeRecorder`, `PluginDecisionEvent`, `PluginOutcomeAuditor`, `StrategySelector`

## Quality Sweep (post-write)

After writing the entries into ARC42STORIES.MD, run in order:

1. **Issue status** — verify #155, #156, #157, #158 are OPEN (or note CLOSED)

2. **Class name existence** — confirm via IntelliJ MCP find: `ScoutingIntelBroker`, `PluginOutcomeAuditor`, `StrategyTrustRouter`, `GameOutcomeRecorder`, `GameTickExecutor`, `StrategyTrustObserver`, `StrategySelector`

3. **Create DECLINE wiring issue** — file a new GitHub issue: "feat: wire DECLINE speech act in QuarkMind game-loop dispatch (Quality Goal 2)". Record the new issue number.

4. **Fix C6 capability table** — update the DECLINE row parenthetical from `(pending C2 chapter entry — #198)` to `(#NNN — DECLINE wiring)` where NNN is the new issue from step 3.

5. **Fix §9.2 C2 title references** — update the Mermaid diagram label (`C2["C2: Plugin Intel Channels\n+ L3"]`), the chapter table row title, and the sequencing rationale ("C2 before C3: ...") to match actual delivery. The sequencing rationale should be replaced with an honest layer-sequence statement: "C2 before C3: layer sequence — L3 (typed inter-plugin channels) precedes L4 (ledger) in the delivery sequence; no runtime dependency exists between the broker and `PluginOutcomeAuditor`."

6. **Forward refs** — any `#NNN` in the written entries must exist on GitHub

7. **§9.2 status colours** — update C2–C5 Mermaid nodes from `fill:#D3D3D3` (pending) to `fill:#90EE90` (complete), matching C1 and C6

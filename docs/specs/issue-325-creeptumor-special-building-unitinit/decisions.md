## D1: Ability-placed structure pipeline path

**Choice:** Route through existing BuildCommand — ability-placed structures (CreepTumor, NydusCanal, OracleStasisTrap) dispatch as BuildCommand from AbilityMapping and flow through handleBuild()
**Alternatives:**
- New AbilityPlaceCommand sealed variant — explicit semantics but identical functional output, touches all switch expressions over ReplayCommand
- TrainCommand path — semantically wrong, pollutes train path with building logic
**Rationale:** handleBuild() already emits UnitInit + UnitDone with correct timing. The semantic distinction (worker-built vs ability-placed) is irrelevant for feature extraction — only event type and timing matter. Zero new infrastructure.
**Trade-offs:** Losing explicit semantic separation between worker-built and ability-placed structures. If future classification needs to distinguish placement method, the distinction would need to be recovered from the abilLink.
**Sources:** StrippedReplayFeatureExtractor.java:326 (handleBuild), AbilityMapping.java:420 (dispatchHuman), ReplayCommand.java (sealed interface)
**Exploration:** quick
**Status:** captured

## D2: Auto-spread CreepTumor synthesis model

**Choice:** Single-spread chain synthesis — each tumor spreads exactly once after maturation (matching SC2 mechanics), producing linear chain growth. Runs as a post-extraction step within the per-player loop, inside the elapsedLoops null guard. Per-player cap calibrated from oracle data.
**Alternatives:**
- Post-extraction synthesis pass in a separate class — cleaner separation but premature for this scope
- Statistical estimation (multiplier) — fast but non-deterministic, inaccurate per-replay
**Rationale:** SC2 tumors spread exactly once, not repeatedly. Oracle ratio (646/313 ≈ 2.1 auto-spread per Queen) confirms short linear chains. Single-spread model matches the mechanic and avoids exponential growth that would require aggressive capping.
**Trade-offs:** No terrain awareness — spread fires on a timer regardless of whether creep can physically expand. Per-player cap approximates game limits. Accuracy depends on calibration quality.
**Sources:** StrippedReplayFeatureExtractor.java:269 (handleTrain deferred events), oracle data (CreepTumor: 646, CreepTumorQueen: 313 across 118 replays)
**Exploration:** quick
**Depends on:** D1 (ability-placed structures flow through BuildCommand — the initial Queen-placed tumor uses this path)
**Status:** captured

## D3: Building under-counting fix approach

**Choice:** Diagnostic-first — run AbilityMappingCoverageDiagnosticTest with building-specific output to identify which abilLinks/abilCmdIndex values produce building events in oracle but not in Java, then apply targeted fixes.
**Alternatives:**
- Speculative fix (preemptively add CmdUpdateTargetPointEvent handling + expand all index maps) — risks fixing wrong things and introducing new bugs without evidence
**Rationale:** Under-counting has multiple possible causes (CmdUpdateTargetPointEvent not handled, abilCmdIndex gaps, add-on buildings). Guessing wastes time. Diagnostic pinpoints the exact gaps, and the fix is only as large as the actual problem.
**Trade-offs:** Requires running diagnostic first (adds a step), but prevents unnecessary code changes.
**Sources:** Issue #325 body (SupplyDepot 75%, Pylon 82%, MissileTurret 64%, Refinery 82%), AbilityMappingCoverageDiagnosticTest
**Exploration:** quick
**Status:** captured

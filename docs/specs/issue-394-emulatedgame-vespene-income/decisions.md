# Decisions — #394 EmulatedGame Vespene Income

## D1: Worker assignment model

**Choice:** Implicit — compute gas workers as 3 × completed gas buildings, subtract from mineral pool
**Alternatives:**
- Explicit tracking — add workerAssignment state to PlayerState; more accurate for partial saturation but adds complexity
- Configurable per gas building — allow playbooks to override workers-per-geyser; middle ground but unnecessary at this stage
**Rationale:** Matches the existing pattern where mineral workers are computed on-the-fly via `countWorkersPerBase()`, not tracked as state. No new state management needed.
**Trade-offs:** Cannot model partial gas saturation (e.g. only 2 workers on a geyser). Acceptable for emulation fidelity.
**Sources:** EmulatedGame.java:944-968 (countWorkersPerBase), PlayerState.java
**Exploration:** quick
**Status:** captured

## D2: Gas income rate model

**Choice:** Tiered rates per worker like minerals — 3-tier `GAS_TIER_RATES_PER_TICK` array
**Alternatives:**
- Flat rate per worker (~20.3 gas/min/worker) — simpler but ignores diminishing returns on 3rd worker
**Rationale:** Consistency with the mineral model (`MINERAL_TIER_RATES_PER_TICK`) and better accuracy for the 3rd worker penalty.
**Trade-offs:** Slightly more code than a flat constant. Initial values from community data, not replay-calibrated — calibration is a follow-up.
**Sources:** SC2Data.java:33-59 (MINERAL_TIER_RATES_PER_TICK), sc2data-train-times-require-calibration protocol
**Exploration:** quick
**Depends on:** D1 (worker count per geyser feeds the tier lookup)
**Status:** captured

## D3: Scope includes playbook vespene assertions

**Choice:** Add vespene bounds checking to `SC2DeliveryHandler.doAssert()` in this issue
**Alternatives:**
- Separate follow-up issue — stricter scope but leaves no way to validate the income model via playbook tests
**Rationale:** Small addition (~10 lines), and without it the foundation work cannot be validated in playbook calibration tests.
**Trade-offs:** Slightly wider scope than "just income model."
**Sources:** SC2DeliveryHandler.java:116-155 (doAssert), SC2PlaybookCalibrationTest
**Exploration:** quick
**Status:** captured

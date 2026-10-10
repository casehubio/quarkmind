## D1: Overall approach — playbook optimization + fidelity fix + 2nd OC morph

**Choice:** Approach C — fix supply depot timing (pre-build), reorder CC/OC morph timing, fix `drainBuildingQueues()` isComplete check, add morph-during-train test coverage, and add 2nd OC morph (CC-2 → OC-2) to the playbook.
**Alternatives:**
- Approach A (playbook + fidelity fix, no 2nd OC) — simpler but leaves SCV count more sensitive to timing; 2nd OC morph is standard Terran play
- Approach B (playbook only) — easiest to hit target but leaves queue-during-morph fidelity bug
**Rationale:** Full audit scope matches user intent. 2nd OC morph is realistic Terran play (double MULE income funds depots), the fidelity fix ensures EmulatedGame's "living spec" role is accurate, and pre-building depots eliminates the largest source of lost production ticks.
**Trade-offs:** 2nd OC morph creates another 25-tick production gap on CC-2, which must be managed via build timing. Higher playbook complexity.
**Sources:** EmulatedGame.java:469-484 (drainBuildingQueues), EmulatedGame.java:802-833 (handleMorph), economy-only-terran.yaml, issue #398 analysis
**Exploration:** quick
**Status:** captured

### Implementation notes (build order / timing — derived from SC2 data, not design decisions)

These are SC2 domain details to be validated via the calibration test, not architectural choices:

- **Depot pre-building:** Trigger depots ~2 supply before cap to match real SC2 pro play. Depot build time (21 ticks) vs SCV train time (12 ticks) means a 2-supply lead eliminates supply blocks.
- **CC-2 timing:** Order earlier than current 20 supply to reduce the production blackout during OC morph. Exact timing determined by mineral affordability and validated by the calibration test.
- **OC-2 morph:** Morph CC-2 to OC immediately on completion — standard Terran play for double MULE income. The 25-tick production gap is covered by OC-1 continuing to train.

## D2: drainBuildingQueues fidelity fix

**Choice:** Add `isComplete` check in `drainBuildingQueues()` so queued units don't start training on a morphing building. In real SC2, the production queue pauses during a morph and resumes after completion.
**Alternatives:**
- Leave as-is — the current behavior (queue drains during morph) produces slightly more SCVs than real SC2, which would help our SCV count target but reduces simulation fidelity
**Rationale:** EmulatedGame is the "living spec" for SC2 behavior. Correct mechanics matter more than hitting a specific SCV count — the playbook fix compensates for the small throughput loss.
**Trade-offs:** Slightly fewer SCVs produced during morph windows. Playbook timing must account for this.
**Depends on:** D1 (approach)
**Sources:** EmulatedGame.java:469-484 (drainBuildingQueues — no isComplete check), EmulatedGame.java:350-431 (handleTrain — has isComplete check), PhysicsState.java:39-45 (fireCompletions — no isComplete check, correct for in-progress training)
**Exploration:** quick
**Status:** captured

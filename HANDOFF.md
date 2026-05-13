# Handover — 2026-05-13

**Head commit:** `59b2cd2` — spec and plan files for #128

## What Changed This Session

**#128 — Parallel training queues (closed):**
- `PlayerState`: `buildingQueues` + `buildingTrainingUntil` maps; supply reserved at queue time
- `handleTrain`: building existence + type validation (`SC2Data.trainedBy`), queue depth cap (5)
- `drainBuildingQueues` wired into `tick()` after `fireCompletions`
- `EnemyBehavior`: real building tags from `enemy.buildings`, no more fake `"enemy-nexus-N"` tags
- `TrainIntent.unitTag` → `buildingTag` (IntelliJ rename; `Intent.unitTag()` removed as dead interface method — #132 closed)
- Building type validation added (#133 closed)

**#129 — Auto-engage (closed):**
- Removed `attackingUnits` gate from both loops in `resolveCombat`
- Removed stop-to-fight range check from `moveEnemyUnits`
- All units fire at nearest enemy in weapon range each tick; fire-while-moving
- 11 existing tests adjusted for blast radius; `attackingUnits` retained as dead state (#134 open)

**Open follow-up issues:** #134 (attackingUnits cleanup), #135 (focus-fire coverage gap), #136 (test nits)

## Immediate Next Step

**#130 — Realistic mineral saturation** — the only remaining Phase 5 item. Replace the flat `miningProbes * MINERALS_PER_PROBE_PER_TICK` income with a worker-count-dependent model: linear to 16, diminishing returns 16–24, no gain above 24. Entry point: `EmulatedGame.tick()` line 86.

## Open Issues

| # | What | Status |
|---|------|--------|
| #13 | Live SC2 smoke test | Blocked on SC2 |
| #14 | GraalVM native image | Blocked on #13 |
| #74 | YAML unit definitions | Parked |
| #130 | Mineral saturation | Next |
| #131 | Deferred visualizer work | Pending |
| #134 | attackingUnits dead state cleanup | Pending |
| #135 | Focus-fire engine coverage gap | Pending |
| #136 | EmulatedGameTest nits | Pending |

## References

| Context | Where |
|---------|-------|
| Blog entry (this session) | `~/claude/public/quarkmind/blog/2026-05-13-mdp01-the-engine-fights-back.md` |
| Phase 5 gaps | `docs/roadmap-sc2-engine.md` — one item left (#130) |
| #128 spec | `docs/superpowers/specs/2026-05-12-parallel-training-queues-design.md` |
| #129 spec | `docs/superpowers/specs/2026-05-12-auto-engage-design.md` |
| Prior handover | `git show HEAD~1:HANDOFF.md` |

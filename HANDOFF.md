# Handoff — issue-761-intent-rejection-diagnostic

## Branch state

On `issue-761-intent-rejection-diagnostic`, rebased on main. 12 commits this session. Clean working tree. All SC2 comparison dual-run tests pass. Self-baselines need re-recording (expected after physics changes on the branch).

## What this session did

Investigated the -16.4% pinned single-base economy gap (#763). The session progressed through investigation, SC2 measurement, fix attempts, and ended by discovering a pre-existing calibration regression.

### Investigation (diagnostic tests committed)

- **Per-tick economy trace** (`ContentionCalibrationDiagnosticTest.verifyRallyExitPosition`) — shows EM ahead early (+235 at tick 50), falls behind from tick 150, reaches -565 at tick 304
- **Fixed-vs-pinned comparison** (`pinnedVsFixedWorkerComparison`) — fixed 36w = +0.8% (physics correct), pinned = -10.8% without buildings
- **Per-worker injection trace** (`traceInjectedWorkerFirstCycles`) — first delivery takes 10 ticks (steady-state: 5). A* routes away from patches before reversing
- **Buildings comparison** (`pinnedNoBuildingsComparison`) — buildings at (30,30) accounted for 340 minerals of the gap

### SC2 measurement (#767)

- **First-delivery profiler** (`WorkerPhysicsProfilerTest.profileFirstDeliveryTiming`) — 24 probes on TorchesAIE
- SC2 probes spawn at Nexus EDGE (126.6, 161.4), NOT center
- Mean first-delivery: 170 loops (7.7 ticks), range 6.3-11.7 ticks
- Timing increases with saturation: 6.3t at 12w, 9.3t at 36w
- Data saved to `profiler/first-delivery-timing-run1.json`

### Fix attempts (all reverted)

| Fix | Effect | Why reverted |
|-----|--------|-------------|
| Building footprint 6×6→5×5 | 8w: +0.4%→+12.9% | Breaks all baselines — the 6×6 provides walk overhead that empirically matches SC2 |
| Rally exit point | First delivery 10→4 ticks | Workers arrive too fast, contention offsets gains |
| Direct-line walk | Fixed 36w: +0.8%→+2.7% | No obstacle avoidance = too fast |
| Goal-biased A* start | First delivery 10→12 ticks | Different routing = more contention |

### Fix kept

- **Real building positions** — `cal-single-base-probes-buildings.json` recorded from SC2. Pinned test improved from -16.4% to -15.1%.

### Critical discovery

Fresh SC2 baselines (re-recorded this session) reveal a **pre-existing calibration regression** from prior sessions' changes (#766):

| Baseline | SC2 | EM | Delta | On main |
|----------|-----|-----|-------|---------|
| 8w | 2335 | 2635 | **+12.9%** | +0.4% |
| 12w | 3415 | 3325 | **-2.6%** | -0.3% |

Root cause: #766's `smoothed.set(smoothed.size() - 1, target)` in `setTargetWithPath` and obstacle radius 0.3→0.5 improved waypoint precision but shifted mining rates. The existing physics constants were calibrated against imprecise paths.

## Issues

| Issue | Status | Notes |
|-------|--------|-------|
| #763 | Open, blocked on #768 | Pinned gap investigation complete; fix blocked on correct per-worker rates |
| #767 | Done (measurement recorded) | SC2 first-delivery profiler data captured |
| #768 | **New — Critical** | 8w +12.9% overshoot from #766 changes. Must fix before any other accuracy work |

## Next session should

1. **Fix #768** — isolate whether the tile-center snap or obstacle radius change causes the 8w +12.9% overshoot. Recalibrate constants. This is the foundational regression.
2. After #768: re-apply footprint fix (5×5) + rally exit point. These are geometrically correct and SC2-validated but can't land until per-worker rates are right.
3. After that: re-measure the pinned gap. It should be significantly reduced.

## Key files

| File | What |
|------|------|
| `docs/sc2-physics-constants.md` §16 | Full gap analysis, measurement data, stale baseline warning |
| `quarkmind-sc2/src/test/resources/profiler/first-delivery-timing-run1.json` | SC2 per-probe first-delivery data |
| `quarkmind-sc2/src/test/resources/calibration/cal-single-base-probes-buildings.json` | SC2 building positions for pinned test |
| `quarkmind-sc2/src/test/java/.../ContentionCalibrationDiagnosticTest.java` | All diagnostic tests from this session |
| `quarkmind-sc2/src/test/java/.../WorkerPhysicsProfilerTest.java` | SC2 first-delivery profiler |

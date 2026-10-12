---
id: PP-20261012-a7c3f1
title: "Calibration milestones are waypoints — reach, assess, replan"
type: rule
scope: repo
applies_to: "All calibration and accuracy issues under the emulator-driven reconstitution pipeline (#384)"
severity: important
refs:
  - docs/benchmarks/emulated-game-accuracy-baseline.md
  - docs/benchmarks/oracle-accuracy-baseline.md
violation_hint: "An issue is being closed as 'done' with acceptance criteria treated as final targets rather than waypoints — or a new milestone is planned without an assessment of what the data revealed about the current approach"
created: 2026-10-12
---

Calibration milestones define waypoints, not destinations. Each milestone sets a numeric target to reach — but reaching it (or getting close enough that returns diminish) triggers an assessment cycle, not a victory lap.

## The cycle

1. **Set target** — a numeric threshold that's ambitious enough to force real progress but bounded enough to be achievable within the current approach.
2. **Work toward it** — calibrate constants, fix mechanics, improve extraction.
3. **Reach or plateau** — either hit the target or identify why the current approach can't get there.
4. **Assess** — what did the data reveal? Is the measurement methodology sound? Is the approach still the right one? What's the biggest remaining gap and what would close it?
5. **Replan** — design the next milestone with a potentially different approach. The assessment may reveal that the next gain requires a methodology shift (e.g., CmdEvent extraction → tracker-event validation), not just more of the same.

## Why this matters

Without explicit waypoints, calibration work either tunnels indefinitely on diminishing returns or stops too early because a soft target was met. The waypoint pattern forces periodic reassessment of both the target and the methodology.

## What goes in the closing assessment

Each milestone issue closes with a comment documenting:
- **Baseline at close** — current accuracy numbers at the target checkpoint
- **What improved and why** — specific fixes and their measured impact
- **What didn't improve and why** — the ceiling or bottleneck that limited further progress
- **Methodology verdict** — is the current validation approach still measuring the right thing?
- **Next milestone recommendation** — what target, what approach, what's changed

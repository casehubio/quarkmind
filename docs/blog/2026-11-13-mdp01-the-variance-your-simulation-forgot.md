---
title: The Variance Your Measurement Invented
date: 2026-11-13
tags: [quarkmind, emulation, calibration, variance, starcraft, physics]
---

# The Variance Your Measurement Invented

Your simulation gets the average right. The total mineral income at 5 minutes is within 0.1% of the real game. Every parameter has been swept, every constant calibrated. The aggregate numbers match.

Then you look at individual worker trips.

In StarCraft II, a Probe mining cycle — walk to patch, harvest, walk back, deposit — appears to take anywhere from 2 to 23 ticks. The distribution looks right-skewed: about 43% of cycles complete in just 2–3 ticks, another 30% take 4–5, and a long tail stretches past 15. The standard deviation is 2.54 ticks.

In the EmulatedGame, the same 12 workers produce cycles that are almost perfectly uniform: 87% land on exactly 5 ticks. Standard deviation 0.34.

Obviously the simulation is too narrow. The variance gap is 7× and the range is wrong. So you spend a week building a direction-dependent collision model, tuning head-on penalties, adjusting acceleration constants, and shuffling worker processing order. You get σ from 0.34 to 2.25. Almost there.

Then you find the measurement bug.

## The array reorder trap

SC2 reorders workers in the observation array every tick. Worker at index 0 in tick T is a different Probe than index 0 in tick T+1. The cycle tracker was using array indices, not worker tags. It measured phantom transitions between different workers, creating false short cycles.

The corrected distribution — tracking by worker tag, not array position:

| Metric | Index-based (buggy) | Tag-based (correct) |
|---|---|---|
| σ | 2.54 | **0.58** |
| Mean | 4.47 | **5.32** |
| Range | 2–23 | **4–6** |
| 2-tick bucket | 21.4% | **0%** |

SC2 mining cycles are extremely uniform. 57% complete in exactly 5 ticks. 38% in 6 ticks. 6% in 4 ticks. Nothing outside that range, across all five recorded games. The "high variance" was an artefact of confusing different workers at the same array index.

The simulation at σ=0.34 was closer to reality than the "improved" version at σ=2.25.

## What the correction revealed

The direction-dependent collision model — head-on workers near-stopping, same-direction workers passing transparently — was designed for a target that didn't exist. It produced a long congestion tail that SC2's uniform distribution doesn't have. The harsh 0.2× speed penalty created 10-20 tick outlier cycles that no real SC2 worker ever experiences.

Reverting to a uniform collision factor (0.96) and restoring the original acceleration (3.5 u/s², from SC2Mapster) produces:

| Metric | Original | After fix | SC2 |
|---|---|---|---|
| σ | 0.34 | 0.90 | 0.58 |
| Mean | 4.89 | 5.32 | 5.32 |
| 4-tick | — | 6.7% | 5.7% |
| 5-tick | 87% | 65.6% | 56.8% |
| 6-tick | — | 21.4% | 37.5% |

The mean matches exactly. The 4-tick bucket matches within a percentage point. The 5/6 split is still off — the EM is too peaked at 5 ticks — but the shape is recognisably the same distribution, not a different one.

The per-loop shuffle is still correct physics. SC2 does randomise processing order every game loop. It just doesn't produce much variance — mining cycles are dominated by walk distance and overhead phases, not by who processes first. The shuffle matters for fairness (no worker is systematically advantaged), not for spread.

## The real lesson

The lesson isn't about StarCraft. It's about measurement assumptions.

When your simulation disagrees with your measurement, the instinct is to fix the simulation. You add complexity — direction-dependent penalties, asymmetric yielding, processing order shuffling — because the gap between simulation and measurement is the thing you can see, and adding physics feels like progress.

But the measurement can be wrong. An array index is not an identity. A phantom transition between different workers at the same array slot looks exactly like a fast cycle of one worker. The resulting distribution has the right number of data points and plausible statistical properties. Nothing about it screams "bug."

The tell was that every SC2 worker achieved 2-tick cycles regardless of patch distance. Workers 6–8 units from the town hall cannot physically complete a round trip in 44 game loops. That should have been the signal to question the measurement before building a week of physics corrections on top of it.

Measure twice. Simulate once.

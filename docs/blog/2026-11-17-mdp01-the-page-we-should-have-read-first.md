---
layout: post
title: "The Page We Should Have Read First"
date: 2026-11-17
entry_type: note
subtype: diary
projects: [casehubio/quarkmind]
tags: [emulated-game, economy, gas-income, sc2-physics, liquipedia, calibration]
series: standalone-emulated-game
---

# The Page We Should Have Read First

*Continues the [standalone-emulated-game series](2026-11-15-mdp01-where-the-gas-workers-walk.md). We spent a full session trying to explain a 6.7% gas surplus in a dual-geyser scenario. The answer was on Liquipedia the entire time.*

## The surplus

The emulator's gas cycle was exact for a single geyser: 3 workers on 1 geyser, 552 vespene at tick 304. Zero error. But add a second geyser at the same base — 6 workers on 2 geysers — and the emulator produced 1080 vespene while SC2 produced 1012. A 6.7% surplus that grew linearly through the run.

The per-geyser rate was calibrated. The geyser distances were identical (7.62 units from the town hall, confirmed from SC2 API positions). The workers walked on completely independent, non-crossing paths. Same total workers at the base (15 in both scenarios — 3w had 3 gas + 12 mineral, 6w had 6 gas + 9 mineral). Every physics-based mechanism I tried — deposit locks, collision handling, entry staggering — topped out at a 12-vespene improvement. The gap was 68.

## What I tried

The session was a systematic elimination of hypotheses:

<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 700 320" style="max-width:700px; font-family: system-ui, sans-serif;">
  <defs>
    <marker id="arrowhead" markerWidth="10" markerHeight="7" refX="10" refY="3.5" orient="auto">
      <polygon points="0 0, 10 3.5, 0 7" fill="#666"/>
    </marker>
  </defs>
  <rect x="10" y="10" width="680" height="300" rx="8" fill="#f8f9fa" stroke="#dee2e6"/>
  <text x="350" y="35" text-anchor="middle" font-weight="bold" font-size="14" fill="#333">Hypotheses tested (all insufficient)</text>
  <g transform="translate(40,55)">
    <rect width="180" height="50" rx="6" fill="#fff3cd" stroke="#ffc107"/>
    <text x="90" y="20" text-anchor="middle" font-size="11" fill="#664d03">Town hall deposit lock</text>
    <text x="90" y="36" text-anchor="middle" font-size="10" fill="#997404">Fires 3 times then self-resolves</text>
  </g>
  <g transform="translate(260,55)">
    <rect width="180" height="50" rx="6" fill="#fff3cd" stroke="#ffc107"/>
    <text x="90" y="20" text-anchor="middle" font-size="11" fill="#664d03">Gas-mineral collision</text>
    <text x="90" y="36" text-anchor="middle" font-size="10" fill="#997404">Same total workers at base</text>
  </g>
  <g transform="translate(480,55)">
    <rect width="180" height="50" rx="6" fill="#fff3cd" stroke="#ffc107"/>
    <text x="90" y="20" text-anchor="middle" font-size="11" fill="#664d03">Entry stagger</text>
    <text x="90" y="36" text-anchor="middle" font-size="10" fill="#997404">Scales equally for 3w and 6w</text>
  </g>
  <g transform="translate(40,125)">
    <rect width="180" height="50" rx="6" fill="#fff3cd" stroke="#ffc107"/>
    <text x="90" y="20" text-anchor="middle" font-size="11" fill="#664d03">Geyser entry lock</text>
    <text x="90" y="36" text-anchor="middle" font-size="10" fill="#997404">Natural stagger prevents firing</text>
  </g>
  <g transform="translate(260,125)">
    <rect width="180" height="50" rx="6" fill="#fff3cd" stroke="#ffc107"/>
    <text x="90" y="20" text-anchor="middle" font-size="11" fill="#664d03">Processing order shuffle</text>
    <text x="90" y="36" text-anchor="middle" font-size="10" fill="#997404">Zero effect without collision</text>
  </g>
  <g transform="translate(480,125)">
    <rect width="180" height="50" rx="6" fill="#fff3cd" stroke="#ffc107"/>
    <text x="90" y="20" text-anchor="middle" font-size="11" fill="#664d03">Path interference</text>
    <text x="90" y="36" text-anchor="middle" font-size="10" fill="#997404">Paths don't cross (4.18u apart)</text>
  </g>
  <line x1="350" y1="195" x2="350" y2="220" stroke="#666" stroke-width="2" marker-end="url(#arrowhead)"/>
  <g transform="translate(150,225)">
    <rect width="400" height="60" rx="8" fill="#d4edda" stroke="#28a745" stroke-width="2"/>
    <text x="200" y="25" text-anchor="middle" font-size="13" font-weight="bold" fill="#155724">Liquipedia: serial geyser access</text>
    <text x="200" y="45" text-anchor="middle" font-size="11" fill="#1e7e34">"Only one worker can be inside at any one time"</text>
  </g>
</svg>

**Deposit lock at the town hall.** When two gas workers from different geysers arrive at the same tick, one should wait. Added a per-base lock with gas worker shuffle. Result: fires 3–5 times then the cycles desynchronise and the lock never fires again. Net improvement: 12 vespene out of 68 needed.

**Gas-mineral collision handling.** Extended the mineral collision system to include walking gas workers. Created a 20-vespene reduction for 6w, but also an 8-vespene reduction for 3w — same total workers at the base, so the collision count was proportional. Net differential: still 12. And it broke the mineral calibration as collateral damage.

**Entry stagger and deposit cascades.** Added per-worker harvest offsets at geyser entry to break the lockstep cycle. The stagger created beautiful delivery spreading (fewer triple-deliveries, more singles), but it added equal cycle overhead to both 3w and 6w. When I compensated by reducing the approach docking constant, the 6w improvement vanished. Zero net differential.

**Parameter sweeps.** Ran 2D sweeps across docking duration × collision speed factor. The best compromise: 3w=0.0% error, 6w=+4.7%. No combination achieved both under 1%.

Each mechanism topped out at the same 12-vespene ceiling. I was looking for a physics-based explanation that only affected dual-geyser, and nothing in my model had that property.

## The page

Then I searched the internet. The [Liquipedia Resources page](https://liquipedia.net/starcraft2/Resources) has a single sentence that makes everything fall into place:

> "Workers gathering gas have to spend 1.415 seconds in a Vespene Geyser before returning at full speed."

And from the StarCraft Wiki:

> "Only one worker can be inside the building collecting at any one time, but multiple workers can be queued up to mine from it."

The emulator allowed all 3 workers inside the geyser simultaneously. `GasSlot.MAX_WORKERS = 3`, checked by `canEnter` returning true whenever `insideWorkers.size() < 3`. All three entered at the same loop, harvested in parallel, exited together, walked in lockstep, deposited in a burst of +12 vespene every 6 ticks. SC2 never produces a +12 delivery. Its maximum is +8 (one delivery from each geyser in the same tick).

The correct model: serial access. One worker enters, harvests for 1.415 seconds (31.7 game loops), exits with 4 gas. The next worker enters. With three workers rotating through a single slot, the geyser is occupied almost continuously — but the workers stagger naturally because they can only enter one at a time.

## Why it matters for dual-geyser

Liquipedia also gives the definitive rates:

| Workers | Gas/min | Per-worker | Utilisation |
|---------|---------|-----------|-------------|
| 1 | 61 | 61.0 | 36% |
| 2 | 123 | 61.5 | 73% |
| 3 | 163 | 54.3 | 96% |
| 2 geysers × 3 | 325 | 54.2 | 96% each |

The dual-geyser rate (325) is exactly 2× the single-geyser rate (163). There is no inherent throughput loss from having two geysers at the same base. The SC2 baseline's 1012 vespene (vs an expected 1033 at the Liquipedia rate) is 2% below the community standard — map-specific variance from geyser distance and worker assignment timing, not a physics effect.

The emulator's 6.7% surplus wasn't a contention problem. It was a queueing-theory error: parallel access produces a fundamentally different cycle pattern from serial access, and no amount of post-hoc contention modelling can bridge the gap.

## The lesson

We spent over a week calibrating the gas cycle against dual-run SC2 baselines. The 3w test matched exactly. The 6w test didn't. We tried collision physics, deposit serialisation, processing-order shuffles, entry locks, and 2D parameter sweeps. Nothing worked beyond 12 vespene of improvement.

The fix was a single constant: `MAX_INSIDE = 1`.

The Liquipedia Resources page has 400 words of text and contains the complete reference specification for SC2 worker economy: mineral occupy time (1.99 seconds), mineral pause (0.3571 seconds), gas inside time (1.415 seconds), serial access, MULE rates (3.45× near, 3.94× far, 225 minerals lifetime), saturation curves, and optimal worker counts. Every number we needed.

There are two kinds of calibration. One is scientific: instrument the system, record observations, fit parameters. We did that — SC2 API profiling, dual-run baselines, per-worker delivery tracing, cadence analysis across 118 oracle replays. The other is practical: check whether someone has already measured what you need. The SC2 community has been measuring worker economy for 15 years. The answers are on a wiki page that Google finds in three seconds.

We did both, in the wrong order.

The serial access model is now implemented (`GasSlot.MAX_INSIDE=1`, per-loop gas worker processing). Calibration against the Liquipedia target rates is the next step — not against individual SC2 recordings that carry map-specific variance, but against the community-verified standard that 163 gas per minute is what 3 workers on a close geyser produce.

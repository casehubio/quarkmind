---
layout: post
title: "Measuring What the Emulator Gets Wrong"
date: 2026-10-07
entry_type: note
subtype: diary
projects: [casehubio/quarkmind]
tags: [emulated-game, accuracy, phase-2.5, replay-validation]
series: issue-379-emulatedgame-accuracy-baseline
---

# Measuring What the Emulator Gets Wrong

QuarkMind's `EmulatedGame` has been running divergence checks against replay data since the early layers — but until now, those checks only measured aggregate deltas. "You're off by 25 units at minute five." Not particularly useful when you don't know whether those 25 units are missing Marines, phantom Probes, or a race that the emulator doesn't model at all.

The TrackerEventFeatureExtractor work from last session gave us 100% ground truth for restored replays. That made it possible — and overdue — to measure EmulatedGame's accuracy at per-type granularity for the first time.

## What the numbers say

The headline: **36.4% unit accuracy at the five-minute mark**, measured across 118 oracle replays (236 player runs, all matchups).

That sounds alarming until you look at the breakdown. The dominant factor isn't physics fidelity — it's race coverage. EmulatedGame seeds every player with a `ProtossRaceModel`. For a PvP game, that's fine: Probes, Zealots, Stalkers all appear with reasonable counts. For a TvZ game, both players get Protoss seeds, and every Terran and Zerg unit shows up as 0% accuracy. That single design choice accounts for roughly 60% of the total unit gap.

The per-type data makes the shape of the problem clear:

| Unit | Ground Truth | Emulated | Accuracy |
|------|-------------|----------|----------|
| Probe | 1,421 | 2,318 | 100% (overcounted) |
| Stalker | 51 | 35 | 68.6% |
| Zergling | 531 | 0 | 0% |
| Marine | 372 | 0 | 0% |
| SCV | 1,850 | 66 | 3.6% |

Probes are overcounted because the harness injects ground-truth buildings but doesn't remove EmulatedGame's initial seeded workers. Zerg and Terran units are zeroed out entirely — no race model, no production.

## Upgrades: zero

The second surprise: **0% upgrade accuracy across all checkpoints**. EmulatedGame completes no upgrades at all. Either `ReplayCommandExtractor` isn't extracting `ResearchIntent`s from replay commands, or the intents are being rejected (building tag mismatch, missing prerequisite). Either way, the emulated game runs an entire match without Stimpack, Blink, or Metabolic Boost.

## Buildings: 100% (but misleading)

Buildings show 100% accuracy, which sounds perfect until you remember that `ReplayValidationHarness` syncs buildings directly from ground truth. The emulated count is *higher* than ground truth (2,464 vs 2,255 at five minutes) because EmulatedGame's initial buildings add to the harness-injected ones. The accuracy formula caps at 100% when emulated exceeds ground truth, masking the over-count.

## Economy: the flat mining gap

Adding an `EconomyTracker` to `EmulatedGame` — accumulating spending by category as intents execute and deriving collection rates from per-interval mineral deltas — made it possible to compare against the ground truth's `PlayerStats` for the first time. The results confirm what the existing mineral divergence already suggested: EmulatedGame's flat mining rate (`SC2Data.mineralIncomePerTick`) diverges from SC2's saturation-based model by ~98% on collection rate. Technology spending reads zero because `SC2Data` lacks upgrade costs entirely.

## What this tells us

The accuracy baseline reframes the emulator improvement roadmap. The three biggest gaps, in order of impact:

1. **Race model coverage** — adding Terran and Zerg race models would lift unit accuracy from 36% to something much closer to the Protoss-only numbers (where accuracy is 55-70% per type). This is a known limitation, already tracked.

2. **Upgrade pipeline** — diagnosing why ResearchIntents fail would unlock upgrade accuracy from 0% to whatever the command extraction covers. This might be a single-bug fix.

3. **Mining model** — replacing the flat rate with saturation curves is the only path to meaningful economy accuracy. Significant physics work, but well-understood.

None of these block Phase 2.5 — the reconstitution accuracy gate is about *training data* quality, not emulator fidelity. But they're now quantified with per-type granularity instead of hand-waved as "known limitations," and the regression thresholds ensure any future emulator work that accidentally degrades accuracy will break the build.

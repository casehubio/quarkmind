---
layout: post
title: "Gas money: teaching EmulatedGame to mine vespene"
date: 2026-10-10
entry_type: note
subtype: diary
projects: [casehubio/quarkmind]
tags: [emulated-game, economy, sc2-physics, tdd]
---

EmulatedGame has had mineral income since its early days — workers assigned to bases, tiered saturation rates, the whole thing. But vespene income was a gap. Gas buildings could be constructed through BuildIntents, and they'd complete on schedule, but they produced nothing. The vespene field only ever changed when something deducted from it or when the replay harness injected a value directly.

This matters because the economy playbooks — the YAML-driven build order validation infrastructure we built for Protoss, Terran, and Zerg — can't include gas building steps without a working income model behind them. Building an Assimilator that produces no gas makes the downstream playbook assertions meaningless.

## The design

I wanted to mirror what already works for minerals rather than invent something new. The mineral model uses `MINERAL_TIER_RATES_PER_TICK` — three tiers of diminishing returns per mineral patch, 8 patches per base. Gas is structurally simpler: one geyser, three worker slots, and a small diminishing return on the third worker. So we added `GAS_TIER_RATES_PER_TICK` with rates of 38/38/20 gas per minute (community-sourced, not yet replay-calibrated — that's a follow-up per the calibration protocol).

The interesting question was worker assignment. Real SC2 has explicit worker assignment — you right-click three probes onto the Assimilator and they stop mining minerals. We don't track individual worker assignments. The solution: implicit budgeting. Count completed gas buildings, multiply by three, cap at total workers, and deduct those from the mineral worker counts before computing mineral income.

The deduction itself has a subtlety. `countWorkersPerBase` distributes workers across bases by proximity, returning a per-base count array. Gas workers need to come out of that array, and the question is *which base loses workers*. We deduct from the largest base first — at a saturated base, the third worker per mineral patch earns only ~5 minerals/minute, so those are the workers you'd reassign to gas in a real game.

## The catch

Code review caught a regression that would have been painful to debug later. When we rewrote `tick()` to insert the gas income logic, `ide_replace_member` silently dropped the `economyTracker.tickUpdate()` call that lived at the bottom of the method. The tool replaced the entire method body with what we provided, and what we provided didn't include those last five lines. No warning, no diff — just a successful replacement that happened to delete a tracking call.

Claude spotted it during the post-implementation review. The `economyTracker` feeds data to the workbench visualiser, and without its tick call, economy graphs would have gone flat with no obvious error.

## What this opens

The foundation is in place for gas building steps in economy playbooks. A Protoss playbook can now `build: ASSIMILATOR` at tick 80, and by tick 120 the `assert: {vespene: {min: 50}}` step will validate that income is accumulating. That validation path — build the gas building, verify income flows, then build gas-costing units — is the next piece needed for realistic economy playbook coverage.

The gas rates themselves need replay calibration. The mineral model went through that process with `SC2TrainTimeCalibrationTest` — gas needs the same treatment. For now, community values get us close enough to validate the plumbing.

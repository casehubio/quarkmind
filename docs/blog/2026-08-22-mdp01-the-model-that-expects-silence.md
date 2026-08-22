---
title: "The model that expects silence"
date: 2026-08-22
author: Mark Proctor
entry_type: note
subtype: diary
tags: [onnx, feature-engineering, classifier, design]
series: quarkmind-diary
publish: false
---

Three ONNX models arrived from neocortex this week — one per opponent race, trained on 71 SC2EGSet tournaments. The numbers looked good: 70% vs Terran, 78% vs Zerg, 81% vs Protoss. The feature extractor that would feed them in quarkmind was a 51-element unit-count histogram. The models expected 2,690 temporal features plus 6 map characteristics.

That gap is the story of the design session.

## The feature contract

The training pipeline extracts 134 features per player: 53 building types, 53 unit types, 13 economy stats, and 15 upgrade flags — all divided by 1000 for normalization. Player and opponent features are concatenated with a visibility flag, producing 269 features per time window. Ten windows of 30 seconds each, zero-padded when the game hasn't run long enough to fill them. The zero-padding is how the model knows to ignore empty windows: `padding_mask = (tensor.abs().sum(dim=-1) == 0)`.

The design review caught what the spec missed. Z-score normalization — the standard `(x - mean) / std` applied to match training — transforms those zeros into `-mean/std`. Non-zero. The padding mask stops working. The model processes empty windows as real data. No error, no exception, just degraded accuracy on short games where most windows are padding.

The fix is to normalize only populated windows. Check whether a window sums to zero before normalizing, skip it if so. Simple once you see it, invisible until you do.

A related failure in the same loop: availability flags (`has_player`, `has_opponent`) computed after normalization will always read as true, because normalization converts zero blocks to non-zero values. Move the check before the loop. Both bugs are consequences of treating normalization as a uniform pass rather than a windowed operation.

## The tick rate that wasn't

The spec assumed 500ms per game tick. SC2Data says otherwise: `LOOPS_PER_TICK = 22` at 22.4 loops per second gives roughly 982ms per tick. Every window boundary calculation was off by a factor of two. At minute 3, the accumulator expected 360 ticks to have fired; only 180 had. Half the windows that should have been populated were zero-padded instead.

The training pipeline processes per-second PlayerStats events, so the ~1s tick rate actually matches more closely than the spec claimed. The constant needed correcting, not the architecture.

## Three models, not one

The current cascade injects a single `@Inference("strategy-classifier")` ONNX model. The neocortex models are per-race with different class counts: 5 labels for vs-Terran, 6 for vs-Zerg, 7 for vs-Protoss. The labels are coarse — `RUSH`, `MECH_PUSH`, `AIR_SUPERIORITY` — where the Drools tier tracks fine-grained archetypes like `TERRAN_MARINE_RUSH` or `ZERG_ROACH_RUSH`.

The mapping turns out to be simpler than it looked. Most consolidated labels map directly to existing `StrategyArchetype` values. Only four genuinely new enum values were needed — the spec claimed ten before we checked what already existed.

The cascade routing is clean: enemy race selects the model, the model's label maps to an archetype, confidence merges via `Math::max` into the same cumulative map the Drools tier uses. No fragmentation.

## What's next

The plan has seven tasks across three batches. Feature extraction infrastructure first, then cascade wiring, then validation against 59 replays in three modes — Drools-only, ONNX-only, and the full cascade. The comparison will show whether the ONNX tier adds real value over the hand-authored Drools rules, or just adds latency.

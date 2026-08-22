---
title: "Seventy-one places to change"
date: 2026-08-22
author: Mark Proctor
entry_type: note
subtype: diary
tags: [gamestate, economy, refactoring, ssr, onnx]
series: quarkmind-diary
publish: false
---

Last session designed the ONNX cascade wiring end-to-end. This session started building it — beginning with the foundation: extending `GameState` to carry economy stats and upgrade tracking.

## The record that touches everything

`GameState` is a Java record with 13 fields. Every game tick, every test, every mock, every replay parser constructs one. Adding four new fields — `playerEconomy`, `enemyEconomy`, `playerUpgrades`, `enemyUpgrades` — means updating every constructor call in the codebase. Seventy-one of them, across 38 files.

The obvious approach: find-and-replace per file, appending the default arguments. With IntelliJ's structural search/replace, I didn't need to. One AST-aware pattern — `new GameState($a$, $b$, ..., $m$)` with 13 positional variables — matched all 71 sites and appended the four defaults in a single operation. No regex, no text parsing, no worrying about multi-line constructor calls.

What I didn't expect: SSR strips fully-qualified class names. One file referenced `new io.quarkmind.domain.GameState(...)` without an import — the SSR output `new GameState(...)`, dropping the package prefix silently. The fix is trivial (add the import), but the failure mode is worth knowing. SSR operates on the AST and uses the short name from the pattern template, regardless of how the source originally spelled the reference.

## PlayerEconomyStats

The new `PlayerEconomyStats` record carries 13 economy fields from the SC2 tracker events — minerals, vespene, collection rates, food, worker count, and the six army/economy/technology spending breakdowns. A `toFeatureVector()` method normalises them to the `[0, 1]` range the ONNX models expect.

The interesting part was wiring both players' stats. The existing `applyPlayerStats` method in both replay parsers (IEM10 JSON and Scelight binary) only tracked the watched player — skipping the opponent's stats entirely. For the ONNX feature vector, we need both. The fix was straightforward: remove the early-return guard and route stats to `playerEconomy` or `enemyEconomy` based on the player ID.

Upgrades follow the same dual-player pattern. Both parsers now process upgrade events (UpgradeEvent in JSON, `ID_UPGRADE` in Scelight) and accumulate upgrade names per player.

## What this sets up

The economy stats and upgrades are the raw material for the ONNX feature extractor — 13 economy fields per player, plus 15 tracked upgrades, feeding into the 134-feature-per-player vector that the temporal window accumulator will aggregate. That's the next piece: windowing 30-second snapshots across the game timeline to build the 269×10 temporal tensor the per-race models were trained on.

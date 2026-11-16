---
id: PP-20261116-8ee9df
title: "Any mineral worker redistribution must call rebalanceGasWorkers() afterwards"
type: rule
scope: repo
applies_to: "WorkerSimulation.java — any method that moves mineral workers between bases"
severity: important
refs:
  - quarkmind-sc2/src/main/java/io/quarkmind/sc2/emulated/WorkerSimulation.java
violation_hint: "syncMineralWorkersFromGt, rebalanceMineralWorkersAcrossBases, transferMineralWorkers, or any new redistribution method ends without a rebalanceGasWorkers() call — gas slots at the destination base may stay undersaturated"
created: 2026-11-16
---

Any method in `WorkerSimulation` that redistributes mineral workers between bases must call `rebalanceGasWorkers()` as its final step. Gas slots are assigned at building-completion time (`onGasBuildingCompleted`), which only sees workers already at that base. When workers arrive later via mineral redistribution, undersaturated gas slots will not self-correct unless `rebalanceGasWorkers()` is explicitly triggered. The pattern is: redistribute mineral workers → check gas slots for vacancies → fill from the newly-arrived mineral pool, subject to `MIN_MINERAL_WORKERS_BEFORE_GAS`.

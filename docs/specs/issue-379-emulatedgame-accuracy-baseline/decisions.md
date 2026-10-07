# Decisions — #379 EmulatedGame Accuracy Baseline

## D1: Comparison approach — extend harness vs separate test

**Choice:** Extend ReplayValidationHarness snapshots with per-type unit/building/upgrade tracking
**Alternatives:**
- Separate comparison test — simpler but loses tick-by-tick per-type granularity
- Both harness extension + separate test — redundant, two views of the same data
**Rationale:** Reuses existing infrastructure, provides richer per-type data at every checkpoint for root cause triage. The harness already runs EmulatedGame against ReplaySimulatedGame — adding per-type maps to TickSnapshot is a natural extension.
**Trade-offs:** Slightly larger TickSnapshot objects in memory, but replay counts are bounded.
**Sources:** ReplayValidationHarness.java, DivergenceReport.java, DivergenceBaselineReportTest.java
**Exploration:** quick
**Status:** captured

## D2: Economy scope — measure what exists vs add tracking

**Choice:** Add income/spending tracking to EmulatedGame, derived from existing state (not new physics)
**Alternatives:**
- Mineral/vespene MAPE only — misses income rates, spending breakdowns, food tracking
- Skip economy entirely — already documented in existing baseline but at lower granularity
**Rationale:** EmulatedGame already tracks minerals spent (via intent execution), units built (supply), and income (flat rate). Deriving collection rates from delta-minerals-per-interval and spending from intent costs produces PlayerStats-compatible values without changing the mining model. Baselines then show exactly where the simplified model diverges.
**Trade-offs:** Derived stats will diverge from reality due to the flat mining model — but that's the point: measuring the gap. Fixing the model is a separate issue.
**Sources:** EmulatedGame.java (snapshot(), mineralIncomePerTick), PlayerEconomyStats, OracleAccuracyBaselineTest economy MAPE
**Exploration:** quick
**Status:** captured

## D3: Regression threshold style

**Choice:** Per-category accuracy percentages (e.g. units ≥85%, buildings ≥75%, upgrades ≥99%)
**Alternatives:**
- Absolute deltas per type — more granular but too many threshold values to maintain
- Both percentages + deltas — accuracy as gate, deltas in report for triage
**Rationale:** Matches OracleAccuracyBaselineTest style, easier to reason about ("EmulatedGame is 85% accurate on units"). Initial thresholds set from the baseline run — this issue establishes the baseline, future issues improve it.
**Trade-offs:** Per-category aggregation masks per-matchup variation. The report will still show per-matchup breakdown for triage; the regression gate aggregates.
**Sources:** DivergenceRegressionTest.java (BASELINE_5MIN, MARGIN), OracleAccuracyBaselineTest
**Exploration:** quick
**Status:** captured

## D4: Economy model fidelity

**Choice:** Derive PlayerStats from existing EmulatedGame state without changing physics
**Alternatives:**
- Model saturation-based mining — accurate but large scope, touches core physics, needs calibration
- Stub with approximate values — minimal code but useless for baselining
**Rationale:** Calculate collection rates from mineral/vespene deltas per interval, spending from cumulative intent costs, food from unit supply values. Quick to implement, and the divergence from reality IS the measurement — it shows where the simplified model needs work.
**Trade-offs:** Economy accuracy will be lower than if we fixed the mining model. But that's expected — this issue measures the gap, future issues close it.
**Depends on:** D2 (economy scope)
**Sources:** EmulatedGame.java (snapshot), SC2Data.mineralIncomePerTick, PlayerEconomyStats
**Exploration:** quick
**Status:** captured

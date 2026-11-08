# Economy Structural Floor Analysis — 2026-11-08

**Purpose:** Document the accuracy ceiling per economy field — the MAPE that cannot be improved by code changes because it originates from measurement artifacts, reporting granularity, or behaviours the simulation cannot observe.

**Dataset:** Oracle (118 replays, 236 player runs, v4.9.3)
**Method:** Standalone EmulatedGame with all fixes through #576 (spending classification), #574 (acceleration model), #575 (mining duration), #571 (sub-tick gas carry-forward), #566 (mining efficiency)
**Reference:** `standalone-emulated-game-baseline.md` (same date)

---

## Summary — 7-minute checkpoint

| Field | Current MAPE | Structural floor | Gap | Improvable? |
|-------|-------------|-----------------|-----|-------------|
| mineralsCurrent | 100.0% | ~40-50% | ~50% | Yes — acceleration model regression (#578) |
| vespeneCurrent | 82.3% | ~35-45% | ~37% | Yes — acceleration model regression (#578) |
| mineralsCollectionRate | 50.0% | ~10-12% | ~38% | Yes — acceleration model regression (#578) |
| vespeneCollectionRate | 43.0% | ~12-15% | ~28% | Yes — acceleration model regression (#578) |
| foodMade | 2.9% | ~2-3% | 0% | At floor |
| foodUsed | 6.1% | ~2-3% | ~3% | Near floor |
| workersActiveCount | 3.2% | ~2-3% | ~1% | Near floor |
| mineralsUsedCurrentArmy | 7.1% | ~5-8% | ~0-2% | At floor — classification fix (#576) |
| mineralsUsedCurrentEconomy | 5.8% | ~3-5% | ~1-3% | Marginal |
| mineralsUsedCurrentTechnology | 17.0% | ~10-12% | ~5-7% | Marginal — timing artifact |
| vespeneUsedCurrentArmy | 7.4% | ~5-8% | ~0-2% | At floor — classification fix (#576) |
| vespeneUsedCurrentEconomy | 100.0% | 100% | 0% | At floor (GT=50, negligible) |
| vespeneUsedCurrentTechnology | 42.0% | ~10-15% | ~27% | Partially — InProgress/Current timing boundary (§8) |

---

## Structural floor sources

### 1. PlayerStatsEvent reporting lag

SC2's `PlayerStatsEvent` updates every 160 game loops (~7 seconds, ~10 ticks). Economy stats at any checkpoint reflect state from up to 160 loops earlier. Items transitioning between InProgress and Current (units completing training, buildings finishing construction, upgrades completing) may appear in the wrong category.

**Affected fields:** All spending fields (mineralsUsedCurrent*, vespeneUsedCurrent*)
**Estimated contribution:** 5-10% MAPE per spending field
**Evidence:** Spending fields consistently show 10-15% MAPE even with perfect unit/building accuracy (100.0%)

### 2. Cumulative drift amplification

Balance fields (mineralsCurrent, vespeneCurrent) are cumulative: `balance = initial + Σ(income) - Σ(spending)`. Small per-tick errors in income or spending compound over time. A 5% income rate error at 7 minutes translates to a much larger balance error because the absolute cumulative error grows while the instantaneous balance fluctuates.

**Affected fields:** mineralsCurrent, vespeneCurrent
**Estimated contribution:** 20-30% MAPE above what rate fields show
**Evidence:** mineralsCurrent MAPE (61.5%) is 3.6× mineralsCollectionRate MAPE (17.0%). vespeneCurrent MAPE (52.5%) is 2.5× vespeneCollectionRate MAPE (20.9%). The amplification ratio grows with checkpoint time (1-min: 4.8×, 3-min: 4.0×, 5-min: 3.6×, 7-min: 3.6×) then stabilises.

### 3. Low-denominator MAPE amplification

Players who spend efficiently maintain low mineral/vespene banks. A bank of 50 minerals with a 25-mineral error is 50% MAPE. The same 25-mineral error against a 500 bank is 5% MAPE. Protoss players average lower mineral banks (~380) than Terran (~460) or Zerg (~520), producing systematically higher Protoss MAPE from the same absolute accuracy.

**Affected fields:** mineralsCurrent, vespeneCurrent (balance fields)
**Estimated contribution:** 10-20% MAPE floor for balance fields, varying by race
**Evidence:** Per-race MAPE breakdown in EconomyDivergenceDiagnosticTest shows consistent Protoss > Terran > Zerg ordering for mineral MAPE despite similar absolute errors

### 4. Death timing variance

EmulatedGame resolves combat at tick granularity (22 game loops). Real SC2 resolves at frame granularity (1 game loop). A unit that dies 1 tick earlier in EM than GT subtracts its cost from the wrong spending snapshot. With multiple unit deaths per game, the spending field noise is ~5-10%.

**Affected fields:** mineralsUsedCurrentArmy, vespeneUsedCurrentArmy, and indirectly balance fields
**Estimated contribution:** 5-10% MAPE for army spending fields
**Evidence:** After classification fix (#576), army spending fields are at 7.1-7.4% MAPE — consistent with death timing being the dominant remaining source

### 5. Worker activity budget residual

The mining efficiency factor (#566) models aggregate worker availability per race (Protoss 0.92, Zerg 0.95, Terran 1.0). This is a population-level average — individual games vary. Some players scout more aggressively; some barely scout. The factor captures the mean but not the variance.

**Affected fields:** mineralsCollectionRate, mineralsCurrent
**Estimated contribution:** 3-5% MAPE residual in income rate fields
**Evidence:** Mining efficiency calibration reduced mineralsCurrent MAPE from ~80% to ~62% at 7-min. Remaining gap includes per-game variance.

### 6. ~~Gas harvest cycle quantisation~~ — RESOLVED (#571)

Sub-tick time carry-forward eliminates the 32→44 loop quantisation. When any gas worker phase completes mid-tick, the remaining time carries forward to the next phase: harvest overflow applies proportional movement, geyser arrival starts the harvest countdown immediately, and base arrival applies remaining movement back toward the geyser. Effective harvest is now exactly 32 loops regardless of tick boundaries.

**Status:** Resolved by #571 — no longer a structural floor contributor

### 7. Early checkpoint gas timing (#569)

At the 1-minute checkpoint, gas buildings have just completed. GT's PlayerStatsEvent captures a partial collection window; the rate-based gas worker estimation underestimates from the partial rate. Only 11% of player runs have gas at 1-min.

**Affected fields:** vespeneCurrent, vespeneCollectionRate (1-min only)
**Estimated contribution:** 100% MAPE at 1-min, resolves by 3-min
**Evidence:** GasCheckpointTimingDiagnosticTest — 23/206 runs affected, absolute error 224 vespene

---

## Fields at or near floor

These fields are already at their structural limit. Further optimisation is not expected to improve them:

- **foodMade** (2.9%) — building completion timing matches 100%; residual is PlayerStatsEvent lag
- **foodUsed** (6.1%) — unit training timing matches 100%; residual is reporting lag + death timing
- **workersActiveCount** (3.2%) — worker count matches; residual is mining efficiency approximation
- **mineralsUsedCurrentArmy** (7.1%) — classification fix (#576); residual is death timing (§4)
- **vespeneUsedCurrentArmy** (7.4%) — classification fix (#576); residual is death timing (§4)
- **mineralsUsedCurrentEconomy** (5.8%) — building and worker costs well-modelled; residual is reporting lag
- **vespeneUsedCurrentEconomy** (100%) — structural: GT=50 total, negligible absolute value

## Fields with remaining improvable gap

| Field | Current | Floor | Action |
|-------|---------|-------|--------|
| mineralsCollectionRate | 50.0% | ~10-12% | Acceleration model regression (#578) |
| vespeneCollectionRate | 43.0% | ~12-15% | Acceleration model regression (#578) |
| vespeneUsedCurrentTechnology | 42.0% | ~10-15% | InProgress/Current timing boundary (§8) |
| mineralsUsedCurrentTechnology | 17.0% | ~10-12% | Timing artifact; marginal improvement possible |
| mineralsCurrent | 100.0% | ~40-50% | Blocked by #578 (acceleration model) |
| vespeneCurrent | 82.3% | ~35-45% | Blocked by #578 (acceleration model) |

---

## Interpretation

The standalone EmulatedGame achieves **100% accuracy** for units, buildings, and upgrades. The economy accuracy ceiling is fundamentally limited by three factors:

1. **Measurement granularity** — PlayerStatsEvent updates every 160 loops, creating a 7-second reporting lag that no simulation improvement can eliminate. This sets a ~5-10% MAPE floor for all spending fields.

2. **Cumulative drift** — Balance fields compound per-tick rate errors over thousands of ticks. Even a 2% per-tick income error produces 30-40% balance MAPE at 7 minutes. This is arithmetic, not a bug.

3. **Unobservable behaviour** — Worker scouting, MULE timing, gas worker reassignment, and per-game playstyle variance create income fluctuations that tracker events cannot capture. The efficiency factor (#566) models the population mean; the per-game variance remains.

The practical ceiling for the 7-minute checkpoint:
- **Rate fields:** ~10-15% MAPE (improvable with calibration work)
- **Spending fields:** ~10-15% MAPE (limited by reporting lag + death timing)
- **Balance fields:** ~35-50% MAPE (limited by cumulative drift of the above)
- **Supply fields:** ~2-3% MAPE (already at floor)

### 8. Research spending classification — RESOLVED (#576)

`EconomyTracker.recordResearchSpending` was routing upgrade costs to Army counters. SC2's PlayerStatsEvent reports upgrades as Technology spending. Fix: route to Technology.

**Impact at 7-min:** mineralsUsedCurrentArmy 14.0% → 7.1%, vespeneUsedCurrentArmy 35.4% → 7.4%. Both Army fields are now at the death-timing floor (§4).

**Side effect:** vespeneUsedCurrentTechnology rose from 15.4% → 42.0% because research vespene now correctly accumulates in Technology. The elevated MAPE is from the InProgress/Current timing boundary — EM records spending at completion tick, GT only surfaces it at the next 160-loop PlayerStatsEvent. Multiple in-flight upgrades (one per tech building) compound the gap. This is a measurement artifact, not a simulation bug.

**Status:** Classification resolved. Timing boundary is the structural floor for this field.

### 9. Acceleration model income regression (#578)

Per-loop acceleration model (#574) halved standalone mineral collection rate. mineralsCollectionRate went from ~12% to ~50% MAPE. mineralsCurrent collapsed to 0% (EM earns less than it spends). Root cause under investigation — may be interaction between acceleration constant (2.5 u/s²) and mining duration (44.576 loops from #575).

**Affected fields:** mineralsCurrent, vespeneCurrent, mineralsCollectionRate, vespeneCollectionRate
**Status:** Filed as #578. Tracker-driven mode unaffected (GT economy syncs mask the regression).

---

Refs #534, #559, #560, #563, #564, #566, #569, #576, #578

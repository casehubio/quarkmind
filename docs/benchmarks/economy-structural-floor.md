# Economy Structural Floor Analysis — 2026-11-08

**Purpose:** Document the accuracy ceiling per economy field — the MAPE that cannot be improved by code changes because it originates from measurement artifacts, reporting granularity, or behaviours the simulation cannot observe.

**Dataset:** Oracle (118 replays, 236 player runs, v4.9.3)
**Method:** Standalone EmulatedGame with all fixes through #566 (mining efficiency), #564 (gas worker sync), #563 (gas simulation), #560 (building pause), #559 (spending reclassification)
**Reference:** `standalone-emulated-game-baseline.md` (same date)

---

## Summary — 7-minute checkpoint

| Field | Current MAPE | Structural floor | Gap | Improvable? |
|-------|-------------|-----------------|-----|-------------|
| mineralsCurrent | 61.5% | ~40-50% | ~10-20% | Partially — cumulative drift compounds |
| vespeneCurrent | 52.1% | ~35-45% | ~7-10% | Partially — gas rate residual |
| mineralsCollectionRate | 17.0% | ~10-12% | ~5-7% | Yes — per-map patch distances |
| vespeneCollectionRate | 19.6% | ~12-15% | ~5-7% | Partially — sub-tick carry-forward applied (#571) |
| foodMade | 3.1% | ~2-3% | ~0-1% | At floor |
| foodUsed | 3.7% | ~2-3% | ~0-1% | At floor |
| workersActiveCount | 2.5% | ~2-3% | 0% | At floor |
| mineralsUsedCurrentArmy | 14.0% | ~10-12% | ~2-4% | Marginal |
| mineralsUsedCurrentEconomy | 5.8% | ~3-5% | ~1-3% | Marginal |
| mineralsUsedCurrentTechnology | 15.7% | ~10-12% | ~4-6% | Marginal |
| vespeneUsedCurrentArmy | 35.4% | ~15-20% | ~15-20% | Yes — army gas spending model |
| vespeneUsedCurrentEconomy | 100.0% | 100% | 0% | At floor (GT=50, negligible) |
| vespeneUsedCurrentTechnology | 15.4% | ~10-12% | ~3-5% | Marginal |

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
**Evidence:** vespeneUsedCurrentArmy (35.4%) is the highest spending MAPE — gas-cost units (Stalkers, Immortals, Medivacs) die in combat more often than mineral-only units

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

- **foodMade** (3.1%) — building completion timing matches 100%; residual is PlayerStatsEvent lag
- **foodUsed** (3.7%) — unit training timing matches 100%; residual is reporting lag + death timing
- **workersActiveCount** (2.5%) — worker count matches; residual is mining efficiency approximation
- **mineralsUsedCurrentEconomy** (5.8%) — building and worker costs well-modelled; residual is reporting lag
- **vespeneUsedCurrentEconomy** (100%) — structural: GT=50 total, negligible absolute value

## Fields with remaining improvable gap

| Field | Current | Floor | Action |
|-------|---------|-------|--------|
| vespeneUsedCurrentArmy | 35.4% | ~15-20% | Death timing + spending model alignment |
| vespeneCollectionRate | 19.6% | ~12-15% | Sub-tick carry-forward applied (#571); remaining gap ~5-7% |
| mineralsCollectionRate | 17.0% | ~10-12% | Per-map patch distances (#567) |
| mineralsCurrent | 61.5% | ~40-50% | Compounds from rate improvements |
| vespeneCurrent | 52.5% | ~35-45% | Compounds from rate improvements |

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

Refs #534, #559, #560, #563, #564, #566, #569

## D1: Threshold strategy — baseline + margin vs aspirational

**Choice:** Baseline + margin (current measured values + 10% margin)
**Alternatives:**
- Aspirational ≥99% — test fails immediately, serves as target. Noisy CI, masks real regressions.
- Tiered per category — keeps current ad-hoc thresholds. No improvement.
**Rationale:** A regression suite catches regressions from the current state. Aspirational thresholds produce permanent red tests that mask real failures. Setting thresholds at baseline + 10% margin means any code change that degrades accuracy is caught immediately.
**Trade-offs:** Thresholds will need updating as the emulator improves — each improvement should raise the baseline
**Sources:** DivergenceRegressionTest.java, docs/benchmarks/emulated-game-accuracy-baseline.md, docs/benchmarks/oracle-accuracy-baseline.md
**Exploration:** quick
**Status:** captured

## D2: Cross-patch scope — sample per dataset

**Choice:** Run against all tournament datasets with tracker events, capped at ~20 replays per dataset
**Alternatives:**
- All replays all datasets — thorough but 30+ minute runtime for a regression test
- Oracle only + economy — minimal change, misses cross-patch coverage entirely
**Rationale:** 20 replays per dataset is enough to detect systematic accuracy regressions across patch versions. Tournament datasets span baseBuild 4.1→5.0. The oracle 118 remain the primary baseline; tournament samples provide cross-patch coverage without excessive runtime.
**Trade-offs:** Sample size may miss rare per-replay failures — acceptable for regression detection, not for calibration
**Depends on:** D1 (thresholds applied per-dataset)
**Sources:** CrossPatchExtractionTest.java, RestorationCoverageAuditTest.java (coverage data)
**Exploration:** quick
**Status:** captured

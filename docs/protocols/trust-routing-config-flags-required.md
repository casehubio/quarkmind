---
id: PP-20260610-bd14ab
title: "L6 trust routing requires three casehub.ledger.trust-score.* flags in application.properties"
type: rule
scope: repo
applies_to: "application.properties — all non-%sc2 profiles when L6 trust routing is active"
severity: critical
refs:
  - docs/superpowers/specs/2026-06-10-layer6-trust-weighted-strategy-routing-design.md
violation_hint: "Flags absent → IncrementalTrustUpdateObserver is a no-op → ActorTrustScoreRepository never written → MaterializedTrustScoreSource returns empty → all strategies in permanent BOOTSTRAP; no error or warning"
created: 2026-06-10
---

Three configuration flags are required for `IncrementalTrustUpdateObserver` to materialise trust scores after each game. Without all three, `MaterializedTrustScoreSource.currentScore()` always returns `OptionalDouble.empty()`, all strategies are permanently in BOOTSTRAP phase, and trust routing produces no strategic learning regardless of how many games are played.

```properties
casehub.ledger.trust-score.enabled=true
casehub.ledger.trust-score.incremental.enabled=true
casehub.ledger.trust-score.materialization.enabled=true
```

Do not set these on `%sc2` profile — the `TrustScoreJob` runs nightly against a real database in that profile. All other profiles (mock, emulated, replay, test) require all three flags.

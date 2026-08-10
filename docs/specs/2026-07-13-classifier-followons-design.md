# Classifier Follow-ons Design — #236 + #238

**Date:** 2026-07-13
**Issues:** #236 (replay classification accuracy ≥ 70%), #238 (Commentator/Advisory prompt updates for PATTERN_ASSESSMENT)
**Branch:** issue-236-classifier-followons
**Prerequisite:** #183 (enemy strategy classifier — landed as 746fb27)

---

## #236 — Replay Classification Accuracy ≥ 70%

### Goal

Validate that the Drools CEP pattern classifier (`PatternClassificationRuleUnit` + `PatternClassifier`) correctly identifies enemy archetypes when fed scouting events derived from replay data.

### Test: `PatternClassificationCalibrationTest`

`@QuarkusTest @Tag("benchmark")` — requires Drools rule unit injection. Runs under `mvn test -Pbenchmark`, excluded from default suite.

**Pipeline per replay game:**

1. Load replay (AI Arena binary + IEM10 JSON — same datasets as `ScoutingCalibrationTest`)
2. Create a `ScoutingSessionManager` per game
3. Tick through frames, calling `processFrame(enemies, gameTimeMs, ourNexus, estimatedEnemyBase)` each tick to generate scouting events
4. At 3-min mark:
   - Build `PatternClassificationRuleUnit` via `sessionManager.buildPatternRuleUnit(gameTimeMin)`
   - Fire rules via injected `RuleUnit<PatternClassificationRuleUnit>`
   - Compute confidences via `PatternClassifier.computeAllConfidences()` → cumulative merge → `topAssessment()`
5. Derive ground truth from raw unit counts using independent threshold heuristic (not the Drools rules)
6. Compare Drools classification against ground truth

**Ground truth derivation** — independent from Drools rules, based on direct unit composition at 3 min:

| Condition | Ground truth |
|-----------|-------------|
| Marines ≥ 5, game time < 4 min | TERRAN_MARINE_RUSH |
| Marines ≥ 6, game time ≥ 4 min | TERRAN_BIO_TIMING |
| Siege Tanks ≥ 2, game time ≥ 5 min | TERRAN_MECH_PUSH |
| Banshee present, game time < 8 min | TERRAN_BANSHEE_HARASS |
| Zerglings ≥ 6, game time < 4 min | ZERG_ZERGLING_RUSH |
| Roaches ≥ 4, game time < 5 min | ZERG_ROACH_RUSH |
| Stalkers + Zealots ≥ 4, game time < 5 min | PROTOSS_GATEWAY_RUSH |
| No dominant unit pattern | NONE (excluded from accuracy) |

Evaluated top-down — first match wins. Macro and cannon rush excluded from ground truth (insufficient signal from unit counts alone at 3 min).

**Archetype categories for assertion:**
- **Rush**: TERRAN_MARINE_RUSH, ZERG_ZERGLING_RUSH, ZERG_ROACH_RUSH, PROTOSS_GATEWAY_RUSH
- **Air threat**: TERRAN_BANSHEE_HARASS

**Assertions:**
- `assertThat(rushAccuracy).isGreaterThanOrEqualTo(0.7)` — ≥ 70% for rush archetypes
- `assertThat(airThreatAccuracy).isGreaterThanOrEqualTo(0.7)` — ≥ 70% for air threat (if sample size > 0)
- Print per-matchup, per-archetype breakdown with sample sizes

**What this tests:** The full CEP-to-classification pipeline — `ScoutingSessionManager` event generation → Drools rule firing → `PatternClassifier` confidence aggregation → top assessment selection. Accuracy measures whether scouting-event-based classification agrees with direct unit observation.

---

## #238 — Commentator and Advisory Prompt Updates for PATTERN_ASSESSMENT

### Goal

Update LLM system prompts and user message builders so Commentator and Advisory observers can reason about `PATTERN_ASSESSMENT` intel when it appears in their input.

### Changes

**`CommentaryWorkerFactory`:**

System prompts (`buildReactiveSystemPrompt`, `buildNarrativeSystemPrompt`):
- Add section: "You may receive enemy strategy classification intel (PATTERN_ASSESSMENT) with an archetype name (e.g. ZERG_ROACH_RUSH) and confidence score (0.0–1.0)."
- Reactive: "When present, call out the classification naturally — e.g. 'It looks like a Roach Rush developing!'"
- Narrative: "When present, weave strategic implications — e.g. 'The early Roach commitment suggests an all-in.'"

User messages (`buildReactiveUserMessage`, `buildNarrativeUserMessage`):
- Extract pattern assessment from trigger map when present
- Format as: `ENEMY PATTERN: <ARCHETYPE> (confidence: <score>)`

**`AdvisoryWorkerFactory`:**

System prompt (`buildSystemPrompt`):
- Add section: "Trigger events may include enemy strategy classification (PATTERN_ASSESSMENT) with archetype and confidence. Factor the classified intent into your recommendation — a high-confidence rush classification should increase urgency."

User message (`buildUserMessage`):
- Extract pattern assessment from input when present under the role's trigger key
- Format as: `Enemy pattern classification: <ARCHETYPE> (confidence: <score>)`

### Test Updates

**`CommentaryWorkerFactoryTest`:**
- Assert reactive system prompt contains "PATTERN_ASSESSMENT"
- Assert narrative system prompt contains "PATTERN_ASSESSMENT"
- Assert user message formats pattern data when present in trigger map

**`AdvisoryWorkerFactoryTest`:**
- Assert system prompt contains "PATTERN_ASSESSMENT"
- Assert user message includes pattern classification when present in trigger

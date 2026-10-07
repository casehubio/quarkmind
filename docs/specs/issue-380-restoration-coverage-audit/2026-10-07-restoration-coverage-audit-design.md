# Restoration Coverage Audit — Design Spec

**Issue:** casehubio/quarkmind#380
**Branch:** issue-380-restoration-coverage-audit
**Date:** 2026-10-07

## Goal

Measure what percentage of each replay dataset routes through
TrackerEventFeatureExtractor (100% accuracy, ground-truth tracker events)
vs StrippedReplayFeatureExtractor (88% unit accuracy, 79% building accuracy,
CmdEvent-based reconstruction). Produce a persistent benchmark report that
informs the re-reconstitution decision in #372.

## Scope

**In scope:**
- Per-dataset coverage measurement for all `.SC2Replay` datasets on disk
- Markdown benchmark report at `docs/benchmarks/restoration-coverage.md`
- Special-category reporting for IEM10 (JSON) and SC2EGSet (not downloaded)
- Parse failure tracking with file paths and error messages

**Out of scope:**
- Running Docker restoration on unrestored replays (deferred to #372)
- Re-reconstituting training data (blocked by this, handled in #372)
- Modifying ReplayFeatureExtractor routing logic

## Architecture

### Test class: `RestorationCoverageAuditTest`

- Package: `io.quarkmind.sc2.replay` (same as existing extractor tests)
- Annotation: `@Tag("report")` — excluded from default surefire run
- Profile: runs via `mvn test -pl quarkmind-sc2 -Preport`
- Plain JUnit (no `@QuarkusTest` — pure file I/O and Scelight parsing)

### Components

**1. Dataset discovery**

Reuses the `DatasetSource` record pattern from `CrossPatchExtractionTest` /
`ReconstitutionExportTest`. Enumerates directories under
`../quarkmind-classifier/data/replay_packs/` plus AI Arena replays at
`replays/aiarena_protoss/`.

Datasets to scan (all `.SC2Replay` files):

| Dataset | Expected path | Expected count |
|---------|--------------|----------------|
| blizzard_ladder/4.9.3 | `replay_packs/blizzard_ladder/4.9.3/replays/` | ~148,640 |
| blizzard_ladder/4.10.1 | `replay_packs/blizzard_ladder/4.10.1/replays/` | ~2,837 |
| blizzard_ladder/4.9.3_oracle | `replay_packs/blizzard_ladder/4.9.3_oracle/restored/` | ~118 |
| blizzard_ladder/4.9.3_restored | `replay_packs/blizzard_ladder/4.9.3_restored/` | ~1 |
| aiarena_protoss (local) | `replays/aiarena_protoss/` | ~29 |
| 2025_HomeStory_Cup_XXVII | `replay_packs/2025_HomeStory_Cup_XXVII/` | ~61 |
| 2018_IEM_PyeongChang | `replay_packs/2018_IEM_PyeongChang/` | ~62 |
| 2020_ASUS_ROG_Online | `replay_packs/2020_ASUS_ROG_Online/` | ~107 |
| 2025_DreamHack_Dallas | `replay_packs/2025_DreamHack_Dallas/` | ~64 |
| 2025_Esports_World_Cup | `replay_packs/2025_Esports_World_Cup/` | ~113 |
| 2025_FEL_Cracow | `replay_packs/2025_FEL_Cracow/` | ~77 |

Datasets that skip scanning (special categories):

| Dataset | Status | Note |
|---------|--------|------|
| IEM10 Taipei 2016 | JSON format | 30 games, tracker events embedded, separate pipeline |
| SC2EGSet | Not downloaded | 17,930 replays, folder structure only |
| 2026_HomeStory_Cup_XXVIII | Not downloaded | 0 replays on disk |
| 2026_HomeStory_Cup_XXIX | Not downloaded | 0 replays on disk |

Missing directories are skipped with a log message (not a test failure) —
this matches existing `ReconstitutionExportTest` behaviour.

**2. Tracker event probe**

For each `.SC2Replay` file:

```
Parse with Scelight requesting TRACKER_EVENTS only
If replay.trackerEvents != null && events.length > 0:
    → tracker (has ground-truth data)
Else:
    → stripped (will use CmdEvent fallback)
```

This is the same check `ReplayFeatureExtractor.extract(Path)` uses, but
without running either extractor. The probe is O(parse) not O(extract) —
significantly cheaper for the 151K ladder replays.

**3. Per-dataset aggregation**

For each scanned dataset, collect:
- `total` — number of `.SC2Replay` files found
- `tracker` — number with tracker events present
- `stripped` — number without tracker events
- `failed` — number that failed to parse (exception during Scelight parse)
- `coverage` — `tracker / (total - failed) * 100`

**4. Markdown report writer**

Writes `docs/benchmarks/restoration-coverage.md` with:

```markdown
# Restoration Coverage Report

Generated: <timestamp>
Test: RestorationCoverageAuditTest

## Per-Dataset Coverage

| Dataset | Total | Tracker | Stripped | Failed | Coverage % |
|---------|-------|---------|----------|--------|------------|
| ... per scanned dataset ... |

## Special Categories

| Dataset | Status | Notes |
|---------|--------|-------|
| IEM10 Taipei 2016 | JSON format | 30 games, tracker events embedded |
| SC2EGSet | Not downloaded | ~17,930 replays available |
| ... |

## Summary

- Total scanned: N replays across M datasets
- Tracker path: N (X%)
- Stripped fallback: N (X%)
- Parse failures: N
- Not scanned: N replays (JSON/not downloaded)

## Parse Failures

| Dataset | File | Error |
|---------|------|-------|
| ... only if failures > 0 ... |
```

### Performance

The 148K blizzard_ladder/4.9.3 dataset is the bottleneck. Scelight
`TRACKER_EVENTS`-only parse is fast (~1-5ms per replay), but 148K files
still means 2-12 minutes of I/O. This is acceptable for a `@Tag("report")`
test that runs on demand.

No parallelism needed — the probe is I/O-bound on sequential file reads.
Scelight's parser is not thread-safe across replays, and the wall-clock
time is acceptable for a one-shot report.

### CLAUDE.md updates

Add `RestorationCoverageAuditTest` to the unit test list and the report
test documentation section.

## Decisions

- **D1:** New standalone test class (not extending existing tests) — keeps
  concern clean, avoids expensive feature extraction
- **D2:** Non-standard datasets (IEM10, SC2EGSet) reported as separate
  categories with status notes — complete picture without fake scan results

## References

- `ReplayFeatureExtractor.java` — routing logic (tracker events check)
- `CrossPatchExtractionTest.java` — `discoverDatasets()` pattern, `dsNoTracker` counter
- `ReconstitutionExportTest.java` — dataset enumeration pattern
- `EmulatedGameAccuracyBaselineTest.java` — markdown report writer pattern
- casehubio/quarkmind#380 — issue
- casehubio/quarkmind#372 — downstream: re-reconstitute training data
- casehubio/quarkmind#366 — epic: Phase 2.5 reconstitution accuracy gate

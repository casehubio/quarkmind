# Restoration Coverage Report — 2026-10-07

**Context:** Phase 2.5 reconstitution accuracy gate (#366)
**Test:** `RestorationCoverageAuditTest`
**Issue:** #380

## Per-Dataset Coverage

| Dataset | Total | Tracker | Stripped | Failed | Coverage % |
|---------|------:|--------:|---------:|-------:|-----------:|
| blizzard_ladder/4.9.3 | 148,640 | 0 | 148,640 | 0 | 0.0% |
| blizzard_ladder/4.10.1 | 2,837 | 0 | 2,837 | 0 | 0.0% |
| blizzard_ladder/4.9.3_oracle | 118 | 118 | 0 | 0 | 100.0% |
| blizzard_ladder/4.9.3_restored | 1 | 1 | 0 | 0 | 100.0% |
| aiarena_protoss | 29 | 29 | 0 | 0 | 100.0% |
| 2025_HomeStory_Cup_XXVII | 61 | 61 | 0 | 0 | 100.0% |
| 2018_IEM_PyeongChang | 62 | 62 | 0 | 0 | 100.0% |
| 2020_ASUS_ROG_Online | 107 | 107 | 0 | 0 | 100.0% |
| 2025_DreamHack_Dallas | 64 | 64 | 0 | 0 | 100.0% |
| 2025_Esports_World_Cup | 113 | 113 | 0 | 0 | 100.0% |
| 2025_FEL_Cracow | 77 | 77 | 0 | 0 | 100.0% |
| 2026_HomeStory_Cup_XXVIII | 0 | 0 | 0 | 0 | 0.0% |
| 2026_HomeStory_Cup_XXIX | 0 | 0 | 0 | 0 | 0.0% |

## Special Categories

| Dataset | Status | Notes |
|---------|--------|-------|
| IEM10 Taipei 2016 | JSON format | 30 games, tracker events embedded, separate pipeline |
| SC2EGSet | Not downloaded | ~17,930 tournament replays available as nested ZIPs |

## Summary

- **Total scanned:** 152,109 replays across 13 datasets
- **Tracker path (ground-truth):** 632 (0.4%)
- **Stripped fallback (88%/79%):** 151,477 (99.6%)
- **Parse failures:** 0
- **Not scanned:** ~17,960 replays (IEM10 JSON + SC2EGSet not downloaded)


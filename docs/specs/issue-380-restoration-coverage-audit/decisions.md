## D1: Test structure — new standalone vs extend existing

**Choice:** New standalone `RestorationCoverageAuditTest` with `@Tag("report")`
**Alternatives:**
- Extend CrossPatchExtractionTest — mixes concerns, runs expensive full extraction per replay
- Extend ReconstitutionExportTest — coverage audit would be side effect of data generation test
**Rationale:** The audit only needs to check whether tracker events exist in each replay — a lightweight parse, not full feature extraction. Separate test class keeps the concern clean and the run fast.
**Trade-offs:** Duplicates the `discoverDatasets()` pattern from CrossPatchExtractionTest/ReconstitutionExportTest (minor — could be extracted to shared utility later)
**Sources:** CrossPatchExtractionTest.java, ReconstitutionExportTest.java, ReplayFeatureExtractor.java
**Exploration:** quick
**Status:** captured

## D2: Handling non-standard datasets (IEM10 JSON, SC2EGSet empty)

**Choice:** Report as separate categories with status notes in the markdown report
**Alternatives:**
- Exclude from audit — incomplete picture, reader doesn't know what's missing
- Include IEM10 only — inconsistent treatment, SC2EGSet absence is still relevant info
**Rationale:** The audit's purpose is a complete picture of dataset coverage. IEM10 having tracker events embedded in JSON and SC2EGSet not being downloaded are both facts the reader needs. Separate category rows with explanatory notes (not fake counts) keep the report honest.
**Trade-offs:** Report includes datasets that weren't actually scanned — notes must be clear to avoid confusion
**Sources:** replays/replay-index.md, quarkmind-classifier/data/sc2egset/ (empty dirs)
**Exploration:** quick
**Depends on:** D1 (test structure determines where categories are rendered)
**Status:** captured

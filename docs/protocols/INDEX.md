# Protocol Index — quarkmind

| File | Summary | Applies to |
|------|---------|-----------|
| [journal-section-anchors-required.md](journal-section-anchors-required.md) | Journal entries must include §Section anchors for epic-close DESIGN.md merge | design/JOURNAL.md during active epics |
| [issue-refs-must-exist.md](issue-refs-must-exist.md) | Code comment issue references must have real GitHub issues | All source files — TODO, Refs, Fixes, Closes comments |
| [sc2data-train-times-require-calibration.md](sc2data-train-times-require-calibration.md) | SC2Data train times must be calibrated from replay ground truth, not seconds × 22.4 | SC2Data.trainTimeInLoops and any unit training duration constant |
| [replay-tag-prefix-per-source.md](replay-tag-prefix-per-source.md) | Each replay source uses a distinct unit tag prefix (r- binary, j- JSON) | Any class constructing or decoding unit tags from replay events |
| [extractor-separate-from-simulated-game.md](extractor-separate-from-simulated-game.md) | Command extraction in dedicated extractor classes, not SimulatedGame subclasses | Classes in io.quarkmind.sc2.mock |
| [emulated-plugin-seam-visibility.md](emulated-plugin-seam-visibility.md) | External plugin seam: interface and API types public; implementations package-private | sc2/emulated/ plugin seam interfaces |
| [plugin-canactivate-override-required.md](plugin-canactivate-override-required.md) | Override canActivate() on every plugin with non-trivial entryCriteria() — casehub-core default is broken | All plugin/ TaskDefinition implementations |
| [nearest-threat-conditional-write.md](nearest-threat-conditional-write.md) | NEAREST_THREAT written only when !enemies.isEmpty() — gate mechanism depends on key absence | All ScoutingTask implementations |

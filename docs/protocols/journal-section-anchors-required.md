---
id: PP-20260517-72f3c9
title: "Journal entries must include §Section anchors for epic-close DESIGN.md merge"
type: rule
scope: repo
applies_to: design/JOURNAL.md entries written during any active epic
severity: important
refs:
  - docs/DESIGN.md
violation_hint: "grep '§' design/JOURNAL.md returns empty — journal has prose but no §SectionName anchors"
created: 2026-05-17
---

Every entry added to `design/JOURNAL.md` during an epic must include a `§Section` anchor in its header — `### YYYY-MM-DD · §SectionName` — where `SectionName` matches a section in `docs/DESIGN.md`. The epic-close tool (`/epic`) only merges anchored entries into DESIGN.md; plain prose is silently skipped with no warning, causing DESIGN.md to drift from the journal. Use `java-update-design` (invoked from `java-git-commit`) to generate correctly anchored entries automatically; if committing via other means, add the anchor manually.

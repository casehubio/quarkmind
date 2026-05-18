---
id: PP-20260518-cdcbb8
title: "Code comment issue references must have real GitHub issues"
type: rule
scope: repo
applies_to: "All source files — any TODO, Refs, Fixes, or Closes comment referencing a GitHub issue number"
severity: important
refs:
  - docs/DESIGN.md
violation_hint: "A TODO(#N), Refs #N, or Fixes #N comment where issue #N does not exist on GitHub"
created: 2026-05-18
---

Any `#N` reference in a code comment — whether `TODO(#N)`, `Refs #N`, `Closes #N`, or similar — must have a real, open GitHub issue before the commit lands. Comments that cite non-existent issue numbers create misleading dead links in the codebase history. When drafting a comment that would reference an issue, check whether the issue exists; if not, create it first and record the returned issue number in the comment.

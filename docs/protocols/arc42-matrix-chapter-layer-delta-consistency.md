---
id: PP-20260617-248106
title: "ARC42STORIES.MD §9.3 chapter Layer Impact deltas must match the §9.2 Layer × Chapter matrix"
type: rule
scope: repo
applies_to: "ARC42STORIES.MD §9.3 chapter entries and the §9.2 Layer × Chapter matrix"
severity: guidance
refs:
  - ARC42STORIES.MD
violation_hint: "A chapter entry's Layer Impact table shows Medium for a layer, but the §9.2 matrix cell shows Low — or vice versa."
created: 2026-06-17
---

When writing or reviewing a §9.3 chapter entry, cross-check every Layer Impact delta value against the corresponding cell in the §9.2 Layer × Chapter matrix. If they disagree, the chapter entry is authoritative — it is written with full source context at delivery time, while the matrix is often drafted speculatively before implementation is complete. Update the matrix cell to match the chapter entry, not the other way around. The typical failure mode: the matrix uses Low as a default for all chapters at branch-start time, and the actual delta turns out to be Medium or High once the chapter entry is written from source. This was observed twice in one branch (L2 C4 Low→Medium, L4/L6 C5 Low/Medium→Medium/High).

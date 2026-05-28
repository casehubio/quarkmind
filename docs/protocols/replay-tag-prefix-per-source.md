---
id: PP-20260528-d9f967
title: "Each replay source uses a distinct unit tag prefix"
type: rule
scope: repo
applies_to: "Any class that constructs or decodes unit tags from replay events"
severity: important
refs:
  - src/main/java/io/quarkmind/sc2/mock/Sc2ReplayShared.java
  - src/main/java/io/quarkmind/sc2/mock/IEM10JsonSimulatedGame.java
violation_hint: "Calling Sc2ReplayShared.makeTag() in an IEM10 JSON context, or reusing the same prefix string for a new replay source"
created: 2026-05-28
---

Unit tags in quarkmind are prefixed by replay source: `r-` for binary SC2Replay
(scelight parser, `Sc2ReplayShared.makeTag()`), `j-` for SC2EGSet JSON
(`IEM10JsonSimulatedGame.makeTag()`). These prefixes prevent tags from two sources
from silently matching — a collision produces zero intents with no error. Any new
replay source or game event parser must use a unique, undeclared prefix and document
it here. Never call `Sc2ReplayShared.makeTag()` from a JSON-parsing context.

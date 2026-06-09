---
id: PP-20260609-38a43e
title: "Kill stale SC2 processes before starting %sc2 profile work"
type: rule
scope: repo
applies_to: "Any session using mvn quarkus:dev -Dquarkus.profile=sc2"
severity: important
refs:
  - sc2/real/RealSC2Engine.java
violation_hint: "ocraft logs 'Failed to connect after retries' immediately after launch; SC2 process exits within 100ms of being started by ocraft"
created: 2026-06-09
---

SC2 in API mode (`-listen -port 8168`) holds its port until the OS reclaims it — it does not
self-terminate on client disconnect. A stale SC2 from a previous session occupies port 8168;
ocraft launches a new SC2 that immediately exits (port conflict), `processIsAlive()` returns
false, and ocraft throws without retrying. Before running `mvn quarkus:dev -Dquarkus.profile=sc2`,
always run `pkill -9 SC2` to clear any lingering process. Symptoms look like an ocraft
configuration failure, not a port conflict.

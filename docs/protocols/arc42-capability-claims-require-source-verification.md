---
id: PP-20260616-0d5ad3
title: "ARC42STORIES.MD capability claims must be verified against source code"
type: rule
scope: repo
applies_to: "ARC42STORIES.MD §9.4 layer entries — capability comparison tables, layer impact statements, and delivery status claims"
severity: important
refs:
  - ARC42STORIES.MD
violation_hint: "A capability row claims a feature is delivered (e.g. 'Structured DECLINE speech act', 'Shared CaseFile read/write within tick') but the named class, annotation, or message type does not appear in src/main/java or is only platform-defined but not wired in production game-loop code."
created: 2026-06-16
---

Before committing any ARC42STORIES.MD §9.4 capability table entry or layer impact statement, verify each QuarkMind L7 column claim against current source code — not against intent, design spec, or platform documentation. Platform-defined capabilities (e.g. `MessageType.DECLINE` defined in casehub-qhorus) that are not wired in QuarkMind's production game-loop dispatch path must be described as pending, not delivered. Use IntelliJ MCP search or `git -C $PROJECT grep` to confirm the named symbol exists in `src/main/java` before writing "delivered" language.

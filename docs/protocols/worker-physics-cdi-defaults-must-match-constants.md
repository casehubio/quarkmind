---
id: PP-20261116-5f16ed
title: "WorkerPhysicsConfigProducer CDI defaultValue must match the corresponding DEFAULT_ constant"
type: rule
scope: repo
applies_to: "WorkerPhysicsConfigProducer.java and WorkerPhysicsConfig.java — any change to either file"
severity: important
refs:
  - quarkmind-sc2/src/main/java/io/quarkmind/sc2/emulated/WorkerPhysicsConfigProducer.java
  - quarkmind-sc2/src/main/java/io/quarkmind/sc2/emulated/WorkerPhysicsConfig.java
violation_hint: "A @ConfigProperty defaultValue string differs numerically from the DEFAULT_ constant in WorkerPhysicsConfig — the CDI-deployed application uses a different value than unit tests do"
created: 2026-11-16
---

Every `@ConfigProperty` in `WorkerPhysicsConfigProducer` must have a `defaultValue` that matches its corresponding `DEFAULT_*` constant in `WorkerPhysicsConfig`. When a new physics constant is added to `WorkerPhysicsConfig`, the CDI default and the Java constant must be set to the same calibrated value atomically in the same commit. This matters because the CDI path (Quarkus app) and the direct path (`WorkerPhysicsConfig.defaults()` used by unit tests) are independent — a mismatch means the deployed application silently uses a different constant than what all tests validated.

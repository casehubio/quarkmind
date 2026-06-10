---
id: PP-20260610-3c3e89
title: "Inject concrete StrategyTask type in @QuarkusTest — seam interface is ambiguous with three implementations"
type: rule
scope: repo
applies_to: "All @QuarkusTest classes that inject a StrategyTask for testing a specific implementation"
severity: important
refs:
  - src/main/java/io/quarkmind/agent/QuarkMindTaskRegistrar.java
violation_hint: "@Inject @CaseType(\"starcraft-game\") StrategyTask strategyTask — AmbiguousResolutionException at CDI validation with three competing implementations"
created: 2026-06-10
---

L6 registers three competing `StrategyTask` implementations (`DroolsStrategyTask`, `EarlyPressureStrategyTask`, `EconomicExpansionStrategyTask`) via `@Any Instance<StrategyTask>` in `QuarkMindTaskRegistrar`. `@Inject @CaseType("starcraft-game") StrategyTask` is now ambiguous and causes a `DeploymentException` in all `@QuarkusTest` classes that use it.

Test classes that exercise a specific implementation must inject the concrete type:

```java
// Wrong — ambiguous
@Inject @CaseType("starcraft-game") StrategyTask strategyTask;

// Correct — concrete type, no ambiguity
@Inject @CaseType("starcraft-game") DroolsStrategyTask strategyTask;
```

Also inject `StrategySelector` and call `strategySelector.selectForGame("strategy.drools", STRATEGY_VS_UNKNOWN)` in `@BeforeEach` — `DroolsStrategyTask.canActivate()` gates on the selector. Tests that need all implementations use `@Any Instance<StrategyTask>`.

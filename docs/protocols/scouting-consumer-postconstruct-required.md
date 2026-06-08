---
id: PP-20260608-8584ab
title: "ScoutingIntelConsumer implementations must call refreshSubscriptions() from @PostConstruct"
type: rule
scope: application
applies_to: "All classes implementing ScoutingIntelConsumer — DroolsTacticsTask, DroolsStrategyTask, FlowEconomicsTask, and any future consumer"
severity: important
refs:
  - src/main/java/io/quarkmind/agent/plugin/ScoutingIntelConsumer.java
  - src/main/java/io/quarkmind/agent/ScoutingIntelBroker.java
violation_hint: "ScoutingIntelBroker.activeTypes() does not include the plugin's subscribed types; broker.update() is never called for that plugin; intel is silently absent at execute() time"
created: 2026-06-08
---

Any class implementing `ScoutingIntelConsumer` must initialise its `subscribedTypes` field by calling `refreshSubscriptions(preferenceProvider.resolve(SettingsScope.root()))` from a `@PostConstruct` method. Without this, `subscribedIntelTypes()` permanently returns the safe-default `Set.of()` — `ScoutingIntelBroker.computeActiveTypes()` collects that empty set at `@PostConstruct`, and the type is never added to `activeTypes`. Neither `broker.update()` nor `dispatchToAdvisory()` fires for the missing type. The `ScoutingIntelConsumer.refreshSubscriptions()` interface method has a no-op default, so the omission compiles and links cleanly — the only symptom is silent absence of intel.

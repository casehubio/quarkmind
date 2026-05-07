# 0004 — Quarkus Flow single consume() step for economics

Date: 2026-05-07
Status: Accepted

## Context and Problem Statement

`FlowEconomicsTask` uses Quarkus Flow to run four economics decisions per tick
(train, build, expand, research). A natural model is four sequential `consume()`
steps. However, Quarkus Flow serialises the workflow payload between steps,
which resets the `ResourceBudget` accumulated by earlier steps — each decision
then sees a full budget and can independently overspend.

## Decision Drivers

* Budget coordination — all four decisions must share one `ResourceBudget` to prevent double-spend
* Quarkus Flow compatibility — solution must work within the extension's serialisation model
* R&D value — Quarkus Flow must remain the integration point, not be bypassed

## Considered Options

* **Option A** — Single `consume()` step calling all four decisions sequentially
* **Option B** — Four separate `consume()` steps with `ResourceBudget` passed through payload
* **Option C** — Replace Quarkus Flow with a plain CDI bean for economics

## Decision Outcome

Chosen option: **Option A**, because it gives one serialisation boundary,
keeping `ResourceBudget` mutable and shared across all four decisions.

### Positive Consequences

* `ResourceBudget` accumulates correctly across all four decisions
* No framework workarounds needed; idiomatic for a stateful shared budget
* Quarkus Flow remains the R&D integration point

### Negative Consequences / Tradeoffs

* The step is a monolith — all four decisions run or none do
* Less granular flow graph visibility in dashboards

## Pros and Cons of the Options

### Option A — Single step

* ✅ One serialisation boundary; `ResourceBudget` survives all four calls
* ✅ Idiomatic for stateful shared budget within a single workflow instance
* ❌ Less flow graph granularity

### Option B — Four steps with payload-carried budget

* ✅ More granular flow graph
* ❌ Quarkus Flow serialises payload between steps, resetting `ResourceBudget` (GE-0059) — broken by design

### Option C — Plain CDI bean

* ✅ No serialisation concerns; simplest implementation
* ❌ Removes Quarkus Flow as the R&D integration point — defeats the purpose of this plugin slot

## Links

* `FlowEconomicsTask` — `plugin/flow/FlowEconomicsTask.java`
* GE-0059 — ResourceBudget reset between consume() steps
* ADR-0001 — Quarkus Flow placement decision

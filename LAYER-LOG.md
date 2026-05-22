# QuarkMind Agentic Harness — Layer Log

Structured record of what was built at each layer, optimised for LLM consumption. Each entry is the raw material needed to reproduce the layer in a different domain harness. Correlates with blog entries in the workspace `blog/`, git history, and GitHub issues.

Entries are ordered for learning, not chronology. Each entry is complete when the layer closes — no placeholders.

Cross-references:
- Blog entries: workspace `blog/` (staged; published via `publish-blog`)
- Design specs: workspace `specs/` and promoted to `docs/superpowers/specs/`
- Tutorial teaching objectives: `quarkmind.md §Tutorial Layers` in casehub-parent
- AML reference implementation: `../aml/LAYER-LOG.md` (Layers 1–3 complete)
- Platform harness pattern: `https://raw.githubusercontent.com/casehubio/parent/main/docs/AGENTIC-HARNESS-GUIDE.md`

**Domain note:** QuarkMind uses a single-module Quarkus app (no `api/`/`app/` split). There are no downstream JPA consumers. CDI displacement works at the plugin level via `@CaseType` qualifier — each layer adds a new plugin implementation that takes priority over the prior one. The `NaiveXxxService @DefaultBean` pattern from AML applies at the plugin seam level here, not at a separate service class.

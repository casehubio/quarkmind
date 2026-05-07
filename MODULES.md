# Module Structure

Currently a single Maven module (`quarkmind-agent`). The planned split into
`starcraft-sc2` / `starcraft-domain` / `starcraft-agent` has been **deferred
indefinitely** — the R&D iteration pace makes splitting premature. Extract when
a plugin implementation is stable enough to version independently.

## Potential future split

| Module | Extract when | Contains |
|---|---|---|
| `starcraft-domain` | Domain model stabilises | `domain/` |
| `starcraft-sc2` | Engine seam freezes | `sc2/`, `sc2/mock/`, `sc2/real/`, `sc2/emulated/`, `sc2/replay/` |
| `starcraft-agent` | Plugin interfaces freeze | `agent/`, `agent/plugin/` |
| `starcraft-agent-drools` | Drools plugin matures | Drools `TaskDefinition` implementations |
| `starcraft-agent-flow` | Flow plugin matures | Quarkus Flow worker implementations |

**Current status (2026-05-07):** All phases through Phase 5 (EmulatedEngine)
are complete in a single module. No concrete plans to split — revisit when
preparing for Maven Central publication or native image milestone.

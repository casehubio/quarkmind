# Module Structure

Multi-module Maven project. Dependencies flow one way: each world module depends
on `quarkmind-core`. No world depends on another world. `quarkmind-core` depends
on CaseHub foundations.

## Modules

| Module | Status | Contains |
|---|---|---|
| `quarkmind-core` | Active | Agency framework — SPIs (WorldBridge, Intent, IntentQueue), NeedState, AgencyLoop, spatial/interaction/moment contracts |
| `quarkmind-sc2` | Active | StarCraft II agent — all SC2-specific code (domain, engine, plugins, visualizer, replay harness) |
| `quarkmind-town` | Stub | Sims-like 3D life simulation — Godot 4 client, Quarkus backend |
| `quarkmind-minecraft` | Stub | Minecraft agent — Mineflayer bridge, Luanti CI |
| `quarkmind-evennia` | Stub | MUD agent — Evennia bridge, text-based spatial model |
| `quarkmind-sonaria` | Stub | Roblox/Sonaria agent — creature in ecosystem |
| `quarkmind-godot-mcp` | Stub | Godot EditorPlugin MCP — visual world building tooling |

## Dependency Graph

```
casehub-parent
  └── quarkmind (parent POM)
       ├── quarkmind-core ← casehub-engine-api, casehub-eidos-api
       ├── quarkmind-sc2  ← quarkmind-core + all SC2 dependencies
       ├── quarkmind-town ← quarkmind-core
       ├── quarkmind-minecraft ← quarkmind-core
       ├── quarkmind-evennia ← quarkmind-core
       ├── quarkmind-sonaria ← quarkmind-core
       └── quarkmind-godot-mcp ← quarkmind-core
```

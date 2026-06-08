# Native Quarkus Compatibility Tracker

Status: JVM mode only (Phase 0)

## Dependencies

| Dependency | Version | Native Status | Notes |
|---|---|---|---|
| quarkus-rest | 3.34.2 | ✅ Supported | |
| quarkus-rest-jackson | 3.34.2 | ✅ Supported | |
| quarkus-scheduler | 3.34.2 | ✅ Supported | |
| casehub-core | 1.0.0-SNAPSHOT | 🔲 Not verified | Verify before native build |
| ocraft-s2client-bot | 0.4.21 | 🔲 Not verified | Uses RxJava + Protobuf; jar patched for Vert.x 4.x compat (see below) |
| ocraft-s2client-api | 0.4.21 | 🔲 Not verified | 5 classes bytecode-patched in local Maven repo to fix Vert.x 4.x API breakage; re-run `/tmp/ocraft_compat_patch.py` + `/tmp/ocraft_compat_patch2.py` after `mvn install` clears .m2 |
| drools-goap-tactics | — | ✅ Native-safe | Pure Java A* planner; no new dependency introduced. Drools tracked via casehub-core. |
| drools-cep-scouting | — | ✅ Native-safe | Rule unit model with DataStore accumulation; no runtime bytecode gen. Drools Executable Model handles AOT compilation. |
| commons-compress | 1.27.1 | 🔲 Not verified | Used only in `IEM10JsonSimulatedGame.enumerate()` for BZip2 outer ZIP reading. Confined to `sc2/mock/`. No bundled GraalVM metadata — would need manual reflection config if native build is attempted. |

## Rules (enforce these always)
- No dynamic class loading or runtime code generation
- All CDI injection via constructor or field — no programmatic Arc.container() lookups
- Reflection usages → register in src/main/resources/reflection-config.json
- No raw use of Class.forName()

## Known Issues
(none yet — updated as issues are discovered)

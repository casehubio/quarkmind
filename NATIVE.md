# Native Quarkus Compatibility Tracker

Status: JVM mode only (Phase 0)

## Dependencies

| Dependency | Version | Native Status | Notes |
|---|---|---|---|
| quarkus-rest | 3.34.2 | ✅ Supported | |
| quarkus-rest-jackson | 3.34.2 | ✅ Supported | |
| quarkus-scheduler | 3.34.2 | ✅ Supported | |
| casehub-core | 1.0.0-SNAPSHOT | 🔲 Not verified | Verify before native build |
| ocraft-s2client-bot | — | ✅ Removed (#185) | Replaced by QuarkusSC2Transport; no longer a dependency |
| ocraft-s2client-api | — | ✅ Removed (#185) | Was the Vert.x 3.x transport layer (5 classes bytecode-patched); fully removed |
| ocraft-s2client-protocol | 0.4.21 | ✅ No transport code | protobuf-java + jackson only; no Vert.x, no RxJava2; unblocks #14 (GraalVM) |
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

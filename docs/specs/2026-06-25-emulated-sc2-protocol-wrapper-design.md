# EmulatedGame SC2 Protocol Wrapper — Design Spec

**Issue:** #171
**Date:** 2026-06-25
**Branch:** issue-171-emulated-sc2-protocol-wrapper
**Revision:** 4 (third review)

## Goal

Wrap `EmulatedGame` in a server-side SC2 WebSocket/protobuf layer so that `RealSC2Engine` connects to it identically to how it connects to real SC2. The agent stack never knows it is talking to a simulation.

**Primary driver:** Anyone can clone, build, and test strategies without installing StarCraft II. Runs on CI (GitHub Actions) with no external dependencies.

**Secondary benefits:**
- Exercises the full ocraft/protobuf code path — eliminates "works in emulated, breaks in %sc2" gap
- Unblocks #14 (GraalVM native image tracing captures all ocraft reflection paths)
- Unblocks #13 (SC2 smoke test without real SC2)

## Non-Goals

- Replacing `%emulated` — the direct `EmulatedEngine` path stays for pure physics benchmarking without protocol overhead
- Full protobuf fidelity beyond what `ObservationTranslator` reads — surface matches the translator, enforced by round-trip test
- `SC2DebugScenarioRunner` support — follow-up issue (#203)
- Visualizer support — `RealSC2Engine.addFrameListener()` is a no-op (same in `%sc2` — pre-existing limitation); follow-up issue (#204)
- Natural game end detection — EmulatedGame has no win/loss condition; games run until `quit()`

## Known Behavioral Differences vs `%emulated`

Agents running through the SC2 protocol path experience these differences compared to the direct `EmulatedEngine` path:

| Field | `%emulated` | `%emulated-sc2` | Reason |
|-------|------------|-----------------|--------|
| `weaponCooldownTicks` | Populated from `PhysicsState` | Always 0 | Real SC2 protobuf doesn't carry cooldown data |
| `blinkCooldownTicks` | Populated from `PhysicsState` | Always 0 | Same — not in SC2 observation |
| `enemyBuildings` | Separate list | Collapsed into `enemyUnits` | SC2 protobuf doesn't distinguish enemy buildings from enemy units — all are `Alliance.Enemy` with a unit type ID |
| `enemyStagingArea` | Separate list | Collapsed into `enemyUnits` | Same — no staging area concept in SC2 protocol |
| `geysers` | Populated | Empty `List.of()` | `ObservationTranslator` doesn't extract neutral resources yet |
| `mineralPatches` | Empty (not modelled) | Empty `List.of()` | Not modelled in either path |

Tactics decisions that depend on cooldown awareness (blink retreat) behave differently than in `%emulated`. This matches real SC2 behavior — the protocol path is SC2-faithful by design.

## 1. Profile Architecture

New profile: `%emulated-sc2` (emulated physics, SC2 protocol).

| Profile | Engine | SC2 Protocol | Purpose |
|---------|--------|-------------|---------|
| `%mock` | MockEngine → SimulatedGame | No | Unit tests, dev |
| `%emulated` | EmulatedEngine → EmulatedGame | No | Physics benchmarks |
| `%emulated-sc2` | RealSC2Engine → transport → EmulatedSC2Server → EmulatedGame | Yes | Full-stack testing, CI, GraalVM tracing |
| `%replay` | ReplayEngine | No | Replay analysis |
| `%sc2` | RealSC2Engine → transport → real SC2 | Yes | Live SC2 |

**Profile gate changes:**

| Class | Current | New |
|-------|---------|-----|
| `RealSC2Engine` | `@IfBuildProfile("sc2")` | `@IfBuildProfile(anyOf = {"sc2", "emulated-sc2"})` |
| `QuarkusSC2Transport` | `@IfBuildProfile("sc2")` | `@IfBuildProfile(anyOf = {"sc2", "emulated-sc2"})` |
| `SC2BotAgent` | `@IfBuildProfile("sc2")` | `@IfBuildProfile(anyOf = {"sc2", "emulated-sc2"})` |
| `SC2StartupBean` | `@IfBuildProfile("sc2")` | `@IfBuildProfile(anyOf = {"sc2", "emulated-sc2"})` |
| `MockStartupBean` | `@UnlessBuildProfile(anyOf = {"sc2", "replay", "test", "prod"})` | Add `"emulated-sc2"` to exclusion list |

`QuarkusSC2Transport.skipProcessLaunch` becomes config-driven via `@ConfigProperty(name = "starcraft.sc2.skip-launch", defaultValue = "false")`. The `%emulated-sc2` profile sets it to `true`.

## 2. EmulatedSC2Server

CDI bean in `sc2/emulated/server/`. Starts a raw `ServerSocket` speaking the SC2 WebSocket/protobuf protocol, backed by an `EmulatedGame` instance.

**Lifecycle:**
- `@IfBuildProfile("emulated-sc2")`, `@ApplicationScoped`, `@Startup` — forces eager construction so the server is listening before `SC2StartupBean` fires `orchestrator.startGame()` → `transport.connect()`
- `@PostConstruct` opens the `ServerSocket` on the configured port and starts accepting connections in a virtual thread
- `@PreDestroy` closes the server socket

**Ownership:**
- Owns an `EmulatedGame` instance (not CDI-managed — same pattern as `EmulatedEngine`)
- Injects `EmulatedConfig` for race, strategy, and wave configuration

**Protocol handling** — promoted from `FakeSC2Server` with `buildResponse()` replaced:

| Request | Server behavior |
|---------|----------------|
| `Ping` | Pong (unchanged from FakeSC2Server) |
| `CreateGame` | Ack — game configuration deferred to JoinGame |
| `JoinGame` | Wire EmulatedGame: `TerrainGrid.emulatedMap()`, `PathfindingMovement`, `RaceModel`, `EnemyBehavior` from config; `game.reset()` |
| `GameInfo` | Build `ResponseGameInfo` with map name, player info, and `StartRaw` (see table below) — `SC2BotAgent.onGameStart()` extracts terrain via the normal SC2 path |
| `Observation` | `game.snapshot()` → `GameStateToProtobuf.translate()` → `ResponseObservation` |
| `Action` | Parse `ActionRawUnitCommand` → `ProtobufToIntent.translate()` → `game.applyIntent()` per intent |
| `Step` | `game.tick()` — transport drives the clock, same as real SC2 |
| `Quit` | Cleanup |

**StartRaw fields** — ocraft's `StartRaw.from()` requires all five sub-fields (`orElseThrow`). Omitting any crashes `SC2BotAgent.onGameStart()`. `FakeSC2Server` sidesteps this by not including `StartRaw` at all (it's optional in `ResponseGameInfo`), but we need it for terrain.

| Field | Value | Notes |
|-------|-------|-------|
| `mapSize` | `Size2dI(64, 64)` | Matches `TerrainGrid.emulatedMap()` dimensions |
| `pathingGrid` | `toPathingGrid()`, 1 bpp, 64×64 = 512 bytes | Functional — `SC2BotAgent` extracts terrain from this |
| `terrainHeight` | 1×1 8bpp stub (1 byte) | Required by ocraft; not read by anyone |
| `placementGrid` | 8×1 1bpp stub (1 byte) | Required by ocraft; not read by anyone. 8×1 avoids the 1×1 1bpp edge case where `size.getX() * size.getY() * bitsPerPixel / 8 = 0` (integer division) |
| `playableArea` | `RectangleI((0,0), (64,64))` | Required by ocraft; not read by anyone |

**Single connection** — accepts one WebSocket client at a time (SC2 API constraint). The accept loop handles TCP probes gracefully (same pattern as `FakeSC2Server.acceptAsync()`).

## 3. Translation Layers

Two pure-function translator classes — no CDI, no instance state, static methods, unit-testable without SC2. Mirror `ObservationTranslator` and `ActionTranslator` in design.

### GameStateToProtobuf

`sc2/emulated/server/GameStateToProtobuf.java` — translates `GameState` → `Sc2Api.Observation`.

Populates exactly the fields that `ObservationTranslator.translate()` reads:

| GameState field | Protobuf destination |
|----------------|---------------------|
| `minerals`, `vespene`, `supply` (`foodCap`), `supplyUsed` (`foodUsed`) | `PlayerCommon` |
| `myUnits` | `ObservationRaw.units` with `Alliance.Self`, not in `ALL_BUILDINGS` |
| `myBuildings` | `ObservationRaw.units` with `Alliance.Self`, in `ALL_BUILDINGS` |
| `enemyUnits` | `ObservationRaw.units` with `Alliance.Enemy` |
| `enemyBuildings` | `ObservationRaw.units` with `Alliance.Enemy` (building `Units` type IDs) |
| `enemyStagingArea` | `ObservationRaw.units` with `Alliance.Enemy` |
| `gameFrame` | `Observation.gameLoop` |

All three enemy lists (`enemyUnits`, `enemyBuildings`, `enemyStagingArea`) emit as `Alliance.Enemy` protobuf units. In real SC2, enemy buildings are structurally indistinguishable from enemy units at the protobuf level — `ObservationTranslator` processes all `Alliance.Enemy` entities through `toUnit()`. After round-tripping, all enemies collapse into `enemyUnits` with `enemyBuildings` and `enemyStagingArea` empty. This is correct SC2 protocol behavior.

Each domain `Unit` / `Building` maps to a protobuf `Raw.Unit` with: tag, unit type, `display_type=Visible`, alliance, position (x, y, z=0), health, healthMax, shield, shieldMax, build progress. `display_type` and `alliance` are ocraft `UnitSnapshot.from()` validation requirements (`orElseThrow`) — not read by `ObservationTranslator` but must be present.

Reverse lookup maps (`UnitType → Units`, `BuildingType → Units`) — the inverse of `ObservationTranslator.mapUnitType()` / `mapBuildingType()`. Static `EnumMap` tables, built once.

Minimal `ImageData` for `MapState.visibility` and `MapState.creep` (1×1 pixel, 8bpp — required by ocraft `MapState.from()` validation). `ObservationRaw` also requires a `PlayerRaw` with a mandatory `camera` position (`orElseThrow`) — fixed point (e.g., 50,50); not read by `ObservationTranslator`.

### TerrainGrid.toPathingGrid()

`domain/TerrainGrid.java` — new instance method, the inverse of the existing `fromPathingGrid()` static factory.

Encodes the grid's walkability into the SC2 bit-packed bitmap format: `index = x + y * width; bit = (data[index/8] >> (7 - index%8)) & 1`. Walkable tiles (Height.LOW, Height.RAMP, Height.HIGH) encode as 1; WALL encodes as 0.

Returns `byte[]` — the caller wraps it in protobuf `ImageData` with `bitsPerPixel=1`, `size=(width, height)`.

### ProtobufToIntent

`sc2/emulated/server/ProtobufToIntent.java` — translates `ActionRawUnitCommand` → `Intent`.

Reverse of `ActionTranslator`:

| Ability | Intent |
|---------|--------|
| `ATTACK` | `AttackIntent(tag, targetPos)` |
| `MOVE` | `MoveIntent(tag, targetPos)` |
| `BUILD_*` | `BuildIntent(tag, buildingType, targetPos)` |
| `TRAIN_*` | `TrainIntent(buildingTag, unitType)` |
| `EFFECT_BLINK_STALKER` | `BlinkIntent(tag, targetPos)` |
| `CALLDOWN_MULE` | `MuleCalldownIntent(tag, targetPos)` |

Reverse lookup: `abilityId (int) → Intent factory`. Built from `ActionTranslator` tables inverted.

### Round-Trip Enforcement

`GameStateRoundTripTest.java` — plain JUnit. Catches translation drift and protobuf validation failures.

**Observation round-trip** — must include the ocraft parsing layer to catch malformed protobuf that ocraft would reject at runtime:

```
EmulatedGame.snapshot() → GameState (original)
    → GameStateToProtobuf.translate() → Sc2Api.Response
    → ResponseObservation.from(response) → .getObservation()
    → ObservationTranslator.translate()
    → GameState (round-tripped)
Assert: original ≈ round-tripped (per-field, with documented lossy fields)
```

**Lossy fields in round-trip assertion** (expected, not bugs):

| Field | Original | Round-tripped | Reason |
|-------|----------|--------------|--------|
| `enemyBuildings` | Populated | Empty `List.of()` | Collapsed into `enemyUnits` — SC2 protocol doesn't distinguish |
| `enemyStagingArea` | Populated | Empty `List.of()` | Same |
| `enemyUnits` | Original enemies only | Original + buildings + staging | Absorbs collapsed lists |
| `geysers` | Populated | Empty `List.of()` | `ObservationTranslator` doesn't extract neutrals |
| `weaponCooldownTicks` | Non-zero | 0 | Not in SC2 protobuf |
| `blinkCooldownTicks` | Non-zero | 0 | Not in SC2 protobuf |

**Intent round-trip** — covers 4 of 6 intent types (Attack, Move, Build, Train):

```
Intent (original)
    → ActionTranslator.translate() → ResolvedCommand → protobuf Action
    → ProtobufToIntent.translate()
    → Intent (round-tripped)
Assert: original.equals(round-tripped)
```

Blink and MULE are not round-trippable — `ActionTranslator` returns null for both (they're not yet wired to real SC2 abilities). These two are tested one-directionally: `ProtobufToIntent` receives a protobuf action with the correct ability ID and produces the expected Intent.

**Terrain round-trip:**

```
TerrainGrid.emulatedMap()
    → toPathingGrid() → byte[]
    → fromPathingGrid(bytes, width, height)
    → TerrainGrid (round-tripped)
Assert: walkability matches at every tile
```

## 4. Configuration

New properties in `application.properties`:

```properties
# --- Transport: skip SC2 process launch, connect to in-process server ---
%emulated-sc2.starcraft.sc2.skip-launch=true
%emulated-sc2.starcraft.sc2.port=8168
%emulated-sc2.starcraft.sc2.connect.retry=5
%emulated-sc2.starcraft.sc2.connect.retry-interval-ms=200

# --- Emulated config: signal active simulation ---
%emulated-sc2.emulated.active=true

# --- CDI: in-memory ledger (same block as %mock/%emulated/%sc2/%test/%replay) ---
%emulated-sc2.quarkus.arc.selected-alternatives=\
  io.casehub.ledger.memory.InMemoryLedgerEntryRepository,\
  io.casehub.ledger.memory.InMemoryLedgerMerkleFrontierRepository,\
  io.casehub.ledger.memory.InMemoryActorTrustScoreRepository,\
  io.casehub.ledger.memory.InMemoryKeyRotationRepository,\
  io.casehub.ledger.memory.InMemoryAgentSigner,\
  io.casehub.ledger.memory.InMemoryActorIdentityBindingRepository,\
  io.casehub.ledger.memory.InMemoryReactiveLedgerEntryRepository,\
  io.casehub.ledger.memory.InMemoryReactiveKeyRotationRepository
%emulated-sc2.quarkus.index-dependency.casehub-ledger-memory.group-id=io.casehub
%emulated-sc2.quarkus.index-dependency.casehub-ledger-memory.artifact-id=casehub-ledger-memory
%emulated-sc2.casehub.ledger.hash-chain.enabled=false

# --- Domain: emulated map dimensions + terrain-aware tactics ---
%emulated-sc2.scouting.map.width=64
%emulated-sc2.quarkmind.tactics.kite.strategy=terrain-aware
```

`EmulatedSC2Server` reads `starcraft.sc2.port` to know which port to listen on. Both sides share the config key.

Connect retry tuned for in-process server: 5 retries × 200ms (1 second timeout) vs the default 60 × 5000ms (5 minutes) designed for waiting on real SC2 process launch.

`emulated.active=true` makes `EmulatedConfig.isActive()` return true — signals that emulated configuration (race, strategy, wave settings) is driving a live simulation.

CDI alternatives, Jandex index, and hash chain are duplicated per-profile (pre-existing pattern across all six profiles). `scouting.map.width=64` matches the emulated 64×64 map — without it, `DroolsScoutingTask.estimatedEnemyBase()` computes coordinates outside the map. `terrain-aware` kiting matches the emulated map's wall/ramp terrain — without it, tactics ignore terrain.

Emulated game properties (`emulated.player.race`, `emulated.enemy.race`, etc.) already work via `EmulatedConfig` — no new keys needed.

**Run command:**
```bash
mvn quarkus:dev -Dquarkus.profile=emulated-sc2
```

## 5. Package Placement

### New files

| File | Package | What it is |
|------|---------|-----------|
| `EmulatedSC2Server.java` | `sc2.emulated.server` | CDI bean — ServerSocket, WebSocket handshake, protocol dispatch |
| `GameStateToProtobuf.java` | `sc2.emulated.server` | Pure static — `GameState` → protobuf `Observation` |
| `ProtobufToIntent.java` | `sc2.emulated.server` | Pure static — protobuf `ActionRawUnitCommand` → `Intent` |
| `GameStateRoundTripTest.java` | `sc2.emulated.server` (test) | Round-trip translation verification (observation, intent, terrain) |
| `EmulatedSC2ServerTest.java` | `sc2.emulated.server` (test) | Integration — server + transport exchange observations and actions |
| `SC2WebSocketCodec.java` | `sc2` | Pure static — WebSocket handshake and frame encode/decode (RFC 6455) |

### Modified files

| File | Change |
|------|--------|
| `RealSC2Engine.java` | Profile gate: `anyOf = {"sc2", "emulated-sc2"}` |
| `QuarkusSC2Transport.java` | Profile gate + `skipProcessLaunch` → `@ConfigProperty` |
| `SC2BotAgent.java` | Profile gate |
| `SC2StartupBean.java` | Profile gate |
| `MockStartupBean.java` | Add `"emulated-sc2"` to exclusion |
| `TerrainGrid.java` | Add `toPathingGrid()` instance method |
| `EmulatedGame.java` | Visibility widened for cross-package access from EmulatedSC2Server |
| `EnemyBehavior.java` | Visibility widened for cross-package access from EmulatedSC2Server |
| `RaceModelFactory.java` | Visibility widened for cross-package access from EmulatedSC2Server |
| `application.properties` | `%emulated-sc2.*` properties |

### Unchanged

`EmulatedEngine`, `ObservationTranslator`, `ActionTranslator`, `SC2FrameCallback`, `AgentOrchestrator`, all plugins.

## 6. Testing

| Test | Type | Verifies |
|------|------|----------|
| `GameStateRoundTripTest` | Plain JUnit | Observation round-trip through ocraft parsing; intent round-trip (4/6 types) + one-directional Blink/MULE; terrain round-trip |
| `EmulatedSC2ServerTest` | Plain JUnit | Full integration: server starts, transport connects, game loop runs N frames, observations arrive, actions dispatch. Tests terminate via `transport.quit()`. |
| Existing `QuarkusSC2TransportTest` | Unchanged | Regression — transport still works against `FakeSC2Server` |

No `@QuarkusTest` needed — server and transport are plain Java, wired manually in tests.

**Smoke test:** `mvn quarkus:dev -Dquarkus.profile=emulated-sc2` — verify agent loop starts, plugins run, game ticks.

**WebSocket framing deduplication:** The handshake and frame encode/decode code is shared across `QuarkusSC2Transport` (client), `FakeSC2Server` (test), and `EmulatedSC2Server`. Extract to a shared `SC2WebSocketCodec` utility during implementation — pure static methods, no state.

## 7. Follow-Up Issues

Filed:
- #203 — `SC2DebugScenarioRunner` support for `%emulated-sc2` profile
- #204 — Visualizer does not work in `%emulated-sc2` (or `%sc2`) — `RealSC2Engine.addFrameListener()` is a no-op. Root cause: `RealSC2Engine.observe()` does not fire frame listeners, unlike `MockEngine` and `EmulatedEngine`. Use `%emulated` for visualization. Fix would wire frame events through `RealSC2Engine.observe()` — benefits both profiles.

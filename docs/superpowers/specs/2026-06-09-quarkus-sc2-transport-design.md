# Quarkus-Native SC2 WebSocket Transport — Design Spec

**Issue:** [#185](https://github.com/casehubio/quarkmind/issues/185)
**Branch:** `issue-185-quarkus-sc2-transport`
**Date:** 2026-06-09
**Revision:** 9 (post-review)

---

## Problem

`ocraft-s2client-bot` (0.4.21, unmaintained since 2021) creates its own Vert.x instance
(`VertxFactory.create()`) and deploys a verticle for the SC2 WebSocket connection. Quarkus
3.x bundles Vert.x 4.x; ocraft was built against Vert.x 3.x. The result is two Vert.x
instances, seven API incompatibilities requiring bytecode patches to the ocraft JAR, and an
RxJava2 dependency that blocks GraalVM native image (#14). ARC42STORIES §12 tracks the
`ocraft-s2client` JVM-only constraint as Medium severity.

The SC2 protocol is a single WebSocket at `ws://127.0.0.1:{port}/sc2api`, exchanging binary
protobuf `Request`/`Response` frames in a strict sequential (one request in flight at a time)
pattern. ocraft wraps this with ~2000 lines of Vert.x verticles, RxJava2 observables, and
retry machinery. We replace the transport with ~200 lines using the JDK's built-in
`java.net.http.WebSocket` client.

---

## Approach

Remove `ocraft-s2client-bot` (which pulls in `ocraft-s2client-api`, Vert.x 3.x, RxJava2).
Add `ocraft-s2client-protocol` as a **direct** dependency — it was previously transitive and
contains only `protobuf-java` + `jackson-databind`, both already on the Quarkus classpath.
No Vert.x. No RxJava2. No bytecode patching.

Write `QuarkusSC2Transport` using `java.net.http.WebSocket` (JDK 11+, already used in
`GameStateWebSocketTest`). Remove `S2Coordinator` and `S2Agent` from the codebase.

The `SC2Engine` interface is **unchanged**. All mock/emulated/replay implementations are
**unchanged**. The change is contained to `sc2/real/`.

---

## Dependency Change

```xml
<!-- Remove: pulls in ocraft-s2client-api → Vert.x 3.x, RxJava2, bytecode patches -->
<dependency>
    <groupId>com.github.ocraft</groupId>
    <artifactId>ocraft-s2client-bot</artifactId>
    <version>0.4.21</version>
</dependency>

<!-- Add: protobuf-java + jackson-databind only — no Vert.x, no RxJava2 -->
<dependency>
    <groupId>com.github.ocraft</groupId>
    <artifactId>ocraft-s2client-protocol</artifactId>
    <version>0.4.21</version>
</dependency>
```

Transitive closure removed: `ocraft-s2client-api`, `vertx-web-client`, `vertx-rx-java2`,
`rxjava:2.2.21`. Retained: `ocraft-s2client-protocol`, `protobuf-java`, `jackson-databind`.

---

## Files Changed

| File | Change |
|------|--------|
| `pom.xml` | Swap `ocraft-s2client-bot` for `ocraft-s2client-protocol` |
| `sc2/real/QuarkusSC2Transport.java` | **NEW** — WebSocket client + SC2 lifecycle + game loop |
| `sc2/real/SC2FrameCallback.java` | **NEW** — transport-level callback interface (lives in `sc2/real/`) |
| `sc2/real/RealSC2Engine.java` | Replace `S2Coordinator` with `QuarkusSC2Transport`; SC2 config properties move to transport; `isConnected()` delegates to `transport.isRunning()`; `connected` AtomicBoolean removed; remove orphaned `@Inject IntentQueue` |
| `sc2/real/SC2BotAgent.java` | Drop `extends S2Agent`; implement `SC2FrameCallback`; add `@Inject QuarkusSC2Transport` |
| `sc2/real/ObservationTranslator.java` | `ObservationInterface` → `Observation`; `List<UnitInPool>` → `Set<Unit>` |
| `sc2/real/SC2DebugScenarioRunner.java` | `enqueueDebugCommand(Runnable)` → `enqueueDebugCommand(RequestDebug)` |
| `sc2/real/ResolvedCommand.java` | Unchanged (uses `ocraft-s2client-protocol` types) |
| `sc2/real/ActionTranslator.java` | Unchanged (uses `ocraft-s2client-protocol` types) |
| `sc2/real/SC2StartupBean.java` | Unchanged |
| `NATIVE.md` | `ocraft-s2client-api` row → ✅ removed; `ocraft-s2client-protocol` → ✅ no transport code |

**Not changed:** `SC2Engine` interface, all mock/emulated/replay implementations, existing tests.

---

## Architecture

```
AgentOrchestrator
  └── SC2Engine.observe() / dispatch() / connect() / joinGame() / leaveGame()
        └── RealSC2Engine  (@IfBuildProfile("sc2"))
              ├── QuarkusSC2Transport  — WebSocket lifecycle, SC2 protocol, game loop thread
              └── SC2BotAgent         — implements SC2FrameCallback; injects transport for dispatch
```

The `SC2Engine` contract is unchanged: `tick()` is a no-op (transport owns the clock),
`observe()` returns the latest `GameState` from `SC2BotAgent`, `dispatch()` is a no-op
(SC2BotAgent dispatches actions from within `onStep()`).

---

## `SC2FrameCallback` (new interface, `sc2/real/`)

```java
// Package: io.quarkmind.sc2.real
interface SC2FrameCallback {
    void onGameStart(ResponseGameInfo gameInfo);
    void onStep(Observation obs);
    void onGameEnd();
}
```

`ResponseGameInfo` and `Observation` are ocraft-protocol types. `SC2BotAgent` implements
this interface. Lives in `sc2/real/` — transport-level callback, not part of the `SC2Engine`
seam.

---

## `QuarkusSC2Transport`

**Responsibility:** everything between the OS socket and the `SC2FrameCallback`. Knows nothing
about quarkmind domain types — only `SC2APIProtocol.*` and ocraft-protocol wrapper types.

**Config ownership:** all SC2 game-setup properties move from `RealSC2Engine` to the transport.
`RealSC2Engine` becomes a thin lifecycle coordinator that delegates entirely to the transport.

```java
@IfBuildProfile("sc2") @ApplicationScoped
public class QuarkusSC2Transport {

    @ConfigProperty(name = "starcraft.sc2.port",        defaultValue = "8168")
    int sc2Port;

    @ConfigProperty(name = "starcraft.sc2.map",         defaultValue = "TorchesAIE_v4")
    String mapName;

    @ConfigProperty(name = "starcraft.sc2.difficulty",  defaultValue = "VERY_EASY")
    String difficultyStr;   // parsed to ocraft Difficulty enum

    @ConfigProperty(name = "starcraft.sc2.ai.race",     defaultValue = "RANDOM")
    String aiRaceStr;       // the computer opponent's race (ocraft Race enum)

    @ConfigProperty(name = "starcraft.sc2.race",        defaultValue = "PROTOSS")
    String botRaceStr;      // the bot player's race (ocraft Race enum)

    @ConfigProperty(name = "starcraft.sc2.connect.retry",              defaultValue = "60")
    int connectRetryCount;         // TCP probe attempts before connect() throws

    @ConfigProperty(name = "starcraft.sc2.connect.retry-interval-ms",  defaultValue = "5000")
    int connectRetryIntervalMs;    // sleep between TCP probe attempts, in milliseconds
                                   // (per-attempt socket timeout is fixed at 200ms)

    // --- Thread / lifecycle state (internal, managed by runGameLoop / quit) ---
    private volatile Thread gameLoopThread;            // stored so quit() can interrupt it
    private volatile boolean running = false;          // true while game loop is alive; false in finally
    private final AtomicBoolean quitting = new AtomicBoolean(false);

    // --- Lifecycle (called by RealSC2Engine) ---
    void connect();           // resolve SC2 exe, launch process, TCP probe, WS connect + ping
    void createGame();        // RequestCreateGame (reads map, difficulty, aiRace from config)
    void joinGame();          // RequestJoinGame with InterfaceOptions.raw=true (reads botRace from config)
    void runGameLoop(SC2FrameCallback callback);   // stores thread ref; starts virtual thread: preamble + loop + finally
    void quit();              // sets quitting=true + interrupts game loop thread (safe from any thread)
                              // RequestQuit + WS close happen inside the game loop's finally block (correct thread)
    boolean isRunning();      // delegates to: running — used by RealSC2Engine.isConnected()

    // --- Called from within SC2BotAgent.onStep() only ---
    void sendActions(List<ResolvedCommand> commands);  // RequestAction → ResponseAction
    void sendDebug(Sc2Api.RequestDebug req);            // RequestDebug → ResponseDebug

    @PreDestroy
    void shutdown();          // calls quit(); game loop thread handles RequestQuit + close WS + onGameEnd()
}
```

`botRaceStr` is `com.github.ocraft.s2client.protocol.game.Race` after parsing — the only
`Race` in scope on the transport. `io.quarkmind.domain.Race` is a plain enum with no
`toSc2Api()` and is never used here.

---

### `createGame()` — proto structure and the `realtime` invariant

#### `realtime = false` is the load-bearing bot-control invariant

`RequestCreateGame.realtime = 6` is `optional bool`. Its proto comment: "If set, the game
plays in real time." Not setting it (proto default `false`) means step-based bot control —
SC2 advances the simulation only when the bot sends `RequestStep`. This is what the game loop
requires.

With `realtime = true`, SC2 advances on its own clock; `RequestStep` is **silently ignored**.
The game loop would observe the same frozen frame state every step, with the simulation
advancing in the background without bot input. The entire `runGameLoop()` structure becomes
meaningless.

`realtime` must never be set in `RequestCreateGame`. This is as critical as
`InterfaceOptions.raw = true` in `joinGame()`: one controls observation data availability,
the other controls bot step authority.

#### Builder structure

```
Sc2Api.Request
  └── RequestCreateGame createGame
        ├── LocalMap local_map
        │     └── map_path = "<absolute path to .SC2Map>"  (string, ≤260 chars)
        ├── PlayerSetup player_setup[0]   (the bot player)
        │     └── type = PlayerType.Participant
        │         (race field OMITTED — proto comment: "Only used for a computer player")
        ├── PlayerSetup player_setup[1]   (the computer AI)
        │     ├── type = PlayerType.Computer
        │     ├── race = Common.Race.Random   (from aiRaceStr config, default RANDOM)
        │     └── difficulty = Difficulty.VeryEasy  (from difficultyStr config)
        └── realtime: NOT SET  (proto default false — step-based control)
```

Confirmed types from source:
- `PlayerType` enum constants are TitleCase: `Participant(1)`, `Computer(2)`, `Observer(3)`
- `PlayerSetup.race` (field 2) is `SC2APIProtocol.Common.Race` — confirmed proto comment "Only used for a computer player"
- `LocalMap.map_path` is `optional string` (path as absolute string, ≤260 chars)
- `RequestCreateGame.realtime` is `optional bool = 6`

`RealSC2Engine.connect()` currently builds the map path as:
```java
Path.of(System.getProperty("user.home"), ".quarkmind", "maps", mapName + ".SC2Map")
```
This path construction moves into the transport. `mapFile.toAbsolutePath().toString()` provides the string for `map_path`.

---

### `joinGame()` — `InterfaceOptions.raw = true` is mandatory

The SC2 Remote API requires the client to declare which data interfaces it wants in
`RequestJoinGame`. Without `InterfaceOptions.raw = true`:
- `ObservationRaw` is absent from every frame — `obs.getRaw()` returns empty `Optional`
- `obs.getRaw().orElseThrow()` in `ObservationTranslator.translate()` crashes on the first frame
- `Action.action_raw` is ignored — confirmed from proto comment "Populated if Raw interface is enabled"

`joinGame()` must build:

```java
Sc2Api.Request.newBuilder()
    .setJoinGame(Sc2Api.RequestJoinGame.newBuilder()
        .setRace(Race.valueOf(botRaceStr).toSc2Api())   // com.github.ocraft.s2client.protocol.game.Race
        .setOptions(Sc2Api.InterfaceOptions.newBuilder().setRaw(true))
        .build())
    .build()
```

`Race` here is `com.github.ocraft.s2client.protocol.game.Race` (implements
`Sc2ApiSerializable<Common.Race>`, has `toSc2Api()`). `io.quarkmind.domain.Race` has no
`toSc2Api()` and is not used in the transport.

ocraft's `S2Coordinator` enables raw automatically via its participant setup; this transport
must do it explicitly. There is no other option; it is not configurable.

---

### Thread model

Game loop runs on a **virtual thread** (Java 21, confirmed per ARC42STORIES §2):

```java
Thread.ofVirtual().name("sc2-game-loop").start(this::gameLoop);
```

Virtual threads are designed for blocking I/O. Using `CompletableFuture.runAsync()`
(ForkJoinPool) would hold a common-pool thread hostage during `poll()` timeout windows —
semantically wrong for blocking I/O.

---

### WebSocket client, fragment accumulation, and `sendSync`

Uses `java.net.http.WebSocket` (JDK built-in). WebSocket I/O callbacks run on a JDK internal
thread pool.

**`SynchronousQueue<byte[]>` is the pairing primitive.** Structurally enforces depth-0 — no
stale response risk.

**Fragment accumulation is required.** The JDK WebSocket delivers a logical message across
multiple `onBinary` calls: `last=false` for intermediate fragments, `last=true` for the final.
SC2 observations with 100+ visible units can span multiple fragments. A truncated protobuf
parses as garbage or throws `InvalidProtocolBufferException`. `ByteArrayOutputStream`
accumulates fragments; `put()` fires only when `last=true`. The JDK WebSocket guarantees
sequential delivery to the listener (single I/O thread) — no synchronization needed.

```java
private final SynchronousQueue<byte[]> responseQueue = new SynchronousQueue<>();
private final ByteArrayOutputStream messageBuffer = new ByteArrayOutputStream();

// Listener (single JDK WebSocket I/O thread — sequential delivery guaranteed):
@Override
public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
    byte[] chunk = new byte[data.remaining()];
    data.get(chunk);               // safe for both heap and direct ByteBuffers
    messageBuffer.write(chunk, 0, chunk.length);
    ws.request(1);
    if (last) {
        byte[] complete = messageBuffer.toByteArray();
        messageBuffer.reset();
        try {
            responseQueue.put(complete); // blocks until game loop thread calls poll()
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // message lost; poll() times out; loop exits
        }
    }
    return null;
}

// sendSync (game loop virtual thread):
private Sc2Api.Response sendSync(Sc2Api.Request request, Duration timeout)
        throws IOException, InterruptedException, TimeoutException {
    byte[] payload = request.toByteArray();
    try {
        webSocket.sendBinary(ByteBuffer.wrap(payload), true).join();
    } catch (CompletionException e) {
        throw new IOException("WebSocket send failed", e.getCause()); // rethrow as IOException
    }
    // CompletionException wraps the underlying IOException; catching here keeps sendSync()'s
    // declared contract consistent — all failures surface as IOException to the game loop.
    byte[] responseBytes = responseQueue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    if (responseBytes == null) throw new TimeoutException("SC2 did not respond within " + timeout);
    Sc2Api.Response response = Sc2Api.Response.parseFrom(responseBytes);
    if (response.getErrorCount() > 0) {
        throw new IOException("SC2 error: " + response.getErrorList());
    }
    return response;
}
```

**`put(complete)` is correct:** `offer()` (no-arg) drops the response if no consumer is
waiting; `put()` blocks until the game loop thread calls `poll()` — bounded to microseconds.

**`sendBinary().join()` is required; `CompletionException` must be caught in `sendSync()`.** `CompletableFuture.join()` throws `CompletionException` (unchecked, extends `RuntimeException`) when the future completed exceptionally — not `IOException`. The game loop's `catch (IOException ...)` would not intercept it, leaving send failures unlogged. Catching `CompletionException` in `sendSync()` and rethrowing as `IOException` keeps `sendSync()`'s declared exception contract consistent: all failure modes surface as `IOException`, reach the catch block, and are logged.

**`data.get(chunk)` is the only correct extraction:** `data.array()` throws on direct
`ByteBuffer`s.

---

### Timeout differentiation

| Call site | Timeout |
|-----------|---------|
| `connect()` — ping | `Duration.ofSeconds(30)` |
| `createGame()`, `joinGame()`, `requestGameInfo()` (internal) | `Duration.ofSeconds(60)` |
| Per-frame observation + step | `Duration.ofSeconds(5)` |

---

### SC2 process launch and port polling

`resolveSC2Executable()` logic moves from `RealSC2Engine` into the transport.
`ProcessBuilder` spawns SC2 with `-listen -port {sc2Port} -displayMode 0`.

Two-phase port polling:
1. **TCP probe:** retry loop (`connectRetryCount` attempts, `connectRetryIntervalMs` sleep between attempts):
   ```java
   try (var probe = new Socket()) {
       probe.connect(new InetSocketAddress("127.0.0.1", sc2Port), 200);
   }
   // success — fall through to WebSocket handshake
   ```
   Each attempt either succeeds (falls through) or throws (caught, sleep `connectRetryIntervalMs`, retry). The try-with-resources closes the probe socket immediately on success — avoids holding a kernel file descriptor across retry attempts.
2. **WebSocket handshake:** only after the TCP probe succeeds.

---

### `runGameLoop()` — preamble then loop

`runGameLoop(callback)` is called by `RealSC2Engine` after `transport.joinGame()` returns.

```
running = true
boolean gameStarted = false

try {
  // Preamble — inside try so WebSocket is closed if this fails
  requestGameInfo()  [internal, 60s timeout]  → ResponseGameInfo gameInfo
  callback.onGameStart(gameInfo)
  gameStarted = true   // ← set only after onGameStart() succeeds

  loop:
    exit condition: quitting.get() || Thread.currentThread().isInterrupted()

    1. sendSync(RequestObservation, 5s)  → Response
       a. status check: if response.getStatus() != Sc2Api.Status.in_game → break
          (Status enum: launched(1), init_game(2), in_game(3), in_replay(4), ended(5), quit(6))
       b. ResponseObservation.from(response) → responseObs
       c. responseObs.getObservation()      → Observation (ocraft wrapper, already parsed)
       d. callback.onStep(observation)
          ├── ObservationTranslator.translate(obs) → GameState (stored in AtomicReference)
          ├── ActionTranslator.translate(intentQueue.drainAll()) → List<ResolvedCommand>
          ├── if commands non-empty: transport.sendActions(commands) → sendSync(RequestAction, 5s)
          └── if pendingDebugCommands non-empty: drain all into one RequestDebug →
                transport.sendDebug(batched) → sendSync(RequestDebug, 5s)
    2. sendSync(RequestStep(count=1), 5s) → ResponseStep

} catch (InterruptedException | IOException | TimeoutException e) {
  log error unless InterruptedException (which is expected on quit())

} finally {
  running = false      // ← first: RealSC2Engine.isConnected() returns false immediately
  if (quitting.get()): sendSync(RequestQuit, 5s) [best-effort; on game loop thread — safe]
  closeWebSocket()     // ← always: prevents leak on preamble failure
  if (gameStarted): callback.onGameEnd()   // only if onGameStart() was called
  gameLoopThread = null
}
```

`Sc2Api.Status.in_game` — protobuf-java preserves the proto's snake_case naming directly.

**Debug batching:** all `Sc2Api.RequestDebug` objects in `pendingDebugCommands` since the
last step are merged into a single `RequestDebug` via `addAllDebug()` before sending. One
round-trip for all pending debug commands.

---

### `sendActions()` — proto nesting

```
Sc2Api.Request
  └── RequestAction action
        └── repeated Action actions
              └── Raw.ActionRaw action_raw   (field 1; requires InterfaceOptions.raw=true)
                    └── Raw.ActionRawUnitCommand unit_command
                          ├── int ability_id            ← ability.getAbilityId()
                          ├── repeated uint64 unit_tags ← tag.toSc2Api()  (Long)
                          └── Common.Point2D target_world_space_pos  ← point2d.toSc2Api()
                                                          (absent for non-positional commands e.g. train)
```

`queue_command = false` (field 5) — replaces existing orders, same semantics as the
current `actions().unitCommand(..., false)` ocraft call.

---

### `quit()` threading design

`sendSync()` is only safe when called from the game loop virtual thread — it is the sole
consumer of `responseQueue` and the sole sender on the WebSocket. Calling it from any other
thread (e.g., `RealSC2Engine.leaveGame()` on the scheduler thread, or `@PreDestroy` on a
CDI shutdown thread) would create two concurrent senders on the WebSocket (undefined
behaviour per JDK spec) and two competing consumers on `responseQueue`.

`quit()` therefore **must not** call `sendSync()`. Instead:

```java
void quit() {
    quitting.set(true);
    Thread t = gameLoopThread;
    if (t != null) t.interrupt();
}
```

The interrupt propagates into the game loop virtual thread at its next interruptible
operation: `responseQueue.poll(timeout, MILLISECONDS)` throws `InterruptedException` when
the thread is interrupted. Control then transfers to the `catch` block and unconditionally
to the `finally` block (see game loop structure below).

The `finally` block runs on the game loop virtual thread — the correct and only safe thread
for `sendSync`. Its full structure is in the `runGameLoop()` pseudocode above; the quit path
is the `if (quitting.get()): sendSync(RequestQuit, 5s)` branch. Duplicating the block here
would risk future divergence; consult the pseudocode as the single authoritative description.

The `Thread` reference is stored as `volatile Thread gameLoopThread` on the transport.
`runGameLoop()` writes it before starting the virtual thread; the `finally` block nulls it
on exit. `quit()` reads it with a local copy to avoid a race with nulling.

This replicates the safety property of the current ocraft design, where
`gameLoop.cancel(true)` interrupts ocraft's internal game loop thread and ocraft's own
code handles cleanup — the caller never touches the I/O path directly.

### `isConnected()` delegation — and removal of `RealSC2Engine.connected`

`AgentOrchestrator.gameTick()` (confirmed from source) gates every 500ms scheduler dispatch
on `if (!engine.isConnected()) return;`. `isConnected()` must transition to `false` on all
game exits — natural end, error, and explicit quit.

The current `RealSC2Engine` maintains an `AtomicBoolean connected` and sets it `false` inside
ocraft's game loop on natural end (`connected.set(false)` after `while (coordinator.update())`).
After the refactor that code is gone and `connected` would never transition on natural end.

The fix: **`RealSC2Engine.connected` is removed entirely.** `RealSC2Engine.isConnected()`
delegates to `transport.isRunning()`:

```java
@Override
public boolean isConnected() {
    return transport.isRunning();
}
```

`transport.isRunning()` returns `running` — the `volatile boolean` that is set to `false`
at the **start** of the game loop `finally` block, before `RequestQuit`, WebSocket close, or
`onGameEnd()`. This means:
- `isConnected()` transitions to `false` immediately when the game loop exits for any reason
- `gameTick()` stops dispatching before `onGameEnd()` fires — correct ordering
- No extra mechanism is needed; the `finally` block already handles all exit paths

### `@PreDestroy`

`@PreDestroy` calls `quit()`. The game loop virtual thread handles `RequestQuit`, WebSocket
close, and `onGameEnd()` from within its `finally` block. Without `@PreDestroy`, the virtual
thread leaks on JVM exit.

---

## `SC2BotAgent` Refactoring

**Removes:** `extends S2Agent` (ocraft-s2client-bot).

**Implements:** `SC2FrameCallback`.

**Adds:** `@Inject QuarkusSC2Transport transport` — CDI injection. Used within `onStep()` to
call `transport.sendActions()` and `transport.sendDebug()`. No circular CDI: `QuarkusSC2Transport`
receives `SC2BotAgent` as a method parameter via `runGameLoop(callback)`, not via CDI injection.

**`onGameStart(ResponseGameInfo gameInfo)`:** calls `gameInfo.getStartRaw().ifPresent(...)`.
`ResponseGameInfo.getStartRaw()` returns `Optional<StartRaw>` — confirmed from source. The
`.ifPresent()` pattern is preserved unchanged.

**`onStep(Observation obs)`:** translates, stores `GameState`, dispatches actions and debug via
the injected `transport`. The `@IfBuildProfile("sc2")` guard on both beans ensures CDI
resolves cleanly.

### Debug command queue: `enqueueDebugCommand(RequestDebug)`

Original `enqueueDebugCommand(Runnable)` existed because ocraft required debug calls to occur
inside `onStep()`. That constraint is gone. `Runnable` is now semantically wrong.

```java
private final Queue<Sc2Api.RequestDebug> pendingDebugCommands = new ConcurrentLinkedQueue<>();

public void enqueueDebugCommand(Sc2Api.RequestDebug request) {
    pendingDebugCommands.add(request);
}
```

`SC2DebugScenarioRunner` builds the proto upfront (where domain knowledge lives). Debug
commands accumulated in `pendingDebugCommands` are batched and dispatched from within
`onStep()` via `transport.sendDebug()`.

---

## `ObservationTranslator` Update

One method signature changes. All static mapping methods (`mapUnitType`, `mapBuildingType`,
`isBuilding`) are **unchanged** — `ObservationTranslatorTest` passes without modification.

### Naming conflict resolution

Both `io.quarkmind.domain.Unit` and `com.github.ocraft.s2client.protocol.unit.Unit` are in
scope. Resolution: ocraft protocol `Unit` is always **fully-qualified** inside `ObservationTranslator`.
Quarkmind domain `Unit` is imported normally. Affects `toUnit()`, `toBuilding()`, `toUnitsEnum()`.

### Signature change

```java
// Before:
public static GameState translate(ObservationInterface obs) {
    List<UnitInPool> allUnits = obs.getUnits();
    int minerals  = obs.getMinerals();
    int vespene   = obs.getVespene();
    int foodCap   = obs.getFoodCap();
    int foodUsed  = obs.getFoodUsed();
    long loop     = obs.getGameLoop();   // ObservationInterface.getGameLoop() returns long
}

// After:
public static GameState translate(Observation obs) {
    ObservationRaw raw = obs.getRaw().orElseThrow(); // safe: InterfaceOptions.raw=true guarantees presence
    Set<com.github.ocraft.s2client.protocol.unit.Unit> allUnits = raw.getUnits();
    PlayerCommon common = obs.getPlayerCommon();
    int minerals  = common.getMinerals();
    int vespene   = common.getVespene();
    int foodCap   = common.getFoodCap();
    int foodUsed  = common.getFoodUsed();
    long loop     = obs.getGameLoop();   // Observation.getGameLoop() returns int → widened to long
}
```

`orElseThrow()` is safe because `InterfaceOptions.raw = true` guarantees `ObservationRaw`
is populated. If `joinGame()` ever omits this flag, the crash here surfaces it immediately.

The `u.unit() != null` null guard from the existing `UnitInPool` filter is removed.
`ObservationRaw.getUnits()` builds its set with `.filter(Raw.Unit::hasTag).map(Unit::from)` —
every `Unit` in the set is a fully-constructed value, never null. The SELF/ENEMY filters
simplify to `u.getAlliance() == Alliance.SELF` and `u.getAlliance() == Alliance.ENEMY`.

`toUnitsEnum` signature:
```java
private static Units toUnitsEnum(com.github.ocraft.s2client.protocol.unit.Unit u) {
    return u.getType() instanceof Units enumVal ? enumVal : Units.INVALID;
}
```

---

## `SC2DebugScenarioRunner` Update

`SC2BotAgent.enqueueDebugCommand(Runnable)` → `enqueueDebugCommand(Sc2Api.RequestDebug)`.
Public API (`run(String)` / `availableScenarios()`) unchanged.

### `set-resources-500` — known limitation

The SC2 debug protocol has no exact-value resource setter. `DebugSetUnitValue.UnitValue` =
{LIFE, SHIELDS, ENERGY} — per-unit attributes only. `DebugGameState` values (`minerals`,
`gas`, `all_resources`) are boolean cheat-mode toggles, not exact setters. `all_resources`
maximises both.

The `DebugGameState.all_resources` approximation is the only available mechanism. Comment
updated from `DONE_WITH_CONCERNS` to `KNOWN_LIMITATION`.

---

## Error Handling

**`onGameEnd()` fires unconditionally** — it is in the game loop's `finally` block. Every
exit path (normal, error, or interrupted) triggers it. The table below shows what causes
each exit; the final effect is always `onGameEnd()` + WebSocket close.

| Scenario | Exit cause | `closeWebSocket()` | `onGameEnd()` |
|----------|-----------|-------------------|---------------|
| SC2 fails to start within retry window | `connect()` throws before `runGameLoop()` is called | Not reached | Not fired |
| `requestGameInfo()` fails in preamble | `IOException`/`TimeoutException`; caught; `gameStarted=false` | ✅ via finally | Not fired (`gameStarted=false`) |
| `sendBinary()` future fails | `.join()` throws `CompletionException` (unchecked); caught in `sendSync()`, rethrown as `IOException`; caught by game loop catch | ✅ via finally | ✅ (`gameStarted=true`) |
| `put()` interrupted on WebSocket thread | Interrupt flag restored; message lost; `poll()` throws `InterruptedException` | ✅ via finally | ✅ |
| `responseQueue.poll()` times out (5s) | `TimeoutException`; caught | ✅ via finally | ✅ |
| Proto parse error | `InvalidProtocolBufferException` → `IOException`; caught | ✅ via finally | ✅ |
| SC2 returns error response | `response.getErrorCount() > 0` → `IOException`; caught | ✅ via finally | ✅ |
| Status != `Sc2Api.Status.in_game` | Loop exits normally via break | ✅ via finally | ✅ |
| `quit()` called externally | `InterruptedException` on `poll()`; caught | ✅ via finally | ✅ |
| Port already in use (stale SC2) | Protocol `sc2-stale-process-must-be-killed` | Not reached | Not fired |
| JVM exit without `leaveGame()` | `@PreDestroy` → `quit()` | ✅ via finally | ✅ |

`running = false` fires at the **start** of `finally` in all cases where `runGameLoop()` was
called. `RealSC2Engine.isConnected()` transitions to false immediately, stopping scheduler
dispatches before cleanup completes.

---

## Testing

### Copied from ocraft (MIT licensed)

| File | Package | Purpose |
|------|---------|---------|
| `Fixtures.java` | `com.github.ocraft.s2client.protocol` | Proto builders: `sc2ApiUnit()`, `sc2ApiObservation()`, constants |
| `UnitTest.java` | `com.github.ocraft.s2client.protocol.unit` | Regression guard: `Unit.from()` correctness |
| `ObservationTest.java` | `com.github.ocraft.s2client.protocol.observation` | Regression guard: `Observation.from()` correctness |

### Unchanged existing tests

`ObservationTranslatorTest`, `ActionTranslatorTest`, `SC2BotAgentTerrainTest` — pass without
modification.

### New tests

**`ObservationTranslatorIntegrationTest`** — builds proto objects via ocraft Fixtures, asserts
`ObservationTranslator.translate()` returns correct `GameState`. Covers: alliance filtering,
unit vs building classification, all four resource fields, game loop `int`→`long` widening,
fully-qualified `Unit` naming.

**`QuarkusSC2TransportTest`** — local `ServerSocket` speaking SC2 binary proto. Covers:

- `RequestCreateGame` proto: two `PlayerSetup` entries (Participant + Computer with Race/Difficulty), `realtime` absent
- `RequestJoinGame` includes `InterfaceOptions.raw = true` (verified by inspecting sent bytes)
- `RequestPing` → `ResponsePing` round-trip
- Per-frame sequence: RequestObservation → ResponseObservation → RequestStep → ResponseStep
- Fragment accumulation: split response across `last=false` + `last=true` calls produces correct result
- `SynchronousQueue.put()` pairing: producer blocks until consumer calls `poll()`
- `ByteBuffer` extraction: direct and heap buffers both handled
- Startup timeout (60s) vs per-frame timeout (5s)
- `sendActions()` proto: `ability_id`, `unit_tags`, presence/absence of `target_world_space_pos`
- `Sc2Api.Status.in_game` continues loop; `Sc2Api.Status.ended` causes exit + `onGameEnd()`
- `quit()` called from external thread: game loop receives `InterruptedException`, `onGameEnd()` fires via finally; no concurrent WebSocket send
- `onGameEnd()` fires for all in-loop exit paths (IOException, TimeoutException, normal, interrupt)
- Preamble failure (`requestGameInfo()` throws): WebSocket is closed; `onGameEnd()` NOT fired; `isRunning()` returns false
- `isRunning()` returns false immediately after any game loop exit (before `onGameEnd()`)

No live SC2 required for either test.

---

## Acceptance Criteria

From #185:

- [ ] No patched JARs in `.m2` — `ocraft-s2client-api` removed from `pom.xml`
- [ ] Single Vert.x instance — Quarkus's event loop; no ocraft Vert.x created
- [ ] No RxJava2 dependency in the SC2 transport path
- [ ] `%sc2` smoke test passes: connects, observes, dispatches, shuts down cleanly
- [ ] `NATIVE.md` updated: `ocraft-s2client-api` removed; `ocraft-s2client-protocol` → ✅

---

## Out of Scope

- Native image compilation (#14) — unblocked by this change but not attempted here
- Upgrade of `ocraft-s2client-protocol` past 0.4.21 — separate issue
- Exact player resource setting in `set-resources-500` — permanent SC2 protocol limitation

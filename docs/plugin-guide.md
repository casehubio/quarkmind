# Plugin Developer Guide

This guide covers everything needed to write, test, and deploy a new plugin for the QuarkusMind StarCraft II agent.

---

## What a Plugin Is

A plugin is a CDI bean that implements one of four task seam interfaces:

| Seam | Interface | Concern |
|---|---|---|
| `StrategyTask` | `agent.plugin.StrategyTask` | High-level game plan — what to build, when to attack |
| `EconomicsTask` | `agent.plugin.EconomicsTask` | Resource management — workers, supply, gas |
| `TacticsTask` | `agent.plugin.TacticsTask` | Army execution — how and where to fight |
| `ScoutingTask` | `agent.plugin.ScoutingTask` | Information gathering — where is the enemy |

Each seam interface extends `io.quarkmind.agent.TaskDefinition`. The platform finds and calls your plugin automatically via CDI — no registration or wiring needed.

---

## Anatomy of a Plugin

```java
@ApplicationScoped          // CDI singleton
@CaseType("starcraft-game") // routing key — must be exactly this
public class MyStrategyTask implements StrategyTask {

    @Inject IntentQueue intentQueue;  // inject to queue game commands

    @Override public String getId()   { return "strategy.mine"; }
    @Override public String getName() { return "My Strategy"; }

    // Keys that must be present before this plugin activates
    @Override
    public Set<String> requires() { return Set.of(QuarkMindCaseFile.READY); }

    // Additional gate beyond key presence — CDI beans are available here.
    // Do NOT re-check keys declared in requires() — that is redundant and
    // misrepresents the Phase 2 contract (requires() is evaluated first by the engine).
    // Return ctx -> true when requires() alone is the full gate; override only when
    // a CDI-injected condition (selector, broker state) must also be checked.
    @Override
    public Predicate<CaseContext> activateIf() {
        return ctx -> true;   // requires() already gates on READY
    }

    // Keys this plugin writes to context (documentation only, not enforced)
    @Override
    public Set<String> produces() { return Set.of(QuarkMindCaseFile.STRATEGY); }

    @Override
    public void execute(CaseContext ctx) {
        // 1. Read game state from the context
        int minerals = ctx.getOrDefault(QuarkMindCaseFile.MINERALS, 0);
        List<Unit> workers = ctx.getList(QuarkMindCaseFile.WORKERS, Unit.class);

        // 2. Make decisions and queue intents
        if (minerals >= 150 && hasGateway(ctx)) {
            intentQueue.add(new TrainIntent(nexusTag, UnitType.PROBE));
        }

        // 3. Write reasoning state back to context
        ctx.set(QuarkMindCaseFile.STRATEGY, "MACRO");
    }
}
```

---

## Reading Game State

Game state is available in the `CaseContext` via typed accessor methods. All keys are defined in `QuarkMindCaseFile`.

### Resource keys

| Key constant | Type | Description |
|---|---|---|
| `MINERALS` | `Integer` | Current mineral count |
| `VESPENE` | `Integer` | Current vespene (gas) count |
| `SUPPLY_USED` | `Integer` | Supply currently consumed |
| `SUPPLY_CAP` | `Integer` | Current supply cap |
| `GAME_FRAME` | `Long` | Current game frame (÷22.4 = seconds) |

### Unit and building keys

| Key constant | Type | Description |
|---|---|---|
| `WORKERS` | `List<Unit>` | All Probes |
| `ARMY` | `List<Unit>` | All non-Probe units |
| `MY_BUILDINGS` | `List<Building>` | All player buildings (complete and under construction) |
| `ENEMY_UNITS` | `List<Unit>` | Visible enemy units |

### Agent reasoning keys (written by plugins)

| Key constant | Type | Written by |
|---|---|---|
| `STRATEGY` | `String` | `StrategyTask` — e.g. `"MACRO"`, `"ATTACK"`, `"DEFEND"` |
| `CRISIS` | `String` | Any plugin — signals an emergency condition |

### Reading values

```java
// Scalar — getOrDefault returns the value or a default if absent
int minerals = ctx.getOrDefault(QuarkMindCaseFile.MINERALS, 0);
String strategy = ctx.getOrDefault(QuarkMindCaseFile.STRATEGY, "MACRO");

// Typed list — no cast or @SuppressWarnings needed
List<Unit>     workers   = ctx.getList(QuarkMindCaseFile.WORKERS,      Unit.class);
List<Building> buildings = ctx.getList(QuarkMindCaseFile.MY_BUILDINGS, Building.class);

// Nullable — use getAs() when you need to distinguish absent from zero
Long frame = ctx.getAs(QuarkMindCaseFile.GAME_FRAME, Long.class);
int f = frame != null ? frame.intValue() : 0;

// Presence check
boolean ready = ctx.contains(QuarkMindCaseFile.READY);
```

---

## Queuing Intents

Intents are the only way to command the game. Inject `IntentQueue` and call `add()`:

```java
@Inject IntentQueue intentQueue;

// Train a unit from a building
intentQueue.add(new TrainIntent(nexusTag, UnitType.PROBE));
intentQueue.add(new TrainIntent(gatewayTag, UnitType.ZEALOT));

// Build a structure
intentQueue.add(new BuildIntent(probeTag, BuildingType.PYLON, new Point2d(15, 15)));
intentQueue.add(new BuildIntent(probeTag, BuildingType.GATEWAY, new Point2d(17, 18)));

// Move a unit
intentQueue.add(new MoveIntent(unitTag, new Point2d(50, 50)));

// Attack with a unit
intentQueue.add(new AttackIntent(unitTag, new Point2d(100, 100)));
```

**Tags** are unique string identifiers for each unit or building. Read them from `Unit.tag()` or `Building.tag()`.

**Multiple intents per tick are fine.** They are all dispatched at the end of the tick. In mock mode they are applied to `SimulatedGame`; in replay mode they are recorded but not applied; in real SC2 mode they are sent to the game engine.

---

## Replacing a Plugin

To replace the existing `DroolsStrategyTask` with your own:

1. Create your class implementing `StrategyTask` with `@ApplicationScoped @CaseType("starcraft-game")`
2. Delete (or deactivate) the existing implementation — two active beans for the same seam will cause CDI ambiguity

If you want both to coexist temporarily, use `@io.quarkus.arc.Priority` to prefer one.

---

## Testing a Plugin

Use `CaseFileContext` (already on the test classpath via the poc) to build a context without CDI:

```java
class MyStrategyTaskTest {

    IntentQueue intentQueue;
    MyStrategyTask task;

    @BeforeEach
    void setUp() {
        intentQueue = new IntentQueue();
        task = new MyStrategyTask(intentQueue);
    }

    private CaseContext ctx(Map<String, Object> data) {
        var cf = new InMemoryCaseFileRepository()
            .create("starcraft-game", data, PropagationContext.createRoot());
        return new CaseFileContext(cf);
    }

    @Test
    void activateIf_falseWhenReadyAbsent() {
        assertThat(task.activateIf().test(ctx(Map.of()))).isFalse();
    }

    @Test
    void buildsGatewayWhenReady() {
        CaseContext context = ctx(Map.of(
            QuarkMindCaseFile.MINERALS,     200,
            QuarkMindCaseFile.WORKERS,      List.of(probe("p-0")),
            QuarkMindCaseFile.MY_BUILDINGS, List.of(nexus(), completePylon()),
            QuarkMindCaseFile.READY,        Boolean.TRUE
        ));

        task.execute(context);

        assertThat(intentQueue.pending())
            .anyMatch(i -> i instanceof BuildIntent bi && bi.buildingType() == BuildingType.GATEWAY);
    }
}
```

See `EarlyPressureStrategyTaskMigrationTest` and `BasicEconomicsTaskTest` for full examples.

**Never use `@QuarkusTest` for unit tests that don't need CDI** — the boot cost is significant. Use `@QuarkusTest` only when you need the full CDI context (e.g., testing REST endpoints or the full pipeline).

---

## Domain Model Reference

### UnitType

```
PROBE, ZEALOT, STALKER, IMMORTAL, COLOSSUS, CARRIER,
DARK_TEMPLAR, HIGH_TEMPLAR, ARCHON, OBSERVER, VOID_RAY,
UNKNOWN
```

### BuildingType

```
NEXUS, PYLON, GATEWAY, CYBERNETICS_CORE, ASSIMILATOR,
ROBOTICS_FACILITY, STARGATE, FORGE, TWILIGHT_COUNCIL,
UNKNOWN
```

### Unit record

```java
record Unit(String tag, UnitType type, Point2d position, int health, int maxHealth) {}
```

### Building record

```java
record Building(String tag, BuildingType type, Point2d position,
                int health, int maxHealth, boolean isComplete) {}
```

`isComplete` is `false` while a building is under construction. Most plugins should check this before using a building (e.g., `b.isComplete()` before training from a Gateway).

---

## Execution Order

Plugins all run in the same CaseEngine cycle triggered by `AgentOrchestrator.gameTick()`. The order within a cycle is determined by `requires()` dependency chaining — scouting writes `ENEMY_ARMY_SIZE`, which strategy requires, so scouting always precedes strategy. Do not write to a key that another plugin also writes in the same tick.

Intents queued by multiple plugins in the same tick are all dispatched together at the end of the tick.

---

## Resource Double-Spend

Two plugins can both queue intents that spend the same minerals in one tick (e.g., `BasicEconomicsTask` queues a Pylon and `DroolsStrategyTask` queues a Gateway in the same tick when minerals are just enough for one).

In mock mode, `SimulatedGame` does not enforce mineral costs — both intents are applied. In real SC2 mode, the game engine will reject commands it cannot honour. Budget conservatively: check `minerals >= cost` and leave headroom.

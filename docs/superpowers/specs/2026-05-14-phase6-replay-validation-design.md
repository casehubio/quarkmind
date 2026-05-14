# Phase 6 — Replay-Accurate Forward Simulation
**Date:** 2026-05-14
**Epic:** epic-phase-6
**Issue:** #137

---

## Goal

Validate `EmulatedGame` against real SC2 replay data by feeding the human player's
actual commands into the emulated engine and comparing the resulting state to
`ReplaySimulatedGame` (ground truth) tick by tick. Phase 6 covers the economic
layer only: unit counts, building counts, and resources. Movement and combat
validation are deferred.

---

## Scope

**In scope:**
- Ability ID → Intent extraction from replay `GAME_EVENTS` (`CmdEvent.getAbilLink()`)
- `ReplayValidationHarness`: runs both engines in parallel, produces a `DivergenceReport`
- Regression JUnit test asserting exact unit/building counts and bounded mineral delta
- Report test (`@Tag("report")`) producing a human-readable divergence dump
- Ability mapping covers all three races (Protoss, Terran, Zerg)

**Out of scope (tracked separately):**
- Movement validation
- Combat validation
- Terran and Zerg `EmulatedGame` mechanics (#138)

---

## Architecture

### Refactored: `GameEventStream`

`parse(Path) → List<UnitOrder>` is removed. Replaced with:

```java
static List<Event> events(Path replayPath)   // thin MPQ reader, no extraction logic
static String decodeTag(int rawTag)           // package-level utility, retained
```

All extraction logic moves out. `ReplayEngine.connect()` is updated to call
`ReplayCommandExtractor` instead.

### New: `AbilityMapping` (`sc2/replay/`)

Stateful processor scoped to one player. Owns selection state.

```java
class AbilityMapping {
    AbilityMapping(int playerId)
    void onSelection(SelectionDeltaEvent event)      // updates current selection
    Optional<ReplayCommand> process(CmdEvent event)  // dispatches on abilLink
    void reset()
}
```

`process()` checks `abilLink` first. Known IDs dispatch to move/build/train resolution
using the current selection. Unknown IDs return `Optional.empty()` and are logged at
DEBUG. The static ability ID table is populated empirically via `AbilityDiscoveryTest`
and covers all three races.

### New: `ReplayCommand` (`sc2/replay/`)

```java
sealed interface ReplayCommand permits ReplayCommand.Movement, ReplayCommand.IntentCommand {
    record Movement(UnitOrder order)          implements ReplayCommand {}
    record IntentCommand(TimedIntent intent)  implements ReplayCommand {}
}
```

A given `CmdEvent` produces exactly one or nothing — move commands and ability commands
are mutually exclusive.

### New: `TimedIntent` (`sc2/replay/`)

```java
record TimedIntent(long loop, Intent intent) {}
```

Raw SC2 game loop from `CmdEvent.getLoop()`. Converted to tick by the harness at
`loop / LOOPS_PER_TICK`.

### New: `ReplayCommandStream` (`sc2/replay/`)

```java
record ReplayCommandStream(
    List<UnitOrder>   movementOrders,  // ordered by loop ascending
    List<TimedIntent> intents)         // ordered by loop ascending
{}
```

Both lists are unmodifiable. Consumers iterate sequentially; the harness does not
need random access.

### New: `ReplayCommandExtractor` (`sc2/replay/`)

Static facade — no state, no instantiation:

```java
final class ReplayCommandExtractor {
    static ReplayCommandStream extract(Path replayPath, int playerId)
}
```

Orchestrates `GameEventStream.events()` and `AbilityMapping`. Feeds
`SelectionDeltaEvent`s to `mapping.onSelection()` and `CmdEvent`s to
`mapping.process()`, partitions results into the two lists, returns an unmodifiable
`ReplayCommandStream`.

`ReplayEngine.connect()` replaces `GameEventStream.parse()` with
`ReplayCommandExtractor.extract()`. `commands.movementOrders()` feeds
`game.loadOrders()` as before. `commands.intents()` is available but unused in
observe-only mode.

### New: `ReplayValidationHarness` (`sc2/replay/`)

```java
final class ReplayValidationHarness {
    static DivergenceReport run(Path replayPath, int playerId, int tickLimit)
}
```

Plain static method — no CDI, no framework dependencies.

**Initialization:** constructs `ReplaySimulatedGame` and `EmulatedGame`, calls
`reset()` on both, then asserts starting state match on unit counts, building counts,
minerals, and vespene. Fails fast with a clear message if the replay's initial state
does not match the emulated engine's constants — preventing meaningless divergence
reports from misaligned starts.

**Tick loop:**
1. Collect all `TimedIntent`s whose `loop / LOOPS_PER_TICK == currentTick`
2. Apply each as an `Intent` to `EmulatedGame` via `applyIntent()`
3. Call `replayGame.tick()` and `emulatedGame.tick()`
4. Snapshot both states
5. Record `TickSnapshot` in the report
6. Repeat until `tickLimit` or replay complete

Commands are applied before physics runs — matching SC2 semantics where commands
issued in a frame take effect in that frame's resolution. Tick ordering per
iteration: apply intents → `emulatedGame.tick()` → `replayGame.tick()` → snapshot
both → record divergence.

### New: `DivergenceReport` (`sc2/replay/`)

```java
record DivergenceReport(List<TickSnapshot> ticks, Summary summary) {

    record TickSnapshot(
        int tick,
        int groundTruthUnits,     int emulatedUnits,
        int groundTruthBuildings, int emulatedBuildings,
        int groundTruthMinerals,  int emulatedMinerals,
        int groundTruthVespene,   int emulatedVespene) {}

    record Summary(
        int firstUnitDivergenceTick,      // -1 if none
        int firstBuildingDivergenceTick,  // -1 if none
        int maxMineralDelta,
        int maxVespeneDelta,
        boolean economicallyAccurate) {}  // true iff no unit or building divergence

    String renderReport()  // human-readable dump for the report test
}
```

---

## Test Structure

All new test classes are plain JUnit (no CDI) unless noted.

### `AbilityDiscoveryTest`

Parses representative replays — one per race from `replays/IEM10_Taipei/` plus
`Nothing_4720936.SC2Replay` for Protoss — and prints all observed
`(abilLink, abilCmdIndex, count)` tuples to stdout. Not an assertion test. Runs in
the normal suite permanently as a coverage instrument: if new ability IDs appear in
future replays, they show up here before causing silent no-ops in `AbilityMapping`.

### `AbilityMappingTest`

Verifies `(abilLink, abilCmdIndex)` → `Intent` pairs with hardcoded inputs — no
replay file needed. One test per mapped ability type. Covers: unknown ID returns
`Optional.empty()`, no-selection CmdEvent returns `Optional.empty()`, each Protoss
unit type, each Protoss building type, and representative Terran and Zerg entries.

### `ReplayCommandExtractorTest`

Parses `Nothing_4720936.SC2Replay` and asserts structural properties: intent list
non-empty, all `TimedIntent` loops ≥ 0 and ascending, every `TrainIntent` references
a unit type present in `SC2Data`, every `BuildIntent` has a non-null location.

### `ReplayValidationTest`

Runs harness on `Nothing_4720936.SC2Replay`, player 1, to tick 180 (≈3 minutes).

Assertions at every tick:
- Unit count: exact match (deterministic given correct ability mapping)
- Building count: exact match (deterministic)
- Mineral delta ≤ 100 (mining model is flat-rate vs. real probe saturation curves)

Refs #137.

### `ReplayValidationReportTest` (`@Tag("report")`)

Excluded from default surefire, activated via `mvn test -Preport`. Runs harness on
`Nothing_4720936.SC2Replay` to completion. Calls `report.renderReport()` and writes
to stdout. No assertions.

### `GameEventStreamTest` (updated)

Existing tests updated to match the refactored `events(Path)` signature.

---

## Ability ID Table

The static table in `AbilityMapping` is populated empirically from `AbilityDiscoveryTest`
output before implementing the full mapping. The table maps `(abilLink, abilCmdIndex)` →
`Intent` factory. Coverage target: all units and buildings in `UnitType` and
`BuildingType` for all three races. Gaps are logged at DEBUG and surfaced by
`AbilityDiscoveryTest` runs.

---

## Tolerances

| Metric | Tolerance | Rationale |
|---|---|---|
| Unit count | Exact | Training is deterministic given correct ability mapping |
| Building count | Exact | Build times in `SC2Data` are deterministic |
| Minerals | ≤ 100 delta | Flat mining rate vs. real probe saturation model |
| Vespene | ≤ 50 delta | Flat gas rate vs. real assimilator model |

Tolerances apply at every tick, not just at the end. A divergence that appears and
then self-corrects is still a bug.

---

## Open Questions Resolved

- **Stateful vs. stateless `AbilityMapping`:** stateful — owns selection state.
  Architecturally honest; selection is protocol state, not a parameter.
- **`AbilityMapping` race coverage:** all three races. The table is protocol knowledge,
  not physics knowledge; EmulatedGame's current Protoss-only coverage (#138) does not
  constrain the mapping.
- **Economic vs. movement scope:** economic only for Phase 6. Movement validation
  requires a separate design pass (different problem class, fuzzy tolerances).
- **Harness output:** both a JUnit regression test and a `@Tag("report")` dump,
  sharing the same `ReplayValidationHarness` and `DivergenceReport`.

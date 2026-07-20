# Position-Based Compliance Verification for Coaching — Design Spec

**Issue:** #244 (child of epic #250)
**Date:** 2026-07-20
**Branch:** `issue-244-position-compliance`

## Problem

Coach mode V1 (#230) verifies compliance using unit/building count deltas only. Coaching advice like "retreat your army", "expand at the third", or "position stalkers near the ramp" cannot be verified by counts alone. Position-based verification checks spatial relationships — army centroid movement, expansion placement at expected locations, and unit proximity to map features.

## Domain Model Gaps

SC2 provides map and positional data that our domain model does not yet capture. This spec addresses all gaps relevant to spatial compliance and enumerates the remaining ones.

| Gap | SC2 source | Current state | Addressed here? |
|-----|-----------|---------------|-----------------|
| Player start position | `ResponseGameInfo.startRaw.startLocations` | Not captured | Yes |
| Enemy start position | Deduced from start locations | Not captured | Yes |
| Map dimensions | `ResponseGameInfo.startRaw.mapSize` | Not captured | Yes |
| Map name | `ResponseGameInfo` | Not captured | No — nice-to-have |
| Neutral units (watchtowers, rocks) | `Alliance.NEUTRAL` in observation | `ObservationTranslator` filters them out | Yes — extract from first observation tick alongside resources (see NeutralFeature extraction below) |
| Resources in real SC2 | Neutral units with mineral/geyser type | `ObservationTranslator` passes `List.of()` | Yes — `ObservationTranslator.translate()` widened to extract `Alliance.NEUTRAL` units with mineral/geyser unit types (e.g. `NEUTRAL_MINERAL_FIELD`, `NEUTRAL_VESPENE_GEYSER`) into `Resource` records, populating `geysers` and `mineralPatches` on `GameState` |
| Enemy buildings in real SC2 | Enemy alliance buildings | `ObservationTranslator` passes `List.of()` | No — separate gap |
| Expansion locations | Derived from resource clusters | No concept exists | Yes |
| Ramp positions | Derivable from `TerrainGrid.Height.RAMP` cells | No extraction API | Yes |
| Choke points | Derivable from pathfinding/terrain analysis | No concept exists | No — future work |

## Architecture

### 1. Domain Model Enrichment

#### MapInfo — per-game static data

```java
record MapInfo(
    Point2d playerStart,
    Point2d enemyStart,       // null if unknown (fog)
    int mapWidth,
    int mapHeight,
    List<ExpansionLocation> expansions,
    List<NeutralFeature> neutralFeatures,
    List<Point2d> rampPositions
)
```

Computed once at game start. Carried on `GameState`. `rampPositions` computed from `TerrainGrid.rampPositions()` during MapInfo construction.

#### MapInfo Lifecycle

MapInfo is constructed once per game and threaded to every GameState. Each profile provides it differently:

**Real SC2 (`%sc2` profile):**
1. `SC2BotAgent.onGameStart(ResponseGameInfo)` extracts `startRaw` — caches start locations, map size, and constructs `TerrainGrid` from pathing grid (existing code). MapInfo is NOT constructed yet — resources and neutral features are unavailable until the first observation.
2. On first `onStep()`, resources and neutral features become available from the observation. `SC2BotAgent` constructs `MapInfo` from: cached start locations, terrain-derived ramp positions, resources (expansion derivation via §7), and neutral features. `MapInfo` stored as field on `SC2BotAgent`.
3. On subsequent `onStep()` calls, the cached `MapInfo` is reused. `ObservationTranslator.translate(obs, mapInfo)` (signature widened) receives it.
4. Before MapInfo is constructed (frame 0), `GameState.mapInfo()` is null. No coaching triggers fire at frame 0, so this has no practical impact on compliance verification.

**Emulated SC2 (`%emulated-sc2` profile):**
1. `EmulatedSC2Server.buildGameInfo()` adds start locations to `startRaw` — player start `(8,8)`, enemy start `(56,56)` matching the emulated 64×64 map layout
2. Same `SC2BotAgent.onGameStart()` flow extracts them from `ResponseGameInfo`

**Mock profile (default):**
1. `SimulatedGame` gains a `MapInfo mapInfo` field, populated during `reset()` from the game's resources, geysers, and a fixed emulated terrain
2. `snapshot()` returns `GameState` with `mapInfo` included

#### ExpansionLocation — derived from resource clusters

```java
record ExpansionLocation(int ordinal, Point2d position)
```

Ordinal 0 = main base, 1 = natural, 2 = third, etc. Ordered by distance from player start.

#### NeutralFeature — watchtowers, destructible rocks

```java
record NeutralFeature(String tag, NeutralFeatureType type, Point2d position)

enum NeutralFeatureType {
    XELNAGA_TOWER, DESTRUCTIBLE_ROCK, DESTRUCTIBLE_DEBRIS
}
```

**Extraction:** Neutral features are extracted from the first observation tick alongside resources. `ObservationTranslator` filters `Alliance.NEUTRAL` units by SC2 unit type:

| SC2 unit type | NeutralFeatureType |
|--------------|-------------------|
| `NEUTRAL_XELNAGATOWER` | `XELNAGA_TOWER` |
| `NEUTRAL_DESTRUCTIBLE*` (barrier types) | `DESTRUCTIBLE_ROCK` |
| `NEUTRAL_UNBUILDABLE*` (debris types) | `DESTRUCTIBLE_DEBRIS` |

All other neutral types (mineral fields, geysers, critters) are handled separately as `Resource` records or ignored.

**Immutability:** MapInfo is static — features captured at first tick are retained even if destroyed during the game. This is intentional: watchtower and rock positions are map landmarks. "Position units near the watchtower" remains a valid location reference even if the tower is destroyed, because the map position is still meaningful.

#### GameState widened

Add `MapInfo mapInfo` field. Breaking change to the record constructor — all callers updated.

#### Point2d enriched

Add `static @Nullable Point2d centroidOf(List<? extends Positionable> items)` — returns null for empty list (no centroid computable from zero items), otherwise `Point2d(avgX, avgY)`.

```java
interface Positionable { Point2d position(); }
```

`Unit`, `Building`, and `Resource` implement `Positionable`. Each already has `position()`.

#### UnitType enriched

Add `boolean isWorker()` — returns true for PROBE, SCV, DRONE. Used by `ArmyCentroidMovement` to exclude workers from the army centroid. The existing Protoss-only `u.type() == UnitType.PROBE` filter in `GameStateTranslator` is a pre-existing race-specific bug; `isWorker()` provides the correct cross-race filter.

#### TerrainGrid enriched

Add `List<Point2d> rampPositions()` — scans for `RAMP` cells, clusters adjacent ramp cells, returns centroid of each cluster.

### 2. Sealed Predicate Hierarchy

Replaces the flat verification fields on `CoachingAdvice`.

```java
sealed interface VerificationPredicate permits
    CountDelta,
    ArmyCentroidMovement,
    ExpansionPlacement,
    UnitsNearLocation {

    VerificationPredicate withBaseline(GameState state, LocationResolver resolver);
    boolean isSatisfied(GameState state, LocationResolver resolver);
}
```

Two-phase lifecycle: LLM produces check criteria → `withBaseline()` captures current game state snapshot at commitment creation.

#### CountDelta — existing V1 logic as a predicate

```java
record CountDelta(
    UnitType unitType,          // nullable — one of unitType/buildingType set
    BuildingType buildingType,  // nullable
    int expectedDelta,
    int baselineCount           // captured by withBaseline()
) implements VerificationPredicate
```

- `withBaseline()` → counts matching units/buildings, returns new instance with `baselineCount` set
- `isSatisfied()` → `currentCount - baselineCount >= expectedDelta`

#### ArmyCentroidMovement — retreat/advance detection

```java
record ArmyCentroidMovement(
    MovementDirection direction,      // RETREAT or ADVANCE
    LocationReference referencePoint, // what to move toward/away from
    double minDistance,               // minimum displacement in map units
    Point2d baselineCentroid          // captured by withBaseline()
) implements VerificationPredicate

enum MovementDirection { RETREAT, ADVANCE }
```

- `withBaseline()` → filters `myUnits` by `!u.type().isWorker()`, computes army centroid via `Point2d.centroidOf()`. If the army is empty (all dead or no non-workers), `baselineCentroid` is null.
- `isSatisfied()` → computes current army centroid (same filter). If either `baselineCentroid` or current centroid is null (army empty), returns false — cannot verify movement without an army. Otherwise resolves reference point — if null (R1-06 contract), returns false. Checks distance-to-reference changed by `minDistance` in expected direction. RETREAT = distance increased, ADVANCE = distance decreased.

**Worker filter:** `UnitType.isWorker()` returns true for PROBE, SCV, DRONE — the three race-specific harvester types. These cluster at mineral lines and would severely bias the army centroid. Other non-combat units (OVERLORD, OBSERVER, WARP_PRISM, MULE) are rare enough or mobile enough that their centroid contribution is negligible.

#### ExpansionPlacement — new base near expected expansion

```java
record ExpansionPlacement(
    LocationReference targetExpansion,  // which expansion (ordinal)
    double proximityRadius,             // how close the base must be (default ~5.0)
    Set<String> baselineBaseTags        // captured by withBaseline() — tags of all town halls at baseline
) implements VerificationPredicate
```

- `withBaseline()` → collects tags of all town-hall buildings (NEXUS, COMMAND_CENTER, ORBITAL_COMMAND, PLANETARY_FORTRESS, HATCHERY, LAIR, HIVE) into `baselineBaseTags`
- `isSatisfied()` → any town-hall building NOT in `baselineBaseTags` (i.e. genuinely new) exists within `proximityRadius` of the resolved expansion location

#### UnitsNearLocation — positional check

```java
record UnitsNearLocation(
    UnitType unitType,              // nullable = any non-worker unit (!type.isWorker())
    LocationReference location,
    double radius,
    int minCount
) implements VerificationPredicate
```

- `withBaseline()` → returns `this` unchanged (no baseline needed — point-in-time check)
- `isSatisfied()` → count units of type within `radius` of resolved location >= `minCount`. When `unitType` is null, counts all units where `!type.isWorker()` — consistent with the army centroid filter. Workers mining near a base should not satisfy "have units near the expansion."

### 3. Location Reference

Semantic vocabulary for map positions:

```java
sealed interface LocationReference permits
    PlayerBase,
    EnemyBase,
    MapCenter,
    ExpansionOrdinal,
    NearestRamp,
    Watchtower,
    AbsolutePosition {
}
```

| Permit | Resolves to |
|--------|------------|
| `PlayerBase` | `mapInfo.playerStart()` |
| `EnemyBase` | `mapInfo.enemyStart()` — null if unknown, predicate degrades gracefully |
| `MapCenter` | `Point2d(mapWidth/2, mapHeight/2)` |
| `ExpansionOrdinal(int ordinal)` | `mapInfo.expansions().get(ordinal).position()` |
| `NearestRamp(LocationReference relativeTo)` | Ramp position closest to resolved `relativeTo` |
| `Watchtower(int index)` | Neutral features filtered to `XELNAGA_TOWER`, indexed by distance from player start |
| `AbsolutePosition(float x, float y)` | Raw coordinates — escape hatch for testing |

#### LocationResolver

```java
class LocationResolver {
    /** Returns null when the location cannot be resolved (e.g. EnemyBase under fog, mapInfo unavailable). */
    @Nullable Point2d resolve(LocationReference ref, GameState state) {
        if (ref instanceof AbsolutePosition a) return new Point2d(a.x(), a.y());
        if (state.mapInfo() == null) return null; // MapInfo not yet constructed or construction failed
        return switch (ref) {
            case PlayerBase _ -> state.mapInfo().playerStart();
            case EnemyBase _ -> state.mapInfo().enemyStart(); // null if unknown (fog)
            case MapCenter _ -> mapCenter(state.mapInfo());
            case ExpansionOrdinal e -> expansionByOrdinal(e.ordinal(), state); // null if out of bounds
            case NearestRamp nr -> nearestRamp(resolve(nr.relativeTo(), state), state);
            case Watchtower w -> watchtowerByIndex(w.index(), state); // null if no towers on map
            case AbsolutePosition a -> new Point2d(a.x(), a.y());
        };
    }
}
```

Pattern matching on sealed type gives compile-time exhaustiveness.

**Null contract:** `resolve()` returns null when the underlying data is unavailable: EnemyBase under fog, ExpansionOrdinal beyond the map's expansion count, Watchtower on a map with no towers, NearestRamp when relativeTo resolves to null. `expansionByOrdinal()` and `watchtowerByIndex()` perform bounds checks and return null for out-of-range indices — the LLM doesn't know how many expansions or towers a map has, so out-of-bounds tokens are expected. All predicates that call `resolve()` treat null as "cannot verify" — `isSatisfied()` returns false. This is the graceful degradation path: a predicate that can't resolve its reference location is not satisfied, and the commitment will eventually expire as CHALLENGED.

**`nearestRamp()`** uses `state.mapInfo().rampPositions()` — a pre-computed list of ramp centroids. No dependency on `TerrainGrid` at resolve time.

### 4. CoachingAdvice + OpenCommitment Evolution

#### CoachingAdvice

```java
record CoachingAdvice(
    String advice,
    CoachingDomain domainTag,
    VerificationPredicate verification,   // nullable — null means non-verifiable
    int verificationWindowFrames
) {
    public boolean isVerifiable() { return verification != null; }
}
```

Four previous nullable fields (`verificationUnitType`, `verificationBuildingType`, `verificationCountDelta`, `verificationWindowFrames`) collapse to one. `verificationWindowFrames` stays on `CoachingAdvice` — it governs *when* to check, not *what* to check.

#### OpenCommitment

```java
record OpenCommitment(
    String correlationId,
    CoachingAdvice advice,
    long issuedAtFrame
)
```

`baselineCount` removed — baseline is embedded in the predicate.

#### Baseline capture point

`CoachingChannelBroker.onCoachingCompleted()` is where commitments are created. Changes:

1. `CoachingCompleted` event gains a `GameState triggerState` field — the GameState snapshot from the game tick that triggered the coaching dispatch, NOT the game state at LLM response time. The worker input already contains the trigger-time game state data; `CoachingWorkerFactory.executeCoaching()` reconstructs a `GameState` from the input map and passes it to `CoachingCompleted`.
2. Broker calls `advice.verification().withBaseline(triggerState, locationResolver)` to produce a fully-populated predicate, then stores the result in a new commitment:

```java
var baselined = advice.verification().withBaseline(triggerState, locationResolver);
var newAdvice = new CoachingAdvice(advice.advice(), advice.domainTag(), baselined, advice.verificationWindowFrames());
commitments.put(domain, new OpenCommitment(correlationId, newAdvice, gameFrame));
```

This chain is necessary because `CoachingAdvice`, `OpenCommitment`, and all predicates are Java records (immutable) — `withBaseline()` returns a new predicate instance, requiring new wrapper records up the chain.

**Why trigger-time, not response-time:** LLM latency is 2-5s. For spatial predicates like `ArmyCentroidMovement`, the army can move 6-15 map units in that time. A response-time baseline would capture a partially-moved army, reducing measured displacement and risking false CHALLENGED verdicts. The trigger-time snapshot is the correct reference because it captures the game state that motivated the advice.

### 5. CoachingComplianceEvaluator Evolution

The evaluator delegates to the predicate instead of hardcoding count logic:

```java
public void evaluate(GameState state, long currentFrame) {
    var iterator = commitments.entrySet().iterator();
    while (iterator.hasNext()) {
        var entry      = iterator.next();
        var commitment = entry.getValue();
        var advice     = commitment.advice();

        long windowEnd = commitment.issuedAtFrame() + advice.verificationWindowFrames();
        long expireEnd = commitment.issuedAtFrame() + autoExpireFrames;

        if (!advice.isVerifiable()) {
            if (currentFrame >= windowEnd) {
                recorder.record(commitment.correlationId(), "NEUTRAL", advice);
                iterator.remove();
            }
            continue;
        }

        if (currentFrame >= windowEnd) {
            if (advice.verification().isSatisfied(state, locationResolver)) {
                recorder.record(commitment.correlationId(), "ENDORSED", advice);
                iterator.remove();
            } else if (currentFrame >= expireEnd) {
                recorder.record(commitment.correlationId(), "CHALLENGED", advice);
                iterator.remove();
            }
        }
    }
}
```

Gains `LocationResolver` as a constructor-injected dependency (`@ApplicationScoped` bean). The private `countUnitsOrBuildings()` method is deleted — that logic now lives in `CountDelta.isSatisfied()`.

### 6. LLM Prompt + Parsing Changes

#### Response format

```json
{
  "advice": "Retreat your army toward your natural",
  "domain": "MILITARY",
  "verificationType": "ARMY_CENTROID_RETREAT",
  "verificationParams": { "referenceLocation": "ENEMY_BASE", "minDistance": 8.0 },
  "verificationWindowFrames": 450
}
```

#### Reference semantics

`referenceLocation` is the point distance is measured FROM. `ARMY_CENTROID_RETREAT` means distance to `referenceLocation` increased (army moved away from it). `ARMY_CENTROID_ADVANCE` means distance decreased (army moved toward it). The system prompt must include this definition so the LLM picks the correct reference.

Example: "Retreat your army toward your natural" → `ARMY_CENTROID_RETREAT` + `referenceLocation: ENEMY_BASE` (distance to enemy increased). Equivalently: `ARMY_CENTROID_ADVANCE` + `referenceLocation: PLAYER_BASE` (distance to base decreased). The prompt should instruct the LLM to use the FROM convention: pick the point the army moves AWAY FROM for RETREAT, TOWARD for ADVANCE.

#### Verification type mapping

| verificationType | Predicate | Required params |
|-----------------|-----------|----------------|
| `COUNT_DELTA` | `CountDelta` | `unitType` or `buildingType`, `expectedDelta` |
| `ARMY_CENTROID_RETREAT` | `ArmyCentroidMovement(RETREAT)` | `referenceLocation` (army moves AWAY from this), `minDistance` (default 8.0) |
| `ARMY_CENTROID_ADVANCE` | `ArmyCentroidMovement(ADVANCE)` | `referenceLocation` (army moves TOWARD this), `minDistance` (default 8.0) |
| `EXPANSION_PLACEMENT` | `ExpansionPlacement` | `expansionOrdinal` (required — LLM must specify target, e.g. `1` for natural, `2` for third) |
| `UNITS_NEAR_LOCATION` | `UnitsNearLocation` | `location`, `radius` (default 10.0), `unitType` (optional), `minCount` (default 1) |
| absent/null | null | — non-verifiable |

#### Location tokens

| Token | LocationReference |
|-------|------------------|
| `PLAYER_BASE` | `PlayerBase` |
| `ENEMY_BASE` | `EnemyBase` |
| `MAP_CENTER` | `MapCenter` |
| `EXPANSION_1`, `EXPANSION_2`, ... | `ExpansionOrdinal(n)` |
| `NATURAL` | `ExpansionOrdinal(1)` (alias) |
| `THIRD` | `ExpansionOrdinal(2)` (alias) |
| `NEAREST_RAMP` | `NearestRamp(PlayerBase)` |
| `WATCHTOWER` | `Watchtower(0)` |

#### Backward compatibility (parser-level)

The old `CoachingAdvice` record signature (with `verificationUnitType`, `verificationBuildingType`, `verificationCountDelta` fields) ceases to exist. Backward compatibility is entirely at the parser level: `CoachingWorkerFactory.parseAdvice()` recognises both JSON shapes and always produces the new `CoachingAdvice` with a `VerificationPredicate`. If the LLM returns the old flat format (`verificationUnitType` + `verificationCountDelta` without `verificationType`), the parser constructs a `CountDelta` predicate from those fields. No hard cutover required — the old LLM prompt can coexist with the new parser during migration.

### 7. Expansion Location Derivation

Resource-cluster algorithm, run once at game start:

**Input:** `List<Resource> mineralPatches`, `List<Resource> geysers`, `Point2d playerStart`

**Algorithm:**
1. Collect all resource positions (minerals + geysers)
2. Cluster by proximity — greedy flood-fill: pick an unvisited resource, group all resources within 12.0 map units. Each connected group = one cluster.
3. Compute centroid of each cluster
4. Sort by distance from `playerStart` — ascending
5. Assign ordinals: 0 = main, 1 = natural, 2 = third, etc.

**Why 12.0 radius?** SC2 mineral patches at a base span ~8-10 units; geysers are slightly further. 12.0 captures the full resource group without merging adjacent expansions (typically 30+ units apart).

**Static factory:** `ExpansionLocation.fromResources(minerals, geysers, playerStart)`

**Calibration:** The 12.0 clustering radius is an SC2 physics constant. Validate against IEM10 replay dataset using the same ground-truth methodology as `sc2data-train-times-require-calibration.md` (replay-measured values, not formula-derived). Note: that protocol covers training/build times specifically, not spatial data — a new spatial calibration protocol should be created if further spatial constants emerge. Filed as GitHub issue for tracking.

**Edge cases:**
- No resources → empty expansion list, location predicates degrade to non-verifiable
- Player start unknown → skip ordinal sorting, use raw cluster order

## Testing Strategy

### Unit tests (plain JUnit)

| Test class | Coverage |
|-----------|----------|
| `CountDeltaTest` | V1 count logic in predicate form — `withBaseline()` + `isSatisfied()` |
| `ArmyCentroidMovementTest` | Centroid computation, retreat/advance against reference points, empty army (null centroid → false), single unit, worker filtering via `isWorker()` |
| `ExpansionPlacementTest` | New base near target, unchanged count, base at wrong expansion |
| `UnitsNearLocationTest` | Units within radius, type filtering, no-baseline path |
| `LocationResolverTest` | Each permit resolves correctly, `NearestRamp` composition, null enemy start, out-of-bounds ExpansionOrdinal → null, missing Watchtower → null |
| `ExpansionLocationTest` | Resource clustering — known layouts produce expected ordinals |
| `MapInfoTest` | Construction from resources + terrain, ramp extraction |
| `CoachingComplianceEvaluatorTest` | Existing tests migrated + new spatial predicate tests |
| `CoachingAdviceTest` | Updated record, `isVerifiable()` with predicate model |
| `CoachingWorkerFactoryTest` | `parseAdvice()` new format, backward compat, location tokens; `buildSystemPrompt()` emits new verification types and location token vocabulary |

### Calibration test (benchmark profile)

| Test class | Coverage |
|-----------|----------|
| `ExpansionLocationCalibrationTest` | Validate clustering radius against IEM10 replays — correct expansion count per map |

All spatial predicate tests use explicit `Point2d` positions — deterministic, no randomness.

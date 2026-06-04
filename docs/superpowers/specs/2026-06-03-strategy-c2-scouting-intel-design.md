# Design: StrategyTask C2 — Consume Scouting Intel Instead of Raw ENEMY_UNITS — #169

## Problem

`DroolsStrategyTask.execute()` reads `ENEMY_UNITS` (raw SC2 observation) and feeds the
unit list directly into `StrategyRuleUnit.enemies`. This couples strategy decisions to
the observation layer. ScoutingTask already abstracts enemy state into two CaseFile
keys per tick that strategy should consume:

| Key | Type | Values |
|-----|------|--------|
| `ENEMY_POSTURE` | String | `"ALL_IN"` / `"MACRO"` / `"UNKNOWN"` |
| `TIMING_ATTACK_INCOMING` | boolean | enemy army near Nexus in last 10 s |

**Behavioral change:** strategy was previously driven by raw unit visibility (any enemy
unit currently visible → DEFEND). After this change it is driven by classified scouting
intel. This is intentional — the two layers carry different semantics, and the refactor
makes the dependency explicit.

**Tick-1 benefit:** StrategyTask cannot activate on tick 1 because ENEMY_POSTURE and
TIMING_ATTACK_INCOMING are not present until ScoutingTask has executed at least once.
Previously strategy could run with zero scouting intel.

## UNKNOWN Posture — Two Meanings, One Mostly-Equivalent Behavior

`ENEMY_POSTURE = "UNKNOWN"` arises from two distinct situations:

1. **Pre-contact:** no units have been scouted yet (`unitBuffer` in
   `ScoutingSessionManager` is empty because nothing has been seen)
2. **Post-eviction:** units were scouted, but `evict()` removed events older than
   `UNIT_WINDOW_MS` (3 minutes). Compounding this: `seenUnitTags` is a permanent
   `HashSet` — re-sighting the same tagged unit after eviction does not re-add an event,
   making post-eviction UNKNOWN stickier than pre-contact UNKNOWN.

**Why ATTACK under UNKNOWN is acceptable in the common case:**

The old strategy rule fired ATTACK when `not /enemies` (no currently visible units) and
4+ stalkers. Pre-contact and post-eviction UNKNOWN both represent "no unit events in the
3-minute buffer" — which typically co-occurs with "no enemies currently visible." The
signal is therefore behaviorally equivalent to the old `not /enemies` in the common case.

**Known edge case where equivalence breaks:** `seenUnitTags` stickiness can produce
UNKNOWN posture while units remain visible in the current observation. If a previously-
scouted unit (tag already in `seenUnitTags`) reappears after its event has evicted from
`unitBuffer`, its presence does not re-populate `unitBuffer`, leaving posture at UNKNOWN.
In this state the old rule would see enemies (DEFEND) but the new rule sees UNKNOWN
(ATTACK with 4+ stalkers) — a real divergence. This edge case is accepted as a known
limitation of the current `seenUnitTags`-based de-duplication architecture and is
documented in the protocol below.

**Assumption requiring a protocol (part of #169):** write and commit
`docs/protocols/strategy-attack-under-unknown-posture.md` as part of the #169
implementation. It should document: ATTACK fires under UNKNOWN posture as a deliberate
approximation of the old `not /enemies` signal; this holds while `UNIT_WINDOW_MS` (3
minutes) is long relative to stalker accumulation time AND the `seenUnitTags` stickiness
edge case is not materially frequent. Revisit if `UNIT_WINDOW_MS` is reduced below ~2
minutes or if the seenUnitTags architecture is changed.

## StrategyRuleUnit Changes

**Remove:** `DataStore<Unit> enemies` and `getEnemies()`

**Add:**
```java
private String  enemyPosture         = "UNKNOWN";
private boolean timingAttackIncoming = false;
```

Add getters and setters for both. Plain fields are safe per GE-0053 (only raw `DataStore`
type is loaded by Drools classloader) and are populated before `fire()` so GE-0109 does
not apply.

**`eval()` anti-pattern acknowledged:** both new strategy rules use `eval()`, which
bypasses Rete/PHREAK indexing. Acceptable for a stateless fire-and-discard session.
If sessions ever become stateful, migrate to `DataStore<String>` (see fallback below).

**`enemyArmySize`, `enemyBuildOrder` intentionally omitted** — no rules consume them.
ENEMY_ARMY_SIZE is still read from the CaseFile in `execute()` with `.orElse(0)` for
logging.

## DroolsStrategyTask Changes

**Entry criteria:** `{READY, ENEMY_POSTURE, TIMING_ATTACK_INCOMING}`

ENEMY_POSTURE and TIMING_ATTACK_INCOMING are both produced by DroolsScoutingTask in the
same tick — adding ENEMY_ARMY_SIZE provides no additional ordering guarantee and would
mislead consumers about actual task dependencies.

**`execute()` reads from CaseFile:**
- Remove: `ENEMY_UNITS`
- Keep: `ENEMY_ARMY_SIZE` with `.orElse(0)` — for logging only, not a declared dependency
- Add: `ENEMY_POSTURE` (String, `.orElse("UNKNOWN")`)
- Add: `TIMING_ATTACK_INCOMING` (Boolean, `.orElse(false)`)

**`buildRuleUnit()` signature:** remove `enemies` parameter; add `posture` (String) and
`timingAttack` (boolean). Parameters `workers`, `army`, `buildings`, `geysers` are
unchanged.

Remove the C2 stub comment from `execute()`.

**Log statement:** replace `enemies(scouted)=%d` with `posture=%s | timing=%b | armySize=%d`.

**`ENEMY_UNITS` after the change:** `GameStateTranslator` still writes it; ScoutingTask
still reads it. No dangling key.

## Strategy Rules — `StarCraftStrategy.drl`

**Remove** the stale comment "DEFEND and ATTACK are mutually exclusive via the enemies
check" and the two existing strategy assessment rules.

**Replace with:**

```drl
// ---------------------------------------------------------------------------
// Strategy Assessment Rules (salience 200..150)
// Driven by scouting-derived intel, not raw unit visibility.
// DEFEND requires explicit threat classification (ALL_IN posture or timing attack).
// ATTACK fires when no threat signal — including UNKNOWN posture, which is treated
// as equivalent to the old "not /enemies" signal. See protocol:
// docs/protocols/strategy-attack-under-unknown-posture.md
// Default is "MACRO" (empty strategyDecisions list).
// ---------------------------------------------------------------------------

rule "Strategy: Defend"
    salience 200
when
    eval(timingAttackIncoming || enemyPosture.equals("ALL_IN"))
then
    strategyDecisions.add("DEFEND");
end

rule "Strategy: Attack"
    salience 150
when
    eval(!timingAttackIncoming && !enemyPosture.equals("ALL_IN"))
    accumulate(
        /army[ this.type() == UnitType.STALKER ];
        $count : count();
        $count >= 4
    )
then
    strategyDecisions.add("ATTACK");
end
```

**`eval()` + `accumulate()` compilation:** verify by running
`mvn test -Dtest=DroolsStrategyTaskTest` — Quarkus compiles DRL at build time; any
error fails the build before tests run.

**If `eval()` + `accumulate()` does not compile — DataStore fallback:**

Replace `String enemyPosture` and `boolean timingAttackIncoming` in `StrategyRuleUnit`
with:

```java
private final DataStore<String>  postureStore = DataSource.createStore();
private final DataStore<Boolean> timingStore  = DataSource.createStore();
```

Update `buildRuleUnit()` to insert the values:
```java
postureStore.add(posture);
timingStore.add(timingAttack);
```

Both rules need updating:

```drl
rule "Strategy: Defend"
    salience 200
when
    /timingStore[ this == true ]                 // timing attack
    or
    /postureStore[ this.equals("ALL_IN") ]       // all-in posture
then
    strategyDecisions.add("DEFEND");
end
```

Note: DRL `or` fires the rule once per matching branch, so if both conditions hold,
"DEFEND" is added twice. `findFirst()` in `DroolsStrategyTask.execute()` handles this —
but it is worth leaving a comment on the `or` clause.

```drl
rule "Strategy: Attack"
    salience 150
when
    not /timingStore[ this == true ]
    not /postureStore[ this.equals("ALL_IN") ]
    accumulate(
        /army[ this.type() == UnitType.STALKER ];
        $count : count();
        $count >= 4
    )
then
    strategyDecisions.add("ATTACK");
end
```

The guard-flag approach (writing a boolean field in one rule's consequence and reading
it via `eval()` in another rule's condition within the same `fire()` call) is **not** a
valid fallback — plain field writes in a rule consequence are not change events and will
not trigger agenda re-evaluation in the same firing cycle (GE-0109).

## BasicStrategyTask — Delete

`BasicStrategyTask` has no CDI annotations — it is not a CDI bean and is never injected.
After this change its `assessStrategy()` logic (DEFEND when enemies visible from raw
ENEMY_UNITS) diverges permanently from the C2 architecture.

Delete `BasicStrategyTask.java` and `BasicStrategyTaskTest.java`.

**`DroolsStrategyTaskTest` Javadoc:** currently reads "Logic coverage for the same rules
is also exercised by BasicStrategyTaskTest." Remove this sentence when deleting the class.

**Coverage audit:** `BasicStrategyTaskTest` contains one test absent from
`DroolsStrategyTaskTest`: `doesNotBuildGatewayIfPylonIsUnderConstruction` — verifying
an incomplete Pylon (`isComplete() == false`) does not trigger a Gateway build. This is
distinct from `doesNotBuildGatewayWithoutPylon`. Add it to `DroolsStrategyTaskTest`.

## Tests — `DroolsStrategyTaskTest`

### Helper signature

```java
private CaseFile caseFile(int minerals, int vespene, List<Unit> workers,
                           List<Building> buildings,
                           String enemyPosture, boolean timingAttack)
```

Sets `ENEMY_POSTURE`, `TIMING_ATTACK_INCOMING`. Sets `ENEMY_ARMY_SIZE = 0` (not a
dependency, harmless to include for log readability). Removes `ENEMY_UNITS`. All existing
build/train tests pass `"UNKNOWN", false` for the new parameters.

### canActivate test matrix

**Delete these two existing tests:**
- `droolsStrategyTaskRequiresEnemyArmySizeToActivate` — ENEMY_ARMY_SIZE is no longer an
  entry criterion; leaving this test would produce a contradictory assertion
- `droolsStrategyTaskActivatesWhenEnemyArmySizePresent` — same reason

**Replace with** (entry criteria: `{READY, ENEMY_POSTURE, TIMING_ATTACK_INCOMING}`):

| Test | Missing key | Expected |
|------|-------------|----------|
| `canActivate_allKeysPresent` | — | `true` |
| `canActivate_readyAbsent` | READY | `false` |
| `canActivate_enemyPostureAbsent` | ENEMY_POSTURE | `false` |
| `canActivate_timingAttackAbsent` | TIMING_ATTACK_INCOMING | `false` |

### Strategy assessment tests

Replace the three existing strategy assessment tests with:

| Test name | posture | timing | stalkers | expected |
|-----------|---------|--------|----------|----------|
| `strategyIsMacroWhenNoIntelAndNoArmy` | UNKNOWN | false | 0 | MACRO |
| `strategyIsDefendWhenAllInPosture` | ALL_IN | false | 0 | DEFEND |
| `strategyIsDefendWhenTimingAttackIncoming` | UNKNOWN | true | 0 | DEFEND |
| `strategyIsDefendWhenTimingAttackIncomingWithStalkers` | UNKNOWN | true | 4 | DEFEND |
| `strategyIsDefendNotAttackWhenAllInPostureWithStalkers` | ALL_IN | false | 4 | DEFEND |
| `strategyIsAttackWhenMacroPostureAndEnoughStalkers` | MACRO | false | 4 | ATTACK |
| `strategyIsAttackWhenUnknownPostureAndEnoughStalkers` | UNKNOWN | false | 4 | ATTACK |
| `strategyIsMacroWhenBelowAttackThresholdWithMacroPosture` | MACRO | false | 3 | MACRO |

The timing-with-stalkers test (`UNKNOWN, true, 4 → DEFEND`) documents the intended DEFEND
priority when both ATTACK and DEFEND conditions appear to compete.

The 3-stalker boundary test verifies the accumulate threshold — especially valuable given
the `eval()` + `accumulate()` combination is flagged for compilation verification.

### Additional build/train test to add

`doesNotBuildGatewayWhenPylonIsUnderConstruction` — buildings list contains an incomplete
Pylon (`isComplete() == false`); assert no GATEWAY `BuildIntent` is emitted. Migrated
from `BasicStrategyTaskTest` before deletion.

### FullMockPipelineIT

Line 45 comment update:
```java
// Strategy should be DEFEND (ALL_IN posture from scouting); economics trains probe (50 minerals available)
```

No test logic changes required.

## Coherence Check

- Protocol `plugin-canactivate-override-required.md`: `canActivate()` override retained.
- Protocol `nearest-threat-conditional-write.md`: not affected.
- GE-0053: plain String/boolean fields safe in RuleUnit.
- GE-0109: fields written before `fire()` — no re-evaluation issue for primary path.
  Guard-flag fallback explicitly ruled out for the same reason.
- PLATFORM.md: no capability ownership change.

# Layer 3 Dual-Stack Redesign — Broker + Qhorus Advisory

**Issues:** #177, #178, #179, #176
**Branch:** issue-177-layer3-scouting-followon
**Date:** 2026-06-08
**Supersedes:** `2026-06-06-qhorus-layer3-design.md` (delivery mechanism only)
**Review applied:** 2026-06-08 iterations 1–5 — 2 blockers per iteration 1–2; iterations 3–5 closed specification gaps and test design issues

---

## Problem

Layer 3 (#155) introduced a Qhorus channel for scouting intel delivery to `DroolsTacticsTask`.
The channel delivery was the right tool for the wrong job: Qhorus is designed for cross-process,
async, audited agent communication. Game-loop plugin coordination is in-process, sequential, and
requires zero lag.

Consequences: a mandatory 1-tick delivery lag (async `onMessage()` fires after the tick that
produced the intel), JPA writes on every intel change (Qhorus persists every `MessageLedgerEntry`),
and JSON serialize/deserialize on a 22.4 fps hot path.

The Qhorus channel IS the right tool for a different consumer class: LLM advisors (#180) and
the Commentator/Coach (#181) operating at a different timescale from the game loop. The problem
was not Qhorus itself — it was using it for both in-process plugin coordination AND advisory
delivery. These have incompatible requirements and must be separated.

`DroolsTacticsTask` was also the sole `ScoutingIntelConsumer`. `DroolsStrategyTask` and
`FlowEconomicsTask` had no access to typed intel from scouting, leaving them reading untyped
CaseFile keys — the coupling Layer 3 was supposed to eliminate.

---

## Architecture — Dual Stack

Two parallel delivery paths serve different consumers with different requirements.

### Stack 1 — Broker (in-process, synchronous, zero-lag)

```
DroolsScoutingTask.execute()
  → broker.update(ScoutingIntelPayload)        // same game-loop thread, no serialisation

DroolsTacticsTask.execute()    → broker.current(THREAT_POSITION, ThreatPosition.class)
DroolsStrategyTask.execute()   → broker.current(POSTURE, PostureUpdate.class)
                               → broker.current(TIMING_ALERT, TimingAlert.class)
FlowEconomicsTask.execute()    → broker.current(ARMY_SIZE, ArmySize.class)
```

`ScoutingIntelBroker` becomes a typed in-memory store. Plugins read the latest value per type at
execute time — no serialisation, no persistence, no cross-thread delivery lag.

The broker retains ownership of the subscription union (`activeTypes()`) — this drives the CEP
gate in `DroolsScoutingTask`: only run Drools when a plugin or advisory path subscribes to a
CEP-derived type.

### Stack 2 — Qhorus advisory channel (async, LLM-facing)

```
DroolsScoutingTask.execute()
  → dispatchToAdvisory(payload)               // Qhorus STATUS message, unconditional

Future LLM advisors (#180)      → MessageObserver on quarkmind-scouting-intel
Future Commentator/Coach (#181) → MessageObserver on quarkmind-scouting-intel
```

The Qhorus channel survives intact. Its purpose is now explicit: advisory consumers operating at
a different timescale from the game loop. `DroolsTacticsTask` is no longer a `MessageObserver`;
plugins do not subscribe to the channel.

### Advisory delivery gate — complete specification

The advisory dispatch and the plugin subscription union serve different concerns and must not
gate each other. `advisoryEnabled` is a `@ConfigProperty` owned exclusively by
`DroolsScoutingTask` (not `ScoutingIntelBroker`). The broker has no knowledge of advisory
delivery.

**CEP gate** (`needsCep` in `DroolsScoutingTask`) — runs Drools when any plugin subscribes
to a CEP-derived type OR advisory is enabled:

```java
boolean needsCep = broker.isSubscribed(BUILD_ORDER)
                || broker.isSubscribed(TIMING_ALERT)
                || broker.isSubscribed(POSTURE)
                || advisoryEnabled;
```

**Outer guards for passive intel types** — THREAT_POSITION and ARMY_SIZE are not CEP-derived
(computed unconditionally from `enemies`). Their dispatch guards must also be advisory-aware:

```java
// THREAT_POSITION — was: broker.isSubscribed(THREAT_POSITION)
if (nearest != null
        && (broker.isSubscribed(ScoutingIntelType.THREAT_POSITION) || advisoryEnabled)
        && shouldDispatchThreatPosition(prevThreatPos, nearest, minThreatDistance)) {
    prevThreatPos = nearest;
    publishIntel(new ScoutingIntelPayload.ThreatPosition(nearest));
}

// ARMY_SIZE — was: broker.isSubscribed(ARMY_SIZE)
if ((broker.isSubscribed(ScoutingIntelType.ARMY_SIZE) || advisoryEnabled)
        && shouldDispatchArmySize(prevArmySize, currentArmySize, minArmySizeDelta)) {
    prevArmySize = currentArmySize;
    publishIntel(new ScoutingIntelPayload.ArmySize(currentArmySize));
}
```

Without these changes, an LLM advisor with zero plugin subscribers receives no positional data,
regardless of `advisoryEnabled`. The CEP types (POSTURE, TIMING_ALERT, BUILD_ORDER) are already
covered by the `advisoryEnabled` addition to `needsCep`.

**`publishIntel()` — always calls advisory:**

```java
private void publishIntel(ScoutingIntelPayload payload) {
    if (broker.isSubscribed(payload.type())) {
        broker.update(payload);       // Stack 1: in-memory, for plugins
    }
    dispatchToAdvisory(payload);      // Stack 2: Qhorus, always
}
```

`dispatchToAdvisory()` is unconditional within `publishIntel()`. The advisory gate is fully at
the outer guard level, not inside `publishIntel()`.

`advisoryEnabled` defaults to `true` — the advisory channel always exists and may have LLM
consumers. Set to `false` to reduce Qhorus writes in environments where no advisory consumers
will ever connect (e.g. replay analysis, benchmarking).

---

## ScoutingIntelPayload — type() method added

`ScoutingIntelPayload` gains a `type()` method on the sealed interface, implemented by each
record. This eliminates the need for a dispatch switch elsewhere and enables compile-safe typed
access at all call sites. The `permits` clause and record fields are unchanged.

```java
public sealed interface ScoutingIntelPayload
        permits ScoutingIntelPayload.ThreatPosition, ScoutingIntelPayload.PostureUpdate,
                ScoutingIntelPayload.TimingAlert, ScoutingIntelPayload.ArmySize,
                ScoutingIntelPayload.BuildOrder {

    ScoutingIntelType type();

    record ThreatPosition(Point2d position) implements ScoutingIntelPayload {
        public ScoutingIntelType type() { return ScoutingIntelType.THREAT_POSITION; }
    }
    record PostureUpdate(String posture) implements ScoutingIntelPayload {
        public ScoutingIntelType type() { return ScoutingIntelType.POSTURE; }
    }
    record TimingAlert(boolean incoming) implements ScoutingIntelPayload {
        public ScoutingIntelType type() { return ScoutingIntelType.TIMING_ALERT; }
    }
    record ArmySize(int count) implements ScoutingIntelPayload {
        public ScoutingIntelType type() { return ScoutingIntelType.ARMY_SIZE; }
    }
    record BuildOrder(String detected) implements ScoutingIntelPayload {
        public ScoutingIntelType type() { return ScoutingIntelType.BUILD_ORDER; }
    }
}
```

Adding a new payload variant without implementing `type()` is a compile error. The existing
protocol `GE-20260418-9b272f` (sealed-type exhaustiveness) governs this: adding a new variant
requires updating every switch over `ScoutingIntelPayload` and `ScoutingIntelType` in the
same commit.

---

## Component Changes

### `ScoutingIntelBroker` — typed in-memory store added

New fields and methods alongside existing channel/subscription machinery:

```java
// Typed in-memory store — synchronous game-loop writes; ConcurrentHashMap for QA endpoint reads
private final Map<ScoutingIntelType, ScoutingIntelPayload> latest = new ConcurrentHashMap<>();

// Initialized to empty set at field declaration — isSubscribed() is safe before @PostConstruct.
// @PostConstruct overwrites with the real subscription union from CDI consumers.
// Set.of() initial value = zero subscriptions before wiring; no intel dispatched to broker yet.
private volatile Set<ScoutingIntelType> activeTypes = Set.of();

// @Inject PreferenceProvider preferenceProvider — added for refreshAll()

// Called by DroolsScoutingTask when a value changes (subscribed types only)
public void update(ScoutingIntelPayload payload) {
    latest.put(payload.type(), payload);
}

// Untyped read
public Optional<ScoutingIntelPayload> current(ScoutingIntelType type) {
    return Optional.ofNullable(latest.get(type));
}

// Typed read — compile-safe; no cast at call sites
@SuppressWarnings("unchecked")
public <T extends ScoutingIntelPayload> Optional<T> current(
        ScoutingIntelType type, Class<T> clazz) {
    return Optional.ofNullable(latest.get(type))
        .filter(clazz::isInstance)
        .map(clazz::cast);
}

// Clears all stored intel on game restart
void onGameStarted(@Observes GameStarted event) { latest.clear(); }

// Package-private reset for @QuarkusTest isolation — same pattern as computeActiveTypes()
void clearLatest() { latest.clear(); }

// Hot-reload subscription union (#178) — called from QA endpoint on HTTP thread
public void refreshAll() {
    Preferences prefs = preferenceProvider.resolve(SettingsScope.root());
    consumers.forEach(c -> c.refreshSubscriptions(prefs));
    activeTypes = computeActiveTypes(consumers);
}
```

`ScoutingIntelBroker` does **not** hold `advisoryEnabled` — advisory delivery is
`DroolsScoutingTask`'s concern. The broker manages subscriptions and the in-memory store only.

`activeTypes = Set.of()` at field declaration means `new ScoutingIntelBroker()` in plain JUnit
(bypassing `@PostConstruct`) is safe: `isSubscribed()` returns `false` rather than NPE-ing.
`latest` is also initialized inline. `update()` and `current()` work without CDI.

Existing: channel creation, `channelId()`, `isSubscribed()`, `activeTypes()`,
`computeActiveTypes()` — unchanged.

### `DroolsScoutingTask` — dual dispatch + advisory gate

`dispatch(ScoutingIntelPayload)` renamed `publishIntel(ScoutingIntelPayload)` and split (see
Architecture section above). `advisoryEnabled` config property added:

```java
@Inject
@ConfigProperty(name = "quarkmind.scouting.advisory.enabled", defaultValue = "true")
boolean advisoryEnabled;
```

`dispatchToAdvisory()` contains the existing `messageService.dispatch()` logic.

Two outer guards updated with `|| advisoryEnabled` (THREAT_POSITION and ARMY_SIZE — see
Architecture section above for complete code).

`needsCep` gate updated with `|| advisoryEnabled`.

### `DroolsTacticsTask` — broker reader, no longer Qhorus subscriber

**`@Inject` constructor — broker added as third parameter:**

```java
@Inject
public DroolsTacticsTask(RuleUnit<TacticsRuleUnit> ruleUnit,
                         IntentQueue intentQueue,
                         ScoutingIntelBroker broker) {
    this.ruleUnit    = ruleUnit;
    this.intentQueue = intentQueue;
    this.broker      = broker;
}
```

Constructor injection makes the dependency explicit and testable without CDI. Unit tests create
`new ScoutingIntelBroker()` — `@PostConstruct` is skipped, but both `latest` and `activeTypes`
are initialized at field declaration (`latest = new ConcurrentHashMap<>()`,
`activeTypes = Set.of()`). `update()`, `current()`, and `isSubscribed()` all work correctly
without CDI.

**Removed:**
- `implements MessageObserver`
- `onMessage(MessageReceivedEvent)` and `channels()`
- `AtomicReference<TacticsIntelCache> intelCache` — `TacticsIntelCache.java` deleted
- `static TacticsIntelCache merge(...)` and all tests for it
- `TacticsIntelCache currentIntelCache()` test accessor
- `void onGameStarted(@Observes GameStarted)` — broker clears instead
- `@Inject ObjectMapper objectMapper`

**`canActivate()` — updated:**

```java
@Override public boolean canActivate(CaseFile caseFile) {
    return entryCriteria().stream().allMatch(caseFile::contains)
        && broker.current(ScoutingIntelType.THREAT_POSITION).isPresent();
}
```

**`execute()` — reads from broker (typed, no cast):**

```java
Point2d threat = broker.current(ScoutingIntelType.THREAT_POSITION,
        ScoutingIntelPayload.ThreatPosition.class)
    .map(ScoutingIntelPayload.ThreatPosition::position)
    .orElse(null);
```

`initSubscriptions(Preferences)` → renamed `refreshSubscriptions(Preferences)` (public,
implements interface method). `@PostConstruct init()` calls `refreshSubscriptions(prefs)`.

`TacticsIntelCache.java` — **deleted**.

### `ScoutingIntelConsumer` — hot-reload hook (#178)

```java
public interface ScoutingIntelConsumer {
    Set<ScoutingIntelType> subscribedIntelTypes();
    default void refreshSubscriptions(Preferences prefs) {}
}
```

### `ScoutingIntelPreferences` — per-consumer default override (#177)

New overload for consumers whose default differs from the global:

```java
public static PreferenceKey<ScoutingIntelPreference> consumerKey(
        String pluginId, ScoutingIntelType type, boolean defaultEnabled)
```

`FlowEconomicsTask` uses this for `ARMY_SIZE` (global default is `false`; economics wants
`true` by default).

---

## New Consumers (#177)

### `DroolsStrategyTask` — implements `ScoutingIntelConsumer`

**Subscriptions:** `POSTURE` (default true), `TIMING_ALERT` (default true).

BUILD_ORDER is **not** subscribed. `StrategyRuleUnit` has no `buildOrderStore` — subscribing
would produce dead code in `execute()`. BUILD_ORDER is deferred until the Drools strategy rules
explicitly consume it.

**`entryCriteria()`** — updated to `{READY, ENEMY_ARMY_SIZE}`:

- `READY` — game state populated
- `ENEMY_ARMY_SIZE` — scouting ordering dependency (L5 invariant, ARC42STORIES §L5). Scouting
  always writes this key (even as 0), so strategy always runs after scouting in the CaseEngine
  re-evaluation loop. `ENEMY_POSTURE` and `TIMING_ATTACK_INCOMING` removed from `entryCriteria()`
  — they are observability-only CaseFile writes; strategy reads the same intel from the broker.

**Code alignment with ARC42STORIES §L5:** The documented L5 dependency graph already shows
`{READY, ENEMY_ARMY_SIZE}` for Strategy. After #169, the code diverged by adding `ENEMY_POSTURE`
and `TIMING_ATTACK_INCOMING` to `entryCriteria()`. This redesign restores alignment with the
documented graph — the code was the source of divergence, not ARC42STORIES.

**`canActivate()`:**

```java
@Override public boolean canActivate(CaseFile caseFile) {
    return entryCriteria().stream().allMatch(caseFile::contains)
        && broker.current(ScoutingIntelType.POSTURE).isPresent();
}
```

The CaseEngine re-evaluation loop (ARC42STORIES §L5: "evaluates criteria after each task
completes; newly written keys can unlock blocked tasks in the same tick") ensures that after
scouting writes `ENEMY_ARMY_SIZE` and calls `broker.update(PostureUpdate)`, both gates are
satisfied in the same re-evaluation pass. `ENEMY_ARMY_SIZE` is the explicit documented ordering
mechanism; the broker check is the intel-availability gate.

**`execute()` — reads from broker (typed):**

```java
String posture = broker.current(ScoutingIntelType.POSTURE,
        ScoutingIntelPayload.PostureUpdate.class)
    .map(ScoutingIntelPayload.PostureUpdate::posture).orElse("UNKNOWN");
boolean timing = broker.current(ScoutingIntelType.TIMING_ALERT,
        ScoutingIntelPayload.TimingAlert.class)
    .map(ScoutingIntelPayload.TimingAlert::incoming).orElse(false);
// ENEMY_ARMY_SIZE: still read from CaseFile (soft read, no gate — unchanged)
int armySize = caseFile.get(QuarkMindCaseFile.ENEMY_ARMY_SIZE, Integer.class).orElse(0);
```

`DroolsStrategyTask` constructor gains `ScoutingIntelBroker broker` as third parameter (same
pattern as DroolsTacticsTask). Note: `DroolsStrategyTask` requires `@QuarkusTest` due to
`DataSource.createStore()` being initialised by the Quarkus Drools extension at build time
(ARC42STORIES §12) — broker access in tests uses `@Inject ScoutingIntelBroker broker`.

### `FlowEconomicsTask` — implements `ScoutingIntelConsumer`

**Subscriptions:** `ARMY_SIZE` (default `true` via new `consumerKey` overload).

**Broker and subscription infrastructure** — field injection, consistent with the existing
`@Channel` emitter pattern (no constructor; `@Channel` requires CDI, no unit-test-isolation
argument). `FlowEconomicsTask` currently has no subscription infrastructure at all; it must be
added in full:

```java
@Inject ScoutingIntelBroker broker;
@Inject PreferenceProvider preferenceProvider;

// Safe default — isSubscribed() returns false before @PostConstruct fires
Set<ScoutingIntelType> subscribedTypes = Set.of();

@PostConstruct
void init() {
    refreshSubscriptions(preferenceProvider.resolve(SettingsScope.root()));
}

@Override
public void refreshSubscriptions(Preferences prefs) {
    // Economics subscribes to ARMY_SIZE only; default true via the new consumerKey overload
    boolean wantsArmySize = prefs.getOrDefault(
        ScoutingIntelPreferences.consumerKey(getId(), ScoutingIntelType.ARMY_SIZE, true)
    ).asBoolean();
    subscribedTypes = wantsArmySize ? Set.of(ScoutingIntelType.ARMY_SIZE) : Set.of();
}

@Override
public Set<ScoutingIntelType> subscribedIntelTypes() { return subscribedTypes; }
```

Without `@PostConstruct`, `subscribedIntelTypes()` returns `Set.of()` at broker construction
time — `activeTypes` never includes `ARMY_SIZE`, and `broker.update()` is never called for it.

**`execute()` — reads army size from broker, passes to tick:**

```java
int armySize = broker.current(ScoutingIntelType.ARMY_SIZE,
        ScoutingIntelPayload.ArmySize.class)
    .map(ScoutingIntelPayload.ArmySize::count).orElse(0);
GameStateTick tick = new GameStateTick(/* existing fields */, armySize);
```

**`GameStateTick`** — new final field `int enemyArmySize` added.

**`EconomicsDecisionService`** and **`EconomicsFlow`** — updated to use `enemyArmySize` for
probe-vs-army balance decisions.

**Test callsite impact — explicit:**

`GameStateTick` constructor gains one parameter. All construction sites:

| File | Method | Usages |
|------|--------|--------|
| `EconomicsDecisionServiceTest` | `tick()` helper (L203) | 15 test methods |
| `EconomicsDecisionServiceTest` | `tickWithGas()` helper (L212) | 4 test methods |
| `EconomicsFlowTest` | `tick()` helper (L136) | 5 test methods |
| `EconomicsFlowTest` | `tickWithGas()` helper (L145) | 1 test method |
| `FlowEconomicsTask.execute()` | production call site | 1 |

All helpers add `int enemyArmySize` as final parameter, passing `0` where army size is
irrelevant to the test's concern.

---

## Hot-Reload (#178)

Two separate QA endpoints. Keeping them separate avoids a circular dependency
(`ScoutingIntelBroker` → `DroolsScoutingTask`):

**`POST /qa/scouting/subscriptions/reload`** — calls `broker.refreshAll()`
- Re-resolves preferences, calls `consumer.refreshSubscriptions(prefs)` on each consumer
- Recomputes `activeTypes` — next tick's CEP gate reflects the updated union
- Scoped to subscription filtering only

**`POST /qa/scouting/thresholds/reload`** — calls `scoutingTask.refreshThresholds(prefs)`
- Re-resolves `minThreatDistance`, `minArmySizeDelta`, dispatch enabled flags
- `DroolsScoutingTask.refreshThresholds(Preferences)` extracted from `initThresholds()`

Both are `@UnlessBuildProfile("prod")`. Both operate synchronously on the HTTP thread between
game ticks.

---

## NEAREST_THREAT Removal (#179)

### Production classes affected

Three classes reference `QuarkMindCaseFile.NEAREST_THREAT` in `src/main/`:

**`DroolsScoutingTask`** — writes the key (both `caseFile.put()` and `producedKeys()`).
Remove both. No behaviour change elsewhere.

**`BasicScoutingTask`** (`@Alternative @ApplicationScoped @CaseType`) — references in
`producedKeys()` and `execute()`. Never active in CDI but compiled. Remove from both.

**`BasicTacticsTask`** (no CDI annotations, direct-instantiation-only test class) — references in
`entryCriteria()` and `execute()`. Both must be removed:

- `entryCriteria()`: new set `{READY, STRATEGY}`. NEAREST_THREAT was the conditional CaseFile
  gate (written only when enemies present). After redesign, DroolsTacticsTask uses the broker
  gate. BasicTacticsTask is a legacy reference implementation; it loses the conditional gate
  entirely. `canActivate()` activates on `{READY, STRATEGY}` — consistent with what the active
  `DroolsTacticsTask` uses for its CaseFile half of `canActivate()`.

- `execute()` line 79: delete `Point2d nearestThreat = caseFile.get(NEAREST_THREAT, ...).orElse(null)`.
  Replace call `executeAttack(army, nearestThreat)` with `executeAttack(army, null)`.
  `executeAttack()` already null-guards: `Point2d target = nearestThreat != null ? nearestThreat : MAP_CENTER`.
  **Behavioural consequence:** BasicTacticsTask permanently uses MAP_CENTER as the attack target.
  This is acceptable — BasicTacticsTask is never CDI-active; it exists as a historical reference.

Delete `NEAREST_THREAT` constant from `QuarkMindCaseFile` last, after all references are removed.

### BasicTacticsTaskTest — complete update required

The test file has two tests that will not compile after NEAREST_THREAT is removed, and one that
tests now-changed behaviour:

**`tacticsTaskRequiresNearestThreatToActivate()` (L115–122):**
Rewrite as `canActivate_false_withoutStrategy()` — verify `{READY}` alone does not satisfy
`canActivate()` (STRATEGY is missing). The test principle (ordering/gate verification) is
preserved; the specific key changes.

**`tacticsTaskActivatesWhenAllCriteriaPresent()` (L125–132):**
Rewrite as `canActivate_true_withReadyAndStrategy()` — put `{READY, STRATEGY}` in CaseFile
and assert `canActivate()` is true. No NEAREST_THREAT involved.

**`attackTargetsNearestThreatWhenAvailable()` (L42–47):**
Rewrite as `attackTargetsMapCenterAlways()` — BasicTacticsTask now always uses MAP_CENTER.
Remove the `Point2d threat = new Point2d(50,50)` setup. Assert target equals `MAP_CENTER`.

**`caseFile()` helper (L136–145):**
Remove `Point2d nearestThreat` parameter and the conditional `cf.put(NEAREST_THREAT, ...)`.
All 7 call sites updated to drop the argument.

---

## CaseFile Keys — After Redesign

| Key | Written by | Purpose after redesign |
|-----|-----------|----------------------|
| `ENEMY_ARMY_SIZE` | DroolsScoutingTask, BasicScoutingTask (@Alt) | Ordering gate for Strategy; soft-read by DroolsStrategyTask; observability |
| `ENEMY_POSTURE` | DroolsScoutingTask | Observability only — no plugin gates on it |
| `TIMING_ATTACK_INCOMING` | DroolsScoutingTask | Observability only |
| `ENEMY_BUILD_ORDER` | DroolsScoutingTask | Observability only |
| `NEAREST_THREAT` | **Removed** (#179) | — |

---

## ARC42STORIES Updates Required

Both entries updated in the same commit as the code changes.

**Layer — casehub-qhorus (L3):**

Current text contains a phantom reference: "TacticsMessageBridge CDI bridge for qualifier
visibility." This class does not exist in the codebase and must be removed.

Updated text: dual-stack architecture — `ScoutingIntelBroker` is now a typed in-memory store
(broker path) AND Qhorus channel coordinator (advisory path); `DroolsTacticsTask` no longer
implements `MessageObserver` — reads from broker via constructor-injected `ScoutingIntelBroker`;
`TacticsIntelCache` removed; new consumers `DroolsStrategyTask` and `FlowEconomicsTask`
implement `ScoutingIntelConsumer` and read from broker; Qhorus channel is the advisory surface
for LLM advisors (#180) and Commentator/Coach (#181).

**Layer — Adaptive Plugin Selection (L5):**

The ARC42STORIES §L5 already documents `{READY, ENEMY_ARMY_SIZE}` as Strategy's dependency
graph entry. After #169, the codebase diverged by adding `ENEMY_POSTURE` and
`TIMING_ATTACK_INCOMING` to `entryCriteria()`. This redesign restores code alignment with
the documented L5 graph — the code was the source of divergence, not ARC42STORIES.

Dependency graph updated — `NEAREST_THREAT` CaseFile gate replaced by broker presence check:

```
Scouting   {READY}                           → writes ENEMY_ARMY_SIZE; publishes to broker
Economics  {READY}                           → reads broker for ARMY_SIZE
Strategy   {READY, ENEMY_ARMY_SIZE} [order]  → writes STRATEGY; reads broker for POSTURE/TIMING
Tactics    {READY, STRATEGY}        [order]  → canActivate() also gates on
                                               broker.current(THREAT_POSITION).isPresent()
```

Clarification for Tactics: `entryCriteria()` = `{READY, STRATEGY}` was already the case —
Tactics never had NEAREST_THREAT in `entryCriteria()`. What changes is the `canActivate()`
override: the `intelCache.get().threatPosition() != null` check is replaced by the broker
presence check. The CaseEngine re-evaluation loop that the L5 entry documents governs both
the CaseFile half and the broker half of `canActivate()`.

---

## Test Changes

### Deleted
- `DroolsTacticsTaskTest` lines 120–155: `TacticsIntelCache merge()` tests — class deleted
- All `intelCache` field references in tests
- `TacticsIntelCache.java` — deleted
- `QhorusScoutingIntelIT.java` — **delete entire file**. Both tests reference
  `tacticsTask.currentIntelCache()` (lines 51, 64) which is removed by this redesign; the
  file fails to compile the moment `currentIntelCache()` is deleted.
  - `afterTickWithEnemies_tacticsIntelCacheIsPopulated()` → replaced by
    `execute_publishesBothBrokerAndAdvisoryChannel_whenThreatsPresent()` in `DroolsScoutingTaskIT`
  - `afterTickWithNoEnemies_tacticsIntelCacheRemainsEmpty()` → covered by
    `broker.current(THREAT_POSITION)` being empty in the no-enemies scenario (asserted in
    `AdaptivePluginSelectionIT.tacticsSkippedWhenNoEnemiesVisible()`)

### Updated

**`DroolsTacticsTaskTest`** — constructor call gains third arg:
```java
// Before: task = new DroolsTacticsTask(ruleUnit, intentQueue);
ScoutingIntelBroker broker = new ScoutingIntelBroker(); // @PostConstruct skipped; latest map ready
broker.update(new ScoutingIntelPayload.ThreatPosition(pos));
task = new DroolsTacticsTask(ruleUnit, intentQueue, broker);
```
`refreshSubscriptions()` replaces `initSubscriptions()` at all call sites.

**`DroolsTacticsTaskIT`** — broker injection and cross-test isolation:

`DroolsTacticsTaskIT` is `@QuarkusTest`. `ScoutingIntelBroker` is `@ApplicationScoped` — a
single CDI instance shared across all test methods. The existing `intelCache.set()` pattern had
an explicit null-branch reset:

```java
// Old null branch — reset intelCache to empty
((DroolsTacticsTask) tacticsTask).intelCache.set(TacticsIntelCache.empty());
```

After the redesign, `broker.latest` accumulates across tests without an equivalent reset. Test N
leaves `THREAT_POSITION` in the broker. Test N+1 with `nearestThreat=null` (no broker.update()
call) sees stale state from the previous test; `canActivate()` returns `true` when it should
return `false`.

**Resolution:** add `broker.clearLatest()` to the existing `@BeforeEach @AfterEach void reset()`
method. `clearLatest()` is package-private on `ScoutingIntelBroker` — same pattern as
`computeActiveTypes()`:

```java
@Inject ScoutingIntelBroker broker;   // added field

@BeforeEach @AfterEach
void reset() {
    intentQueue.drainAll();
    sessionManager.reset();
    broker.clearLatest();             // added — prevents broker state bleed across tests
}
```

Lines 232–235 replacement:
```java
// Before: ((DroolsTacticsTask) tacticsTask).intelCache.set(new TacticsIntelCache(pos, null, null));
// After (non-null branch): broker.update(new ThreatPosition(nearestThreat));
// After (null branch):     broker.clearLatest();  ← handled by @BeforeEach reset()
```

**`BasicTacticsTaskTest`** — full rework (see NEAREST_THREAT section above):
- `caseFile()` helper: remove `nearestThreat` param and `cf.put(NEAREST_THREAT, ...)` line
- `tacticsTaskRequiresNearestThreatToActivate()` → `canActivate_false_withoutStrategy()`
- `tacticsTaskActivatesWhenAllCriteriaPresent()` → `canActivate_true_withReadyAndStrategy()`
- `attackTargetsNearestThreatWhenAvailable()` → `attackTargetsMapCenterAlways()`

**`AdaptivePluginSelectionIT`:**

- `tacticsSkippedWhenNoEnemiesVisible()`: remove `caseFile.contains(NEAREST_THREAT)` assertion;
  add `assertThat(broker.current(THREAT_POSITION)).isEmpty()`

- `strategyRequiresScoutingOutputToActivate()`: Positive case requires **both** gates:
  ```java
  // BOTH gates must be satisfied for canActivate() to return true:
  // Gate 1 (CaseFile): READY and ENEMY_ARMY_SIZE
  // Gate 2 (broker): broker.current(POSTURE).isPresent()
  withScouting.put(QuarkMindCaseFile.READY, Boolean.TRUE);
  withScouting.put(QuarkMindCaseFile.ENEMY_ARMY_SIZE, 0);   // existing line — unchanged
  broker.update(new ScoutingIntelPayload.PostureUpdate("UNKNOWN")); // new line — satisfies gate 2
  assertThat(strategyTask.canActivate(withScouting)).isTrue();
  ```
  Note: `@Inject ScoutingIntelBroker broker` added to test class; `@BeforeEach` clears broker
  state to avoid cross-test contamination.

- `tacticsActivatesWhenNearestThreatAndStrategyPresent()`:
  ```java
  // Before: result.caseFile().put(NEAREST_THREAT, new Point2d(50,50));
  // After:  broker.update(new ThreatPosition(new Point2d(50,50)));
  ```

**`EconomicsDecisionServiceTest`** and **`EconomicsFlowTest`:**
`tick()` and `tickWithGas()` helpers add `int enemyArmySize = 0` as last constructor argument.

### New tests

**`ScoutingIntelBrokerTest`** — pure unit tests only (no CDI):
- `update_storesLatestByType()` — call `update(new ThreatPosition(...))`, assert `current(THREAT_POSITION).isPresent()`
- `current_returnsEmpty_beforeFirstUpdate()` — fresh broker, assert `current(THREAT_POSITION).isEmpty()`
- `onGameStarted_clearsAllLatestValues()` — update, then call `onGameStarted(new GameStarted())` directly, assert empty

`refreshAll_recomputesActiveTypesFromUpdatedPreferences()` is **not** a unit test —
`refreshAll()` requires CDI-injected `PreferenceProvider` and `Instance<ScoutingIntelConsumer>`.
It is covered end-to-end by `QaEndpointsTest: POST /qa/scouting/subscriptions/reload`.

**`DroolsStrategyTaskTest`** (`@QuarkusTest` — already annotated; Drools RuleUnit requires CDI):

Add `@Inject ScoutingIntelBroker broker` field. Add `broker.clearLatest()` to the existing
`@BeforeEach @AfterEach void drainQueue()`.

**Existing tests that break or become stale — full list:**

The existing `caseFile()` helper (L304–L320) puts `ENEMY_POSTURE` and `TIMING_ATTACK_INCOMING`
into the CaseFile. After the redesign, `DroolsStrategyTask.execute()` reads posture and timing
from the broker, not from the CaseFile. All tests that exercise posture-driven strategy
decisions must add `broker.update()` calls before `strategyTask.execute(cf)`.

*canActivate tests — all four require rewriting:*

`canActivate_allKeysPresent()` (L212): immediately broken — puts `{READY, ENEMY_POSTURE,
TIMING_ATTACK_INCOMING}` but NOT `ENEMY_ARMY_SIZE`. New entryCriteria requires `ENEMY_ARMY_SIZE`.
`canActivate()` returns `false`; the test asserts `isTrue()` — hard failure on first run.

The four existing canActivate tests are replaced with tests that match the new two-gate model:
- `canActivate_false_whenReadyAbsent()` — CaseFile has `{ENEMY_ARMY_SIZE}`, no READY; no broker state
- `canActivate_false_whenEnemyArmySizeAbsent()` — CaseFile has `{READY}`, broker has POSTURE
- `canActivate_false_whenBrokerHasNoPosture()` — CaseFile has `{READY, ENEMY_ARMY_SIZE}`, broker empty
- `canActivate_true_whenBothGatesSatisfied()` — CaseFile `{READY, ENEMY_ARMY_SIZE}` + broker.update(PostureUpdate("UNKNOWN"))

*execute() tests with non-default posture or timing=true — need broker.update():*

Tests using default `("UNKNOWN", false)` posture/timing (Gateway, Stalker, Assimilator build
tests) continue to work without broker.update(): broker empty → execute() reads "UNKNOWN" and
false as defaults — same values the tests currently pass. No change needed.

Tests with non-default posture/timing must add explicit broker setup:

| Existing test | Needed addition |
|---|---|
| `strategyIsDefendWhenAllInPosture()` | `broker.update(new PostureUpdate("ALL_IN"))` |
| `strategyIsDefendWhenTimingAttackIncoming()` | `broker.update(new TimingAlert(true))` |
| `strategyIsDefendWhenTimingAttackIncomingWithStalkers()` | `broker.update(new TimingAlert(true))` |
| `strategyIsDefendNotAttackWhenAllInPostureWithStalkers()` | `broker.update(new PostureUpdate("ALL_IN"))` |
| `strategyIsAttackWhenMacroPostureAndEnoughStalkers()` | `broker.update(new PostureUpdate("MACRO"))` |

The `caseFile()` helper signature is unchanged — it still puts ENEMY_POSTURE and
TIMING_ATTACK_INCOMING into the CaseFile for observability (they're still written by scouting).
Only execute() no longer reads them.

New tests from the spec list that now belong here rather than as additions:
- `refreshSubscriptions_updatesSubscribedTypes()` — verifies subscription preference hot-reload

**`FlowEconomicsTaskTest`** (`@QuarkusTest` — `@Channel` injection requires SmallRye Reactive
Messaging CDI context; `MutinyEmitter` has no plain-Java mock):
- `subscribedIntelTypes_includesARMY_SIZE()` — inject task, verify
- `execute_passesArmySizeFromBrokerToGameStateTick()` — broker.update(new ArmySize(7)), call
  execute(), capture emitted tick via `InMemoryConnector.sink("economics-ticks")` and assert
  `enemyArmySize == 7`

The `InMemoryConnector` pattern is already established in Quarkus SmallRye test infrastructure;
`EconomicsFlowTest` already uses the same channel — use the same test profile configuration.

**`DroolsScoutingTaskIT`** (extended):

`@Inject ScoutingIntelBroker broker` added. `broker.clearLatest()` added to existing
`@BeforeEach @AfterEach void reset()`.

*Dual-stack verification* — `execute_publishesBothBrokerAndAdvisoryChannel_whenThreatsPresent()`:

After `scoutingTask.execute(cf)` with enemies present:
1. `assertThat(broker.current(THREAT_POSITION, ThreatPosition.class)).isPresent()` — Stack 1
2. Stack 2 — Qhorus advisory verification: inject `@Inject MessageStore messageStore` and
   assert at least one message was written to the advisory channel:
   `assertThat(messageStore.countByChannel(broker.channelId())).isGreaterThan(0)`

`MessageStore` (not `ChannelService`) is the correct injection target. `ChannelService` covers
channel creation and lookup only — it has no message-query API. `MessageStore.countByChannel()`
is confirmed in the `MessageStore` interface. `InMemoryMessageStore` (`@Alternative @Priority(1)`
in the qhorus testing module) is the active implementation in all `@QuarkusTest` runs — messages
dispatched via `messageService.dispatch()` land there and are queryable without JPA.

The `MessageObserver` alternative is ruled out: a `@ApplicationScoped` test observer would
require a dedicated test profile to prevent it from affecting other ITs sharing the CDI container.

*Advisory-always invariant:* `dispatchToAdvisory()` fires unconditionally within `publishIntel()`.
This is an architectural guarantee verified by code review — not separately tested.

*Full broker population:* existing `writesArmySizeEachTick()` test verifies ENEMY_ARMY_SIZE is
written. Add: after a tick with enemies, `broker.current(POSTURE)` and `broker.current(THREAT_POSITION)`
are populated.

*Note:* The existing `writesNearestThreat()` test (L51–L56) tests `NEAREST_THREAT` in the
CaseFile — this test must be deleted as part of #179.

**`AdaptivePluginSelectionIT`** — add explicit `broker.clearLatest()` to `@BeforeEach setUp()`:

```java
@Inject ScoutingIntelBroker broker;  // added field

@BeforeEach
void setUp() {
    simulatedGame.reset();
    orchestrator.startGame();   // fires GameStarted → broker.onGameStarted() → latest.clear()
    intentQueue.drainAll();
    broker.clearLatest();       // explicit defensive reset — does not rely on event chain
}
```

`startGame()` does fire a synchronous CDI `GameStarted` event (verified: `AgentOrchestrator.startGame()`
line 60 calls `gameStartedEvent.fire(new GameStarted())`). The defensive `broker.clearLatest()`
makes the test independent of this event chain and safe against future refactoring of `startGame()`.

**`QaEndpointsTest`:**
- `POST /qa/scouting/subscriptions/reload` → 200, `activeTypes` reflects updated preferences
  (covers `refreshAll()` end-to-end with real CDI context)
- `POST /qa/scouting/thresholds/reload` → 200, `minThreatDistance` reflects updated preference

---

## Docs Cleanup (#176)

`CLAUDE.md` Project Artifacts table updated:

```
| docs/protocols/ | Quarkmind-specific standing rules (SC2 domain) —
                    platform protocols are in casehubio/garden/docs/protocols/ |
```

`docs/protocols/sc2data-train-times-require-calibration.md` — verified present, quarkmind-local.

---

## What Does Not Change

- `ScoutingIntelType` enum — unchanged
- `ScoutingIntelPreferences` existing constants and `consumerKey(id, type)` overload — unchanged
- Qhorus channel creation and `broker.channelId()` — unchanged
- `DroolsScoutingTask` change-detection logic (`prevThreatPos`, `prevPosture`, etc.) — unchanged
- `DroolsScoutingTask.producedKeys()` for `ENEMY_POSTURE`, `TIMING_ATTACK_INCOMING`,
  `ENEMY_BUILD_ORDER`, `ENEMY_ARMY_SIZE` — unchanged (observability writes remain)
- `ScoutingIntelBroker.computeActiveTypes()` static method — unchanged, tested directly

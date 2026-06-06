# Layer 3: casehub-qhorus Typed Inter-Plugin Messaging

**Issue:** #155
**Branch:** issue-155-qhorus-layer3
**Date:** 2026-06-06

## Purpose

Adds typed, preference-driven messaging between QuarkMind plugin agents via casehub-qhorus. Severs `DroolsTacticsTask`'s coupling to raw CaseFile key constants for threat intel — tactics reads from a typed, subscription-filtered qhorus channel instead. Demonstrates Layer 3 of the QuarkMind agentic harness tutorial.

## Architecture

### New Components

```
agent/plugin/
  ScoutingIntelType          enum   — 5 intel categories (THREAT_POSITION, POSTURE,
                                      TIMING_ALERT, ARMY_SIZE, BUILD_ORDER)
  ScoutingIntelConsumer      iface  — Set<ScoutingIntelType> subscribedIntelTypes()
  ScoutingIntelPayload       sealed — per-type payload records
  ScoutingIntelPreferences   class  — all PreferenceKey<?> constants

agent/
  ScoutingIntelBroker        @ApplicationScoped
    ├─ collects all ScoutingIntelConsumer CDI beans at @PostConstruct
    ├─ builds union of subscribed types → activeTypes()
    ├─ creates / finds "quarkmind.scouting.intel" observe channel → channelId()
    └─ exposes isSubscribed(ScoutingIntelType) to producer

plugin/scouting/
  DroolsScoutingTask         (modified)

plugin/
  DroolsTacticsTask          (modified)
```

### Data Flow Per Tick

```
DroolsScoutingTask.execute()
  → always computes THREAT_POSITION, ARMY_SIZE (cheap — stream min/count)
  → runs Drools CEP only if BUILD_ORDER, TIMING_ALERT, or POSTURE is in activeTypes()
  → for each subscribed type: dispatches via MessageService(EVENT) if value changed
    beyond its preference-configured threshold
  → still writes CaseFile keys (unchanged — harness and other code may read them)

(synchronous, via InProcessMessageBus — within the same dispatch() call)
  qhorus → DroolsTacticsTask.notify() → AtomicReference<TacticsIntelCache> updated

DroolsTacticsTask.execute()
  → reads TacticsIntelCache from AtomicReference (no CaseFile key coupling)
  → canActivate() gates on READY + STRATEGY in CaseFile + cache.threatPosition() non-null
```

Note: `MessageObserver.notify()` is called synchronously by `InProcessMessageBus` during `MessageService.dispatch()`. The cache is updated before `dispatch()` returns, so `DroolsTacticsTask` reads fresh intel in the same tick that scouting dispatched it.

## New Types

### `ScoutingIntelType`

```java
public enum ScoutingIntelType {
    THREAT_POSITION,   // nearest enemy position — Point2d
    POSTURE,           // enemy posture string — AGGRESSIVE / DEFENSIVE / UNKNOWN
    TIMING_ALERT,      // incoming timing attack — boolean
    ARMY_SIZE,         // enemy army unit count — int
    BUILD_ORDER        // detected build order string — e.g. "4-GATE"
}
```

### `ScoutingIntelConsumer`

```java
public interface ScoutingIntelConsumer {
    Set<ScoutingIntelType> subscribedIntelTypes();
}
```

Any plugin that wants typed scouting intel implements this interface. `ScoutingIntelBroker` discovers all implementations via CDI `Instance<ScoutingIntelConsumer>` at startup.

### `ScoutingIntelPayload` (sealed)

```java
public sealed interface ScoutingIntelPayload
    permits ScoutingIntelPayload.ThreatPosition,
            ScoutingIntelPayload.PostureUpdate,
            ScoutingIntelPayload.TimingAlert,
            ScoutingIntelPayload.ArmySize,
            ScoutingIntelPayload.BuildOrder {

    record ThreatPosition(Point2d position)   implements ScoutingIntelPayload {}
    record PostureUpdate(String posture)       implements ScoutingIntelPayload {}
    record TimingAlert(boolean incoming)       implements ScoutingIntelPayload {}
    record ArmySize(int count)                 implements ScoutingIntelPayload {}
    record BuildOrder(String detected)         implements ScoutingIntelPayload {}
}
```

Sealed so dispatch sites and observer switch branches are exhaustive. `Point2d` is a plain Java domain record — serialises to `{x: float, y: float}` without framework leakage.

Qhorus message content is JSON-encoded with a `type` discriminator matching `ScoutingIntelType.name()`:
```json
{"type": "ThreatPosition", "data": {"x": 45.0, "y": 120.0}}
```

### `ScoutingIntelPreferences`

Holds all `PreferenceKey<?>` constants. Requires a `ScoutingIntelPreference implements Preference` wrapper for the platform-api typed key contract.

**Producer-side (dispatch thresholds):**

| Key | Type | Default | Meaning |
|-----|------|---------|---------|
| `scouting.intel.dispatch.threat-position.min-distance` | `Double` | `0.0` | Dispatch if enemy moved > N units |
| `scouting.intel.dispatch.army-size.min-delta` | `Integer` | `1` | Dispatch if count changed by ≥ N |
| `scouting.intel.dispatch.posture.enabled` | `Boolean` | `true` | Dispatch on any posture change |
| `scouting.intel.dispatch.timing-alert.enabled` | `Boolean` | `true` | Dispatch on any timing change |
| `scouting.intel.dispatch.build-order.enabled` | `Boolean` | `true` | Dispatch on any build order change |

**Consumer-side (per-consumer subscription flags):**

| Key | Type | Default |
|-----|------|---------|
| `scouting.intel.consumer.tactics.threat-position` | `Boolean` | `true` |
| `scouting.intel.consumer.tactics.posture` | `Boolean` | `true` |
| `scouting.intel.consumer.tactics.timing-alert` | `Boolean` | `true` |
| `scouting.intel.consumer.tactics.army-size` | `Boolean` | `false` |
| `scouting.intel.consumer.tactics.build-order` | `Boolean` | `false` |

Consumer key naming convention: `scouting.intel.consumer.{plugin-id}.{type-name-kebab}`. Future consumers (strategy, economics — see #177) declare their own keys following the same pattern. `ScoutingIntelPreferences` provides a `consumerKey(String pluginId, ScoutingIntelType)` factory method. Kebab-case mapping: `THREAT_POSITION` → `threat-position`, `TIMING_ALERT` → `timing-alert`, `BUILD_ORDER` → `build-order`, `ARMY_SIZE` → `army-size`, `POSTURE` → `posture`.

Preferences are read at `@PostConstruct` — subscriptions and thresholds are fixed at boot. Dynamic hot-reload is deferred to #178.

## `ScoutingIntelBroker`

```java
@ApplicationScoped
public class ScoutingIntelBroker {

    static final String CHANNEL_NAME = "quarkmind.scouting.intel";

    @Inject Instance<ScoutingIntelConsumer> consumers;
    @Inject ChannelService channelService;

    private UUID channelId;
    private Set<ScoutingIntelType> activeTypes;

    @PostConstruct
    void init() {
        // GE-20260529-88b7b6: ChannelService.create() not idempotent — findByName() first
        channelId = channelService.findByName(CHANNEL_NAME)
            .map(Channel::id)
            .orElseGet(() -> channelService.create(CHANNEL_NAME, ChannelType.OBSERVE).id());
        // GE-20260526-5247f2: create() does not register in ChannelGateway.
        // MessageObserver (global broadcast) is used — no fanOut() registration needed.

        activeTypes = consumers.stream()
            .flatMap(c -> c.subscribedIntelTypes().stream())
            .collect(Collectors.toUnmodifiableSet());
    }

    public UUID channelId()                          { return channelId; }
    public boolean isSubscribed(ScoutingIntelType t) { return activeTypes.contains(t); }
    public Set<ScoutingIntelType> activeTypes()      { return activeTypes; }
}
```

## `DroolsScoutingTask` Changes

**New injections:**
- `ScoutingIntelBroker broker`
- `MessageService messageService`
- `ObjectMapper objectMapper`
- `Preferences preferences`

**New fields:**
- `double minThreatDistance`, `int minArmySizeDelta` — loaded from preferences at `@PostConstruct`
- Per-type previous-value fields: `prevThreatPos`, `prevPosture`, `prevTimingAlert`, `prevArmySize`, `prevBuildOrder`

**`execute()` additions:**

```
1. Compute THREAT_POSITION and ARMY_SIZE always (cheap)
2. if broker.isSubscribed(BUILD_ORDER || TIMING_ALERT || POSTURE): run Drools CEP
3. For each subscribed type, check threshold/change, dispatch if exceeded
4. Existing CaseFile writes unchanged
```

**`dispatch()` helper:**
```java
private void dispatch(ScoutingIntelPayload payload) {
    String content = objectMapper.writeValueAsString(
        Map.of("type", payload.getClass().getSimpleName(), "data", payload));
    messageService.dispatch(MessageDispatch.builder()
        .channelId(broker.channelId())
        .sender(getId())
        .actorType(ActorType.AGENT)   // GE-20260529-e32a4d: required — omitting throws IAE
        .messageType(MessageType.EVENT)
        .content(content)
        .build());
}
```

The existing `prevEnemyHash` / `PluginDecisionEvent` path is unchanged — that is the ledger attestation path, orthogonal to qhorus dispatch.

## `DroolsTacticsTask` Changes

**Interface additions:**
```java
public class DroolsTacticsTask implements TacticsTask, ScoutingIntelConsumer, MessageObserver
```

**New fields:**
- `AtomicReference<TacticsIntelCache> intelCache` — thread-safe cache updated by notify()
- `Set<ScoutingIntelType> subscribedTypes` — built from preferences at @PostConstruct
- `ScoutingIntelBroker broker`, `ObjectMapper objectMapper` — new injections

**`TacticsIntelCache` record:**
```java
record TacticsIntelCache(Point2d threatPosition, String posture, Boolean timingAlert) {
    static TacticsIntelCache empty() { return new TacticsIntelCache(null, null, null); }
}
```
`merge(TacticsIntelCache prev, ScoutingIntelPayload payload)` returns a new cache with the updated field — pure function, atomic update.

**`subscribedIntelTypes()`** — reads consumer preference keys at `@PostConstruct`, returns immutable set.

**`MessageObserver.notify()`:**
```java
public void notify(Message message) {
    if (!message.channelId().equals(broker.channelId())) return; // filter: own channel only
    ScoutingIntelPayload payload = objectMapper.readValue(message.content(), ScoutingIntelPayload.class);
    intelCache.updateAndGet(prev -> merge(prev, payload));
}
```

**`entryCriteria()` and `canActivate()` changes:**
- Remove `NEAREST_THREAT` from `entryCriteria()` — tactics no longer reads this key
- `canActivate()` adds: `&& intelCache.get() != null && intelCache.get().threatPosition() != null`

**`execute()` change:**
```java
// Before: caseFile.get(QuarkMindCaseFile.NEAREST_THREAT, Point2d.class).orElse(null)
// After:
TacticsIntelCache intel = intelCache.get();
Point2d threat = intel != null ? intel.threatPosition() : null;
```

`STRATEGY`, `ARMY`, `ENEMY_UNITS`, `MY_BUILDINGS` continue to be read from CaseFile — those are not scouting's concern.

## Maven & Configuration

**`pom.xml` additions:**
```xml
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-qhorus-api</artifactId>
</dependency>
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-qhorus</artifactId>
</dependency>
```

**`application.properties` additions:**
```properties
quarkus.datasource."qhorus".db-kind=h2
quarkus.datasource."qhorus".jdbc.url=jdbc:h2:mem:qhorus;MODE=PostgreSQL;DB_CLOSE_DELAY=-1
quarkus.flyway."qhorus".migrate-at-start=true
quarkus.flyway."qhorus".locations=classpath:db/qhorus/migration,classpath:db/ledger/migration
```

`%sc2` and `%emulated` profiles override with a real Postgres URL for live runs. `GE-20260523-fc29ea` addressed by explicit `migrate-at-start=true` with correct locations.

`casehub-platform` mock scope: already on classpath via `casehub-persistence-memory` — `MockPreferenceProvider @DefaultBean` satisfies qhorus's `PreferenceProvider` injection point. Verify during implementation.

## Testing

### Unit Tests (plain JUnit, no CDI)

| Test | What it validates |
|------|------------------|
| `ScoutingIntelBrokerTest` | `activeTypes()` is union of consumer declarations; `isSubscribed()` correct; mock `ChannelService` |
| `DroolsScoutingTaskTest` (additions) | CEP skipped when expensive types not subscribed; dispatch called only when threshold exceeded; dispatch not called when value unchanged |
| `DroolsTacticsTaskTest` (additions) | `notify()` updates cache; `canActivate()` false before first message; `execute()` reads from cache not CaseFile |

### Integration Tests (`@QuarkusTest`)

| Test | What it validates |
|------|------------------|
| `QhorusScoutingIntelIT` (new) | Full dispatch → MessageObserver → cache path; `gameTick()` with enemies present; `Thread.sleep(300)`; asserts cache populated |
| `FullMockPipelineIT` (existing) | Boots cleanly with qhorus datasource config; no logic changes — just confirming CDI wiring resolves |

Preference permutations (different subscription combinations) are unit-testable with `Preferences` stubs — no CDI boot needed.

## Deferred Issues

| Issue | Description |
|-------|-------------|
| #177 | Strategy and Economics plugins subscribe to `ScoutingIntelConsumer` |
| #178 | Dynamic preference hot-reload for subscriptions and thresholds |

## PLATFORM.md Updates Required at Close

Add to cross-repo dependency map:
```
casehub-qhorus-api   quarkmind   src/main/   MessageService, ChannelService, MessageObserver SPIs
casehub-qhorus       quarkmind   src/main/   runtime — named qhorus datasource
```

# Layer 3: casehub-qhorus Typed Inter-Plugin Messaging

**Issue:** #155
**Branch:** issue-155-qhorus-layer3
**Date:** 2026-06-06
**Review applied:** 2026-06-07 — 10 findings from source-level code review

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
  ScoutingIntelBroker        @ApplicationScoped CDI bean

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
  → for each subscribed type: dispatches via MessageService(STATUS) if value changed
    beyond its preference-configured threshold
  → still writes CaseFile keys (harness observability — see NEAREST_THREAT note below)

(post-commit, after dispatch() transaction commits)
  qhorus InProcessMessageBus → DroolsTacticsTask.onMessage() → AtomicReference<TacticsIntelCache> updated

DroolsTacticsTask.execute()
  → reads TacticsIntelCache from AtomicReference (no CaseFile key coupling)
  → canActivate() gates on READY + STRATEGY in CaseFile + cache.threatPosition() non-null
```

**Delivery timing:** `MessageObserver.onMessage()` is called post-commit by `MessageObserverDispatcher` — when dispatching inside a JTA transaction (always true for `@Transactional MessageService.dispatch()`), observer calls are deferred to `afterCompletion(STATUS_COMMITTED)`. `DroolsTacticsTask` sees the cache update in the tick **after** scouting dispatched. The `Thread.sleep(300)` in `QhorusScoutingIntelIT` is correct and necessary.

**`NEAREST_THREAT` retention:** Scouting still writes this CaseFile key for harness observability (QA endpoints, visualizer, benchmark output). Tactics no longer reads it — the key is now observability-only. Issue #179 tracks eventual deprecation once no other code reads it.

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

Qhorus message content is JSON-encoded with a `type` discriminator matching the simple class name. `data` is the full serialized record — field-wrapped, not the inner value:
```json
{"type": "ThreatPosition", "data": {"position": {"x": 45.0, "y": 120.0}}}
```

`Point2d` is a plain Java domain record — serialises to `{x: float, y: float}`.

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

Consumer key naming convention: `scouting.intel.consumer.{plugin-id}.{type-name-kebab}`. Kebab-case mapping: `THREAT_POSITION` → `threat-position`, `TIMING_ALERT` → `timing-alert`, `BUILD_ORDER` → `build-order`, `ARMY_SIZE` → `army-size`, `POSTURE` → `posture`. `ScoutingIntelPreferences` provides a `consumerKey(String pluginId, ScoutingIntelType)` factory method. Future consumers (strategy, economics — see #177) declare their own keys following the same pattern.

Preferences are read at `@PostConstruct` — subscriptions and thresholds are fixed at boot. Dynamic hot-reload is deferred to #178.

## `ScoutingIntelBroker`

```java
@ApplicationScoped
public class ScoutingIntelBroker {

    public static final String CHANNEL_NAME = "quarkmind-scouting-intel";

    @Inject Instance<ScoutingIntelConsumer> consumers;
    @Inject ChannelService channelService;

    private UUID channelId;
    private Set<ScoutingIntelType> activeTypes;

    @PostConstruct
    void init() {
        // GE-20260529-88b7b6: ChannelService.create() not idempotent — findByName() first
        channelId = channelService.findByName(CHANNEL_NAME)
            .map(Channel::id)
            .orElseGet(() -> channelService.create(
                CHANNEL_NAME,
                "Scouting intel for agent plugins",
                ChannelSemantic.APPEND,
                null, null, null, null, null,
                "STATUS"    // allowedTypes — restricts channel to STATUS-only dispatch
            ).id());

        // qhorus/254: ChannelService.create() does NOT call channelGateway.initChannel() —
        // ChannelBackend registration never fires for runtime-created channels.
        // MessageObserver with channels() filter is the correct delivery path here.

        activeTypes = consumers.stream()
            .flatMap(c -> c.subscribedIntelTypes().stream())
            .collect(Collectors.toUnmodifiableSet());
    }

    public UUID channelId()                          { return channelId; }
    public boolean isSubscribed(ScoutingIntelType t) { return activeTypes.contains(t); }
    public Set<ScoutingIntelType> activeTypes()      { return activeTypes; }
}
```

**Why `MessageObserver` over `ChannelBackend`:** PLATFORM.md recommends `ChannelBackend` for per-channel consumers. However, `ChannelService.create()` never calls `channelGateway.initChannel()` — `ChannelInitialisedEvent` only fires for channels created at startup recovery or via the MCP `create_channel` tool. Runtime-created channels (like ours at `@PostConstruct`) never trigger `ChannelBackend` registration. `fanOut()` would silently do nothing. `MessageObserver` with a `channels()` filter is the correct and only working path for runtime-created channels. Tracked in casehubio/qhorus#254.

`activeTypes` is immutable after startup — subscriptions are fixed at boot.

## `DroolsScoutingTask` Changes

**New injections:**
- `ScoutingIntelBroker broker`
- `MessageService messageService`
- `ObjectMapper objectMapper`
- `Preferences preferences` (via `PreferenceProvider` — inject `PreferenceProvider` and call `resolve()`)

**New fields:**
- `double minThreatDistance`, `int minArmySizeDelta` — loaded from preferences at `@PostConstruct`
- Per-type previous-value fields: `prevThreatPos`, `prevPosture`, `prevTimingAlert`, `prevArmySize`, `prevBuildOrder`

**`execute()` additions:**

```
1. Compute THREAT_POSITION and ARMY_SIZE always (cheap)
2. if broker.isSubscribed(BUILD_ORDER || TIMING_ALERT || POSTURE): run Drools CEP
3. For each subscribed type, check threshold/change, dispatch if exceeded
4. Existing CaseFile writes unchanged (observability)
```

**`dispatch()` helper — uses `MessageType.STATUS`, not EVENT:**

STATUS is used because `MessageObserverDispatcher` forces null content for EVENT per PP-20260508-90428f — EVENT observers never see the payload. Semantically EVENT would be correct (no obligation, pure telemetry); STATUS is the pragmatic substitute until Qhorus supports EVENT content for application-tier observers. The channel `allowedTypes="STATUS"` makes this constraint visible at the channel level.

```java
// GE-20260607-d051f2: EVENT content is null in MessageReceivedEvent — use STATUS
private void dispatch(ScoutingIntelPayload payload) {
    try {
        String content = objectMapper.writeValueAsString(
            Map.of("type", payload.getClass().getSimpleName(), "data", payload));
        messageService.dispatch(MessageDispatch.builder()
            .channelId(broker.channelId())
            .sender(getId())
            .actorType(ActorType.AGENT)   // GE-20260529-e32a4d: required — omitting throws IAE
            .messageType(MessageType.STATUS)   // STATUS carries content; EVENT forces null
            .content(content)
            .build());
    } catch (JsonProcessingException e) {
        log.warnf("Failed to serialise scouting intel payload: %s", e.getMessage());
    }
}
```

The existing `prevEnemyHash` / `PluginDecisionEvent` path is unchanged — that is the ledger attestation path, orthogonal to qhorus dispatch.

## `DroolsTacticsTask` Changes

**Interface additions:**
```java
public class DroolsTacticsTask implements TacticsTask, ScoutingIntelConsumer, MessageObserver
```

`ScoutingIntelBroker` is NOT injected into `DroolsTacticsTask` — channel filtering uses the static `CHANNEL_NAME` constant via the `channels()` override (see below). This reduces coupling.

**New fields:**
- `AtomicReference<TacticsIntelCache> intelCache = new AtomicReference<>(TacticsIntelCache.empty())` — initialised to empty, never null
- `Set<ScoutingIntelType> subscribedTypes` — built from preferences at @PostConstruct
- `ObjectMapper objectMapper` — new injection

**`TacticsIntelCache` record:**
```java
record TacticsIntelCache(Point2d threatPosition, String posture, Boolean timingAlert) {
    static TacticsIntelCache empty() { return new TacticsIntelCache(null, null, null); }
}
```
`merge(TacticsIntelCache prev, ScoutingIntelPayload payload)` returns a new cache with the updated field — pure function, atomic update.

**`subscribedIntelTypes()`** — reads consumer preference keys at `@PostConstruct`, returns immutable set.

**`channels()` override — built-in dispatcher filter, eliminates broker injection:**
```java
@Override
public Set<String> channels() {
    return Set.of(ScoutingIntelBroker.CHANNEL_NAME);
}
```
`MessageObserverDispatcher` applies this filter before calling `onMessage()`. No manual channel check needed inside the method.

**`MessageObserver.onMessage()` — correct method name and event type:**
```java
@Override
public void onMessage(MessageReceivedEvent event) {
    // channel filter already applied by MessageObserverDispatcher via channels()
    try {
        JsonNode node = objectMapper.readTree(event.content());
        String type = node.get("type").asText();
        JsonNode data = node.get("data");
        // Sealed interface cannot be deserialized directly — manual type-switch required
        ScoutingIntelPayload payload = switch (type) {
            case "ThreatPosition" -> objectMapper.treeToValue(data, ScoutingIntelPayload.ThreatPosition.class);
            case "PostureUpdate"  -> objectMapper.treeToValue(data, ScoutingIntelPayload.PostureUpdate.class);
            case "TimingAlert"    -> objectMapper.treeToValue(data, ScoutingIntelPayload.TimingAlert.class);
            case "ArmySize"       -> objectMapper.treeToValue(data, ScoutingIntelPayload.ArmySize.class);
            case "BuildOrder"     -> objectMapper.treeToValue(data, ScoutingIntelPayload.BuildOrder.class);
            default -> throw new IllegalArgumentException("Unknown ScoutingIntelType: " + type);
        };
        intelCache.updateAndGet(prev -> merge(prev, payload));
    } catch (JsonProcessingException e) {
        log.warnf("Failed to deserialise scouting intel: %s", e.getMessage());
    }
}
```

**`entryCriteria()` and `canActivate()` changes:**
- Remove `NEAREST_THREAT` from `entryCriteria()` — tactics no longer reads this key
- `intelCache` is initialised to `TacticsIntelCache.empty()` — never null; no null check needed

```java
@Override
public Set<String> entryCriteria() {
    return Set.of(QuarkMindCaseFile.READY, QuarkMindCaseFile.STRATEGY);
}

@Override
public boolean canActivate(CaseFile caseFile) {
    return entryCriteria().stream().allMatch(caseFile::contains)
        && intelCache.get().threatPosition() != null;  // non-null = scouting has sent at least one threat
}
```

**`execute()` change:**
```java
// Before: caseFile.get(QuarkMindCaseFile.NEAREST_THREAT, Point2d.class).orElse(null)
// After:
TacticsIntelCache intel = intelCache.get();
Point2d threat = intel.threatPosition();
```

`STRATEGY`, `ARMY`, `ENEMY_UNITS`, `MY_BUILDINGS` continue to be read from CaseFile — those are not scouting's concern.

## Maven & Configuration

**`pom.xml` additions:**
```xml
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-platform-api</artifactId>  <!-- PreferenceProvider, SettingsScope, ActorType — explicit, not transitive -->
</dependency>
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

**Note on `Preferences` injection:** `Preferences` is not a CDI bean (GE-20260607-3611a2). Inject `PreferenceProvider` and call `preferenceProvider.resolve(SettingsScope.root())` to obtain a `Preferences` instance. Do not `@Inject Preferences` directly.

## Testing

### Unit Tests (plain JUnit, no CDI)

| Test | What it validates |
|------|------------------|
| `ScoutingIntelBrokerTest` | `activeTypes()` is union of consumer declarations; `isSubscribed()` correct; mock `ChannelService` |
| `DroolsScoutingTaskTest` (additions) | CEP skipped when expensive types not subscribed; dispatch called only when threshold exceeded; dispatch not called when value unchanged; `MessageType.STATUS` used (not EVENT) |
| `DroolsTacticsTaskTest` (additions) | `onMessage()` updates cache; `canActivate()` false before first message (threatPosition null), true after; `execute()` reads from cache not CaseFile |

### Integration Tests (`@QuarkusTest`)

| Test | What it validates |
|------|------------------|
| `QhorusScoutingIntelIT` (new) | Full dispatch → post-commit → MessageObserver → cache path; `gameTick()` with enemies present; `Thread.sleep(300)` required (post-commit delivery); asserts cache populated |
| `FullMockPipelineIT` (existing) | Boots cleanly with qhorus datasource config; no logic changes |

Preference permutations (different subscription combinations) are unit-testable with `PreferenceProvider` stubs — no CDI boot needed.

## Deferred Issues

| Issue | Repo | Description |
|-------|------|-------------|
| #177 | quarkmind | Strategy and Economics plugins subscribe to `ScoutingIntelConsumer` |
| #178 | quarkmind | Dynamic preference hot-reload for subscriptions and thresholds |
| #179 | quarkmind | Deprecate and remove `NEAREST_THREAT` CaseFile key once no readers remain |
| qhorus#254 | casehub-qhorus | `ChannelService.create()` should call `channelGateway.initChannel()` |

## PLATFORM.md Updates Required at Close

Add to cross-repo dependency map:
```
casehub-qhorus-api   quarkmind   src/main/   MessageService, ChannelService, MessageObserver SPIs
casehub-qhorus       quarkmind   src/main/   runtime — named qhorus datasource
```

## Review Findings Applied (2026-06-07)

| # | Severity | Change |
|---|----------|--------|
| 1 | Critical | `MessageType.EVENT` → `MessageType.STATUS` — EVENT forces null content in `MessageObserverDispatcher` (GE-20260607-d051f2) |
| 2 | Critical | `notify(Message)` → `onMessage(MessageReceivedEvent)` — correct `MessageObserver` method signature |
| 3 | Critical | `ChannelService.create()` signature corrected — `ChannelType.OBSERVE` does not exist; use `ChannelSemantic.APPEND` with full 9-arg signature |
| 4 | Significant | Removed "same-tick synchronous delivery" claim — observers fire post-commit; delivery is next tick |
| 5 | Significant | Replaced manual `channelId` UUID check with `channels()` override — `MessageObserverDispatcher` applies name-based filter; broker not injected into tactics |
| 6 | Significant | Jackson sealed interface deserialization — manual `readTree()` / `treeToValue()` type-switch; direct `readValue(ScoutingIntelPayload.class)` cannot work |
| 7 | Medium | `NEAREST_THREAT` documented as observability key with #179 tracking eventual deprecation |
| 8 | Medium | Broker injection removed from `DroolsTacticsTask` — `channels()` uses static constant |
| 9 | Medium | Removed redundant null check — `intelCache` initialised to `TacticsIntelCache.empty()`, never null |
| 10 | Architectural | `MessageObserver` vs `ChannelBackend` rationale made explicit — platform gap in qhorus#254 |
| 11 | Bug | `CHANNEL_NAME` made `public` — package-private inaccessible from `io.quarkmind.plugin` |
| 12 | Minor | JSON example corrected — `data` is the full record, not the inner `Point2d` value |
| 13 | Minor | STATUS semantic rationale added — workaround for PP-20260508-90428f EVENT content null |
| 14 | Minor | `casehub-platform-api` added to pom.xml explicitly (was transitive only) |

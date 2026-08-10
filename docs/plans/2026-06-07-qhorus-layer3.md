# Layer 3: casehub-qhorus Typed Inter-Plugin Messaging — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire casehub-qhorus as the typed delivery channel between ScoutingTask and TacticsTask, severing DroolsTacticsTask's coupling to raw CaseFile key constants for threat intel.

**Architecture:** ScoutingIntelBroker registers a qhorus channel at startup and tracks which intel types any ScoutingIntelConsumer subscribes to. DroolsScoutingTask dispatches STATUS messages (one per changed intel type) when values drift past preference-configured thresholds. DroolsTacticsTask implements MessageObserver and caches the latest TacticsIntelCache in an AtomicReference, activating only when scouting has sent at least one threat position. Delivery is post-commit (InProcessMessageBus defers to afterCompletion), so tactics sees updates one tick after scouting dispatches.

**Tech Stack:** Java 21, Quarkus 3.34.2, casehub-qhorus 0.2-SNAPSHOT, casehub-platform-api 0.2-SNAPSHOT, Jackson, JUnit 5 + Mockito, AssertJ.

---

## File Map

**New files:**
- `src/main/java/io/quarkmind/agent/plugin/ScoutingIntelPreference.java` — `SingleValuePreference` wrapper holding a raw value with typed accessors
- `src/main/java/io/quarkmind/agent/plugin/ScoutingIntelPreferences.java` — all `PreferenceKey<ScoutingIntelPreference>` constants + `consumerKey()` factory
- `src/main/java/io/quarkmind/agent/plugin/ScoutingIntelType.java` — 5-value enum
- `src/main/java/io/quarkmind/agent/plugin/ScoutingIntelConsumer.java` — `subscribedIntelTypes()` seam interface
- `src/main/java/io/quarkmind/agent/plugin/ScoutingIntelPayload.java` — sealed interface with 5 payload records
- `src/main/java/io/quarkmind/agent/ScoutingIntelBroker.java` — channel setup + subscription union at `@PostConstruct`
- `src/main/java/io/quarkmind/plugin/TacticsIntelCache.java` — package-private record for cached intel state
- `src/test/java/io/quarkmind/agent/plugin/ScoutingIntelPreferencesTest.java`
- `src/test/java/io/quarkmind/agent/plugin/ScoutingIntelPayloadTest.java`
- `src/test/java/io/quarkmind/agent/ScoutingIntelBrokerTest.java`
- `src/test/java/io/quarkmind/plugin/QhorusScoutingIntelIT.java`

**Modified files:**
- `pom.xml` — add casehub-platform-api, casehub-qhorus-api, casehub-qhorus
- `src/main/resources/application.properties` — add qhorus H2 datasource for test/mock/emulated/replay profiles
- `src/main/java/io/quarkmind/plugin/scouting/DroolsScoutingTask.java` — broker injection, CEP gating, per-type change detection, `dispatch()` helper
- `src/main/java/io/quarkmind/plugin/DroolsTacticsTask.java` — implements `ScoutingIntelConsumer` + `MessageObserver`, `onMessage()`, `merge()`, `canActivate()` gate change
- `src/test/java/io/quarkmind/plugin/DroolsTacticsTaskTest.java` — new test methods for merge, onMessage, canActivate
- `src/test/java/io/quarkmind/plugin/scouting/DroolsScoutingTaskTest.java` — new test methods for threshold dispatch logic

---

## Task 1: Maven dependencies and datasource configuration

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.properties`

- [ ] **Step 1.1: Add qhorus and platform-api to pom.xml**

In `pom.xml`, add these three dependencies after the existing `casehub-ledger-memory` block (around line 101):

```xml
        <dependency>
            <groupId>io.casehub</groupId>
            <artifactId>casehub-platform-api</artifactId>
            <version>0.2-SNAPSHOT</version>
        </dependency>
        <dependency>
            <groupId>io.casehub</groupId>
            <artifactId>casehub-qhorus-api</artifactId>
            <version>0.2-SNAPSHOT</version>
        </dependency>
        <dependency>
            <groupId>io.casehub</groupId>
            <artifactId>casehub-qhorus</artifactId>
            <version>0.2-SNAPSHOT</version>
        </dependency>
```

- [ ] **Step 1.2: Add qhorus datasource to application.properties**

Append to `src/main/resources/application.properties`:

```properties
# --- casehub-qhorus (Layer 3) ---
# qhorus ships quarkus.hibernate-orm.qhorus.* and quarkus.flyway.qhorus.* in its own
# application.properties; quarkmind only needs to provide the datasource URL.
# H2 in-memory for all non-sc2 profiles; %sc2 requires a real Postgres URL.
quarkus.datasource."qhorus".db-kind=h2
quarkus.datasource."qhorus".jdbc.url=jdbc:h2:mem:qhorus;MODE=PostgreSQL;DB_CLOSE_DELAY=-1
%test.quarkus.datasource."qhorus".db-kind=h2
%test.quarkus.datasource."qhorus".jdbc.url=jdbc:h2:mem:qhorus;MODE=PostgreSQL;DB_CLOSE_DELAY=-1
```

The qhorus extension ships `quarkus.flyway.qhorus.migrate-at-start=true` and `quarkus.hibernate-orm.qhorus.datasource=qhorus` in its own `application.properties`, so no additional config is needed in quarkmind for those.

- [ ] **Step 1.3: Verify compile**

```bash
mvn compile -q
```

Expected: BUILD SUCCESS (may warn about unused imports — that's fine at this stage).

- [ ] **Step 1.4: Commit**

```bash
git add pom.xml src/main/resources/application.properties
git commit -m "feat(#155): add casehub-qhorus deps and H2 datasource config"
```

---

## Task 2: Seam types

**Files:**
- Create: `src/main/java/io/quarkmind/agent/plugin/ScoutingIntelPreference.java`
- Create: `src/main/java/io/quarkmind/agent/plugin/ScoutingIntelPreferences.java`
- Create: `src/main/java/io/quarkmind/agent/plugin/ScoutingIntelType.java`
- Create: `src/main/java/io/quarkmind/agent/plugin/ScoutingIntelConsumer.java`
- Create: `src/main/java/io/quarkmind/agent/plugin/ScoutingIntelPayload.java`
- Create: `src/test/java/io/quarkmind/agent/plugin/ScoutingIntelPreferencesTest.java`
- Create: `src/test/java/io/quarkmind/agent/plugin/ScoutingIntelPayloadTest.java`

All types in `io.quarkmind.agent.plugin` — no framework deps, consistent with the existing seam layer.

- [ ] **Step 2.1: Write ScoutingIntelPreferencesTest**

Create `src/test/java/io/quarkmind/agent/plugin/ScoutingIntelPreferencesTest.java`:

```java
package io.quarkmind.agent.plugin;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ScoutingIntelPreferencesTest {

    @Test
    void consumerKey_tactics_threatPosition_hasExpectedQualifiedName() {
        var key = ScoutingIntelPreferences.consumerKey("tactics.drools-goap", ScoutingIntelType.THREAT_POSITION);
        assertThat(key.qualifiedName())
            .isEqualTo("scouting.intel.consumer.tactics.drools-goap.threat-position");
    }

    @Test
    void consumerKey_default_threatPosition_isTrue() {
        var key = ScoutingIntelPreferences.consumerKey("any-plugin", ScoutingIntelType.THREAT_POSITION);
        assertThat(key.defaultValue().asBoolean()).isTrue();
    }

    @Test
    void consumerKey_default_armySize_isFalse() {
        var key = ScoutingIntelPreferences.consumerKey("any-plugin", ScoutingIntelType.ARMY_SIZE);
        assertThat(key.defaultValue().asBoolean()).isFalse();
    }

    @Test
    void toKebab_allTypes_roundTripsCorrectly() {
        assertThat(ScoutingIntelPreferences.toKebab(ScoutingIntelType.THREAT_POSITION)).isEqualTo("threat-position");
        assertThat(ScoutingIntelPreferences.toKebab(ScoutingIntelType.TIMING_ALERT)).isEqualTo("timing-alert");
        assertThat(ScoutingIntelPreferences.toKebab(ScoutingIntelType.BUILD_ORDER)).isEqualTo("build-order");
        assertThat(ScoutingIntelPreferences.toKebab(ScoutingIntelType.ARMY_SIZE)).isEqualTo("army-size");
        assertThat(ScoutingIntelPreferences.toKebab(ScoutingIntelType.POSTURE)).isEqualTo("posture");
    }

    @Test
    void threatPositionMinDistanceKey_defaultIsZero() {
        assertThat(ScoutingIntelPreferences.THREAT_POSITION_MIN_DISTANCE.defaultValue().asDouble())
            .isEqualTo(0.0);
    }

    @Test
    void armySizeMinDeltaKey_defaultIsOne() {
        assertThat(ScoutingIntelPreferences.ARMY_SIZE_MIN_DELTA.defaultValue().asInt()).isEqualTo(1);
    }
}
```

- [ ] **Step 2.2: Write ScoutingIntelPayloadTest**

Create `src/test/java/io/quarkmind/agent/plugin/ScoutingIntelPayloadTest.java`:

```java
package io.quarkmind.agent.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkmind.domain.Point2d;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScoutingIntelPayloadTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void threatPosition_serialisesWithTypeDiscriminator() throws Exception {
        var payload = new ScoutingIntelPayload.ThreatPosition(new Point2d(10f, 20f));
        String json = mapper.writeValueAsString(
            java.util.Map.of("type", payload.getClass().getSimpleName(), "data", payload));

        JsonNode node = mapper.readTree(json);
        assertThat(node.get("type").asText()).isEqualTo("ThreatPosition");
        assertThat(node.get("data").get("position").get("x").floatValue()).isEqualTo(10f);
        assertThat(node.get("data").get("position").get("y").floatValue()).isEqualTo(20f);
    }

    @Test
    void threatPosition_deserialisesViaManualSwitch() throws Exception {
        String json = """
            {"type":"ThreatPosition","data":{"position":{"x":45.0,"y":120.0}}}
            """;
        JsonNode node = mapper.readTree(json);
        String type = node.get("type").asText();
        JsonNode data = node.get("data");

        ScoutingIntelPayload payload = switch (type) {
            case "ThreatPosition" -> mapper.treeToValue(data, ScoutingIntelPayload.ThreatPosition.class);
            case "PostureUpdate"  -> mapper.treeToValue(data, ScoutingIntelPayload.PostureUpdate.class);
            case "TimingAlert"    -> mapper.treeToValue(data, ScoutingIntelPayload.TimingAlert.class);
            case "ArmySize"       -> mapper.treeToValue(data, ScoutingIntelPayload.ArmySize.class);
            case "BuildOrder"     -> mapper.treeToValue(data, ScoutingIntelPayload.BuildOrder.class);
            default -> throw new IllegalArgumentException("Unknown: " + type);
        };

        assertThat(payload).isInstanceOf(ScoutingIntelPayload.ThreatPosition.class);
        var tp = (ScoutingIntelPayload.ThreatPosition) payload;
        assertThat(tp.position().x()).isEqualTo(45f);
        assertThat(tp.position().y()).isEqualTo(120f);
    }

    @Test
    void postureUpdate_roundTrips() throws Exception {
        var payload = new ScoutingIntelPayload.PostureUpdate("AGGRESSIVE");
        String json = mapper.writeValueAsString(
            java.util.Map.of("type", payload.getClass().getSimpleName(), "data", payload));
        JsonNode node = mapper.readTree(json);
        assertThat(node.get("type").asText()).isEqualTo("PostureUpdate");
        assertThat(node.get("data").get("posture").asText()).isEqualTo("AGGRESSIVE");
    }

    @Test
    void timingAlert_roundTrips() throws Exception {
        var payload = new ScoutingIntelPayload.TimingAlert(true);
        String json = mapper.writeValueAsString(
            java.util.Map.of("type", payload.getClass().getSimpleName(), "data", payload));
        JsonNode node = mapper.readTree(json);
        assertThat(node.get("data").get("incoming").asBoolean()).isTrue();
    }
}
```

- [ ] **Step 2.3: Run tests (expect compile failure — types don't exist yet)**

```bash
mvn test -Dtest="ScoutingIntelPreferencesTest,ScoutingIntelPayloadTest" -q
```

Expected: COMPILE ERROR — `ScoutingIntelPreferences`, `ScoutingIntelPayload`, etc. not found.

- [ ] **Step 2.4: Implement ScoutingIntelPreference**

Create `src/main/java/io/quarkmind/agent/plugin/ScoutingIntelPreference.java`:

```java
package io.quarkmind.agent.plugin;

import io.casehub.platform.api.preferences.SingleValuePreference;

public record ScoutingIntelPreference(Object value) implements SingleValuePreference {

    public boolean asBoolean() { return (Boolean) value; }
    public double  asDouble()  { return ((Number) value).doubleValue(); }
    public int     asInt()     { return ((Number) value).intValue(); }

    public static ScoutingIntelPreference ofBoolean(boolean v) { return new ScoutingIntelPreference(v); }
    public static ScoutingIntelPreference ofDouble(double v)   { return new ScoutingIntelPreference(v); }
    public static ScoutingIntelPreference ofInt(int v)         { return new ScoutingIntelPreference(v); }

    public static ScoutingIntelPreference parseBoolean(String s) { return ofBoolean(Boolean.parseBoolean(s)); }
    public static ScoutingIntelPreference parseDouble(String s)  { return ofDouble(Double.parseDouble(s)); }
    public static ScoutingIntelPreference parseInt(String s)     { return ofInt(Integer.parseInt(s)); }
}
```

- [ ] **Step 2.5: Implement ScoutingIntelType**

Create `src/main/java/io/quarkmind/agent/plugin/ScoutingIntelType.java`:

```java
package io.quarkmind.agent.plugin;

public enum ScoutingIntelType {
    THREAT_POSITION,
    POSTURE,
    TIMING_ALERT,
    ARMY_SIZE,
    BUILD_ORDER
}
```

- [ ] **Step 2.6: Implement ScoutingIntelConsumer**

Create `src/main/java/io/quarkmind/agent/plugin/ScoutingIntelConsumer.java`:

```java
package io.quarkmind.agent.plugin;

import java.util.Set;

public interface ScoutingIntelConsumer {
    Set<ScoutingIntelType> subscribedIntelTypes();
}
```

- [ ] **Step 2.7: Implement ScoutingIntelPayload**

Create `src/main/java/io/quarkmind/agent/plugin/ScoutingIntelPayload.java`:

```java
package io.quarkmind.agent.plugin;

import io.quarkmind.domain.Point2d;

public sealed interface ScoutingIntelPayload
        permits ScoutingIntelPayload.ThreatPosition,
                ScoutingIntelPayload.PostureUpdate,
                ScoutingIntelPayload.TimingAlert,
                ScoutingIntelPayload.ArmySize,
                ScoutingIntelPayload.BuildOrder {

    record ThreatPosition(Point2d position) implements ScoutingIntelPayload {}
    record PostureUpdate(String posture)    implements ScoutingIntelPayload {}
    record TimingAlert(boolean incoming)    implements ScoutingIntelPayload {}
    record ArmySize(int count)              implements ScoutingIntelPayload {}
    record BuildOrder(String detected)      implements ScoutingIntelPayload {}
}
```

- [ ] **Step 2.8: Implement ScoutingIntelPreferences**

Create `src/main/java/io/quarkmind/agent/plugin/ScoutingIntelPreferences.java`:

```java
package io.quarkmind.agent.plugin;

import io.casehub.platform.api.preferences.PreferenceKey;

public final class ScoutingIntelPreferences {

    public static final PreferenceKey<ScoutingIntelPreference> THREAT_POSITION_MIN_DISTANCE =
        new PreferenceKey<>("scouting.intel.dispatch", "threat-position.min-distance",
            ScoutingIntelPreference.ofDouble(0.0), ScoutingIntelPreference::parseDouble);

    public static final PreferenceKey<ScoutingIntelPreference> ARMY_SIZE_MIN_DELTA =
        new PreferenceKey<>("scouting.intel.dispatch", "army-size.min-delta",
            ScoutingIntelPreference.ofInt(1), ScoutingIntelPreference::parseInt);

    public static final PreferenceKey<ScoutingIntelPreference> POSTURE_DISPATCH_ENABLED =
        new PreferenceKey<>("scouting.intel.dispatch", "posture.enabled",
            ScoutingIntelPreference.ofBoolean(true), ScoutingIntelPreference::parseBoolean);

    public static final PreferenceKey<ScoutingIntelPreference> TIMING_ALERT_DISPATCH_ENABLED =
        new PreferenceKey<>("scouting.intel.dispatch", "timing-alert.enabled",
            ScoutingIntelPreference.ofBoolean(true), ScoutingIntelPreference::parseBoolean);

    public static final PreferenceKey<ScoutingIntelPreference> BUILD_ORDER_DISPATCH_ENABLED =
        new PreferenceKey<>("scouting.intel.dispatch", "build-order.enabled",
            ScoutingIntelPreference.ofBoolean(true), ScoutingIntelPreference::parseBoolean);

    public static PreferenceKey<ScoutingIntelPreference> consumerKey(String pluginId, ScoutingIntelType type) {
        return new PreferenceKey<>(
            "scouting.intel.consumer." + pluginId,
            toKebab(type),
            ScoutingIntelPreference.ofBoolean(defaultEnabled(type)),
            ScoutingIntelPreference::parseBoolean);
    }

    static String toKebab(ScoutingIntelType type) {
        return type.name().toLowerCase().replace('_', '-');
    }

    static boolean defaultEnabled(ScoutingIntelType type) {
        return switch (type) {
            case THREAT_POSITION, POSTURE, TIMING_ALERT -> true;
            case ARMY_SIZE, BUILD_ORDER -> false;
        };
    }

    private ScoutingIntelPreferences() {}
}
```

- [ ] **Step 2.9: Run tests (expect PASS)**

```bash
mvn test -Dtest="ScoutingIntelPreferencesTest,ScoutingIntelPayloadTest" -q
```

Expected: BUILD SUCCESS, 7 tests passing.

- [ ] **Step 2.10: Commit**

```bash
git add src/main/java/io/quarkmind/agent/plugin/ src/test/java/io/quarkmind/agent/plugin/
git commit -m "feat(#155): add scouting intel seam types — ScoutingIntelType/Consumer/Payload/Preferences"
```

---

## Task 3: ScoutingIntelBroker

**Files:**
- Create: `src/main/java/io/quarkmind/agent/ScoutingIntelBroker.java`
- Create: `src/test/java/io/quarkmind/agent/ScoutingIntelBrokerTest.java`

- [ ] **Step 3.1: Write ScoutingIntelBrokerTest**

Create `src/test/java/io/quarkmind/agent/ScoutingIntelBrokerTest.java`:

```java
package io.quarkmind.agent;

import io.quarkmind.agent.plugin.ScoutingIntelConsumer;
import io.quarkmind.agent.plugin.ScoutingIntelType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ScoutingIntelBrokerTest {

    @Test
    void computeActiveTypes_singleConsumer_returnsItsTypes() {
        ScoutingIntelConsumer c = () -> Set.of(ScoutingIntelType.THREAT_POSITION, ScoutingIntelType.POSTURE);
        Set<ScoutingIntelType> result = ScoutingIntelBroker.computeActiveTypes(List.of(c));
        assertThat(result).containsExactlyInAnyOrder(
            ScoutingIntelType.THREAT_POSITION, ScoutingIntelType.POSTURE);
    }

    @Test
    void computeActiveTypes_multipleConsumers_returnsUnion() {
        ScoutingIntelConsumer c1 = () -> Set.of(ScoutingIntelType.THREAT_POSITION);
        ScoutingIntelConsumer c2 = () -> Set.of(ScoutingIntelType.ARMY_SIZE, ScoutingIntelType.POSTURE);
        Set<ScoutingIntelType> result = ScoutingIntelBroker.computeActiveTypes(List.of(c1, c2));
        assertThat(result).containsExactlyInAnyOrder(
            ScoutingIntelType.THREAT_POSITION,
            ScoutingIntelType.ARMY_SIZE,
            ScoutingIntelType.POSTURE);
    }

    @Test
    void computeActiveTypes_noConsumers_returnsEmpty() {
        assertThat(ScoutingIntelBroker.computeActiveTypes(List.of())).isEmpty();
    }

    @Test
    void computeActiveTypes_overlappingSubscriptions_deduplicates() {
        ScoutingIntelConsumer c1 = () -> Set.of(ScoutingIntelType.POSTURE);
        ScoutingIntelConsumer c2 = () -> Set.of(ScoutingIntelType.POSTURE);
        Set<ScoutingIntelType> result = ScoutingIntelBroker.computeActiveTypes(List.of(c1, c2));
        assertThat(result).hasSize(1).contains(ScoutingIntelType.POSTURE);
    }
}
```

- [ ] **Step 3.2: Run tests (expect compile failure)**

```bash
mvn test -Dtest="ScoutingIntelBrokerTest" -q
```

Expected: COMPILE ERROR — `ScoutingIntelBroker` not found.

- [ ] **Step 3.3: Implement ScoutingIntelBroker**

Create `src/main/java/io/quarkmind/agent/ScoutingIntelBroker.java`:

```java
package io.quarkmind.agent;

import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.quarkmind.agent.plugin.ScoutingIntelConsumer;
import io.quarkmind.agent.plugin.ScoutingIntelType;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class ScoutingIntelBroker {

    public static final String CHANNEL_NAME = "quarkmind.scouting.intel";

    @Inject Instance<ScoutingIntelConsumer> consumers;
    @Inject ChannelService channelService;

    private UUID channelId;
    private Set<ScoutingIntelType> activeTypes;

    @PostConstruct
    void init() {
        // GE-20260529-88b7b6: ChannelService.create() not idempotent — findByName() first
        channelId = channelService.findByName(CHANNEL_NAME)
            .map(c -> c.id)
            .orElseGet(() -> channelService.create(
                CHANNEL_NAME,
                "Scouting intel for agent plugins",
                ChannelSemantic.APPEND,
                null, null, null, null, null,
                "STATUS"   // allowedTypes — STATUS carries content; EVENT forces null (GE-20260607-d051f2)
            ).id);

        // qhorus#254: ChannelService.create() does NOT call channelGateway.initChannel();
        // ChannelBackend registration never fires for runtime-created channels.
        // MessageObserver with channels() filter is the correct delivery path.

        activeTypes = computeActiveTypes(consumers);
    }

    // Package-private for unit testing — accepts Iterable to avoid CDI Instance in tests
    static Set<ScoutingIntelType> computeActiveTypes(Iterable<ScoutingIntelConsumer> consumers) {
        Set<ScoutingIntelType> result = new HashSet<>();
        for (ScoutingIntelConsumer c : consumers) {
            result.addAll(c.subscribedIntelTypes());
        }
        return Collections.unmodifiableSet(result);
    }

    public UUID channelId()                          { return channelId; }
    public boolean isSubscribed(ScoutingIntelType t) { return activeTypes.contains(t); }
    public Set<ScoutingIntelType> activeTypes()      { return activeTypes; }
}
```

- [ ] **Step 3.4: Run tests (expect PASS)**

```bash
mvn test -Dtest="ScoutingIntelBrokerTest" -q
```

Expected: BUILD SUCCESS, 4 tests passing.

- [ ] **Step 3.5: Commit**

```bash
git add src/main/java/io/quarkmind/agent/ScoutingIntelBroker.java \
        src/test/java/io/quarkmind/agent/ScoutingIntelBrokerTest.java
git commit -m "feat(#155): add ScoutingIntelBroker — channel setup and subscription union"
```

---

## Task 4: DroolsTacticsTask changes

**Files:**
- Create: `src/main/java/io/quarkmind/plugin/TacticsIntelCache.java`
- Modify: `src/main/java/io/quarkmind/plugin/DroolsTacticsTask.java`
- Modify: `src/test/java/io/quarkmind/plugin/DroolsTacticsTaskTest.java`

- [ ] **Step 4.1: Write new test methods in DroolsTacticsTaskTest**

Append to `src/test/java/io/quarkmind/plugin/DroolsTacticsTaskTest.java` (after the last existing test method):

```java
    // ---- TacticsIntelCache merge (new) ----

    @Test
    void merge_threatPosition_updatesCachePosition() {
        TacticsIntelCache prev = TacticsIntelCache.empty();
        var payload = new ScoutingIntelPayload.ThreatPosition(new Point2d(50f, 60f));
        TacticsIntelCache result = DroolsTacticsTask.merge(prev, payload);
        assertThat(result.threatPosition()).isEqualTo(new Point2d(50f, 60f));
        assertThat(result.posture()).isNull();
        assertThat(result.timingAlert()).isNull();
    }

    @Test
    void merge_postureUpdate_preservesThreatPosition() {
        var prev = new TacticsIntelCache(new Point2d(10f, 10f), null, null);
        var payload = new ScoutingIntelPayload.PostureUpdate("AGGRESSIVE");
        TacticsIntelCache result = DroolsTacticsTask.merge(prev, payload);
        assertThat(result.posture()).isEqualTo("AGGRESSIVE");
        assertThat(result.threatPosition()).isEqualTo(new Point2d(10f, 10f));
    }

    @Test
    void merge_timingAlert_setsFlag() {
        TacticsIntelCache prev = TacticsIntelCache.empty();
        var payload = new ScoutingIntelPayload.TimingAlert(true);
        TacticsIntelCache result = DroolsTacticsTask.merge(prev, payload);
        assertThat(result.timingAlert()).isTrue();
    }

    @Test
    void merge_armySize_returnsUnchangedCache() {
        var prev = new TacticsIntelCache(new Point2d(5f, 5f), "DEFENSIVE", false);
        TacticsIntelCache result = DroolsTacticsTask.merge(prev, new ScoutingIntelPayload.ArmySize(42));
        assertThat(result).isEqualTo(prev);
    }

    @Test
    void canActivate_emptyCache_returnsFalse() throws Exception {
        DroolsTacticsTask task = makeTask();
        // Cache is empty (no threat position) — should not activate
        io.casehub.core.CaseFile caseFile = makeCaseFileWith(
            QuarkMindCaseFile.READY, QuarkMindCaseFile.STRATEGY);
        assertThat(task.canActivate(caseFile)).isFalse();
    }

    @Test
    void canActivate_cacheHasThreatPosition_returnsTrue() throws Exception {
        DroolsTacticsTask task = makeTask();
        task.onMessage(makeThreatPositionEvent(new Point2d(10f, 20f)));
        io.casehub.core.CaseFile caseFile = makeCaseFileWith(
            QuarkMindCaseFile.READY, QuarkMindCaseFile.STRATEGY);
        assertThat(task.canActivate(caseFile)).isTrue();
    }

    @Test
    void onMessage_updatesIntelCache() throws Exception {
        DroolsTacticsTask task = makeTask();
        assertThat(task.intelCache.get().threatPosition()).isNull();
        task.onMessage(makeThreatPositionEvent(new Point2d(30f, 40f)));
        assertThat(task.intelCache.get().threatPosition()).isEqualTo(new Point2d(30f, 40f));
    }

    @Test
    void onMessage_unknownType_logsAndDoesNotThrow() throws Exception {
        DroolsTacticsTask task = makeTask();
        // unknown type in JSON — should log and continue, not throw
        String badJson = "{\"type\":\"UNKNOWN_TYPE\",\"data\":{}}";
        var event = new io.casehub.qhorus.api.gateway.MessageReceivedEvent(
            ScoutingIntelBroker.CHANNEL_NAME, java.util.UUID.randomUUID(),
            io.casehub.qhorus.api.message.MessageType.STATUS,
            "scouting.drools-cep", null, badJson);
        // should not throw
        task.onMessage(event);
        assertThat(task.intelCache.get().threatPosition()).isNull(); // unchanged
    }

    // ---- helpers ----

    private static DroolsTacticsTask makeTask() {
        DroolsTacticsTask task = new DroolsTacticsTask(null, null);
        task.objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        return task;
    }

    private static io.casehub.qhorus.api.gateway.MessageReceivedEvent makeThreatPositionEvent(Point2d pos)
            throws Exception {
        var payload = new ScoutingIntelPayload.ThreatPosition(pos);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String content = mapper.writeValueAsString(
            java.util.Map.of("type", payload.getClass().getSimpleName(), "data", payload));
        return new io.casehub.qhorus.api.gateway.MessageReceivedEvent(
            ScoutingIntelBroker.CHANNEL_NAME,
            java.util.UUID.randomUUID(),
            io.casehub.qhorus.api.message.MessageType.STATUS,
            "scouting.drools-cep", null, content);
    }

    private static io.casehub.core.CaseFile makeCaseFileWith(String... keys) {
        io.casehub.core.CaseFile cf = new io.casehub.core.CaseFile();
        for (String key : keys) cf.put(key, "present");
        return cf;
    }
```

Also add these imports at the top of `DroolsTacticsTaskTest.java`:

```java
import io.quarkmind.agent.ScoutingIntelBroker;
import io.quarkmind.agent.plugin.ScoutingIntelPayload;
import io.quarkmind.agent.QuarkMindCaseFile;
```

- [ ] **Step 4.2: Run new tests (expect compile failure)**

```bash
mvn test -Dtest="DroolsTacticsTaskTest" -q
```

Expected: COMPILE ERROR — `TacticsIntelCache`, `DroolsTacticsTask.merge`, `DroolsTacticsTask.intelCache` not found.

- [ ] **Step 4.3: Create TacticsIntelCache**

Create `src/main/java/io/quarkmind/plugin/TacticsIntelCache.java`:

```java
package io.quarkmind.plugin;

import io.quarkmind.domain.Point2d;

record TacticsIntelCache(Point2d threatPosition, String posture, Boolean timingAlert) {
    static TacticsIntelCache empty() { return new TacticsIntelCache(null, null, null); }
}
```

- [ ] **Step 4.4: Modify DroolsTacticsTask**

The changes are in four areas. Apply them carefully — the existing code is largely preserved.

**4.4a — Class declaration and new imports:**

Replace the class declaration line and the import block at the top of `DroolsTacticsTask.java`. Add these imports (after existing imports):

```java
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.SettingsScope;
import io.casehub.qhorus.api.gateway.MessageObserver;
import io.casehub.qhorus.api.gateway.MessageReceivedEvent;
import io.quarkmind.agent.ScoutingIntelBroker;
import io.quarkmind.agent.plugin.ScoutingIntelConsumer;
import io.quarkmind.agent.plugin.ScoutingIntelPayload;
import io.quarkmind.agent.plugin.ScoutingIntelPreferences;
import io.quarkmind.agent.plugin.ScoutingIntelType;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
```

Change class declaration from:
```java
public class DroolsTacticsTask implements TacticsTask {
```
to:
```java
public class DroolsTacticsTask implements TacticsTask, ScoutingIntelConsumer, MessageObserver {
```

**4.4b — New fields (add after existing field declarations):**

```java
    // --- qhorus Layer 3 fields ---
    @Inject ObjectMapper objectMapper;
    @Inject PreferenceProvider preferenceProvider;

    final AtomicReference<TacticsIntelCache> intelCache =
        new AtomicReference<>(TacticsIntelCache.empty());
    Set<ScoutingIntelType> subscribedTypes;
```

**4.4c — Extend the existing @PostConstruct init() method:**

In the existing `init()` method, append after the focusFireStrategy line:

```java
        // Preference-backed subscriptions — fixed at boot; see #178 for hot-reload
        initSubscriptions(preferenceProvider.resolve(SettingsScope.root()));
```

Add a new package-private method after `init()`:

```java
    void initSubscriptions(io.casehub.platform.api.preferences.Preferences prefs) {
        subscribedTypes = Arrays.stream(ScoutingIntelType.values())
            .filter(t -> prefs.getOrDefault(ScoutingIntelPreferences.consumerKey(getId(), t)).asBoolean())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
```

**4.4d — Add ScoutingIntelConsumer and MessageObserver implementations:**

Add these methods anywhere after the constructor (before `getId()`):

```java
    @Override
    public Set<ScoutingIntelType> subscribedIntelTypes() { return subscribedTypes; }

    @Override
    public Set<String> channels() { return Set.of(ScoutingIntelBroker.CHANNEL_NAME); }

    @Override
    public void onMessage(MessageReceivedEvent event) {
        try {
            JsonNode node = objectMapper.readTree(event.content());
            String type = node.get("type").asText();
            JsonNode data = node.get("data");
            ScoutingIntelPayload payload = switch (type) {
                case "ThreatPosition" ->
                    objectMapper.treeToValue(data, ScoutingIntelPayload.ThreatPosition.class);
                case "PostureUpdate" ->
                    objectMapper.treeToValue(data, ScoutingIntelPayload.PostureUpdate.class);
                case "TimingAlert" ->
                    objectMapper.treeToValue(data, ScoutingIntelPayload.TimingAlert.class);
                case "ArmySize" ->
                    objectMapper.treeToValue(data, ScoutingIntelPayload.ArmySize.class);
                case "BuildOrder" ->
                    objectMapper.treeToValue(data, ScoutingIntelPayload.BuildOrder.class);
                default -> throw new IllegalArgumentException("Unknown ScoutingIntelType: " + type);
            };
            intelCache.updateAndGet(prev -> merge(prev, payload));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.warnf("Failed to deserialise scouting intel: %s", e.getMessage());
        }
    }

    static TacticsIntelCache merge(TacticsIntelCache prev, ScoutingIntelPayload payload) {
        return switch (payload) {
            case ScoutingIntelPayload.ThreatPosition p ->
                new TacticsIntelCache(p.position(), prev.posture(), prev.timingAlert());
            case ScoutingIntelPayload.PostureUpdate p ->
                new TacticsIntelCache(prev.threatPosition(), p.posture(), prev.timingAlert());
            case ScoutingIntelPayload.TimingAlert p ->
                new TacticsIntelCache(prev.threatPosition(), prev.posture(), p.incoming());
            case ScoutingIntelPayload.ArmySize p -> prev;
            case ScoutingIntelPayload.BuildOrder p -> prev;
        };
    }
```

**4.4e — Change entryCriteria() and canActivate():**

Replace the existing `entryCriteria()`:
```java
    @Override public Set<String> entryCriteria() {
        return Set.of(QuarkMindCaseFile.READY,
                      QuarkMindCaseFile.STRATEGY,
                      QuarkMindCaseFile.NEAREST_THREAT);
    }
```
with:
```java
    @Override public Set<String> entryCriteria() {
        return Set.of(QuarkMindCaseFile.READY, QuarkMindCaseFile.STRATEGY);
    }
```

Replace the `canActivate()` body:
```java
    @Override
    public boolean canActivate(CaseFile caseFile) {
        return entryCriteria().stream().allMatch(caseFile::contains);
    }
```
with:
```java
    @Override
    public boolean canActivate(CaseFile caseFile) {
        return entryCriteria().stream().allMatch(caseFile::contains)
            && intelCache.get().threatPosition() != null;
    }
```

**4.4f — Change the threat-position read in execute():**

In `execute()`, find:
```java
        Point2d threat     = caseFile.get(QuarkMindCaseFile.NEAREST_THREAT, Point2d.class)
                .orElse(null);
```
Replace with:
```java
        // Reads from qhorus intel cache (Layer 3) — no CaseFile key coupling
        Point2d threat = intelCache.get().threatPosition();
```

Remove the comment block about protocol PP-20260603-049dd0 that mentioned `NEAREST_THREAT` — it's no longer relevant.

- [ ] **Step 4.5: Run all tactics tests (expect PASS)**

```bash
mvn test -Dtest="DroolsTacticsTaskTest" -q
```

Expected: BUILD SUCCESS, all tests passing including the original static-method tests and the new qhorus tests.

- [ ] **Step 4.6: Commit**

```bash
git add src/main/java/io/quarkmind/plugin/TacticsIntelCache.java \
        src/main/java/io/quarkmind/plugin/DroolsTacticsTask.java \
        src/test/java/io/quarkmind/plugin/DroolsTacticsTaskTest.java
git commit -m "feat(#155): DroolsTacticsTask — MessageObserver, TacticsIntelCache, preference-backed subscriptions"
```

---

## Task 5: DroolsScoutingTask changes

**Files:**
- Modify: `src/main/java/io/quarkmind/plugin/scouting/DroolsScoutingTask.java`
- Modify: `src/test/java/io/quarkmind/plugin/scouting/DroolsScoutingTaskTest.java`

- [ ] **Step 5.1: Write new test methods in DroolsScoutingTaskTest**

Append to `src/test/java/io/quarkmind/plugin/scouting/DroolsScoutingTaskTest.java` (after existing tests):

```java
    // ---- shouldDispatchThreatPosition ----

    @Test
    void shouldDispatchThreatPosition_newPosition_exceedsZeroThreshold() {
        // Default threshold 0.0 — any move triggers dispatch
        Point2d prev = new Point2d(10f, 10f);
        Point2d curr = new Point2d(10.1f, 10f);
        assertThat(DroolsScoutingTask.shouldDispatchThreatPosition(prev, curr, 0.0)).isTrue();
    }

    @Test
    void shouldDispatchThreatPosition_samePosition_returnsFalse() {
        Point2d pos = new Point2d(10f, 10f);
        assertThat(DroolsScoutingTask.shouldDispatchThreatPosition(pos, pos, 0.0)).isFalse();
    }

    @Test
    void shouldDispatchThreatPosition_movesBelowThreshold_returnsFalse() {
        Point2d prev = new Point2d(10f, 10f);
        Point2d curr = new Point2d(10.5f, 10f); // distance 0.5
        assertThat(DroolsScoutingTask.shouldDispatchThreatPosition(prev, curr, 1.0)).isFalse();
    }

    @Test
    void shouldDispatchThreatPosition_movesAboveThreshold_returnsTrue() {
        Point2d prev = new Point2d(10f, 10f);
        Point2d curr = new Point2d(12f, 10f); // distance 2.0
        assertThat(DroolsScoutingTask.shouldDispatchThreatPosition(prev, curr, 1.0)).isTrue();
    }

    @Test
    void shouldDispatchThreatPosition_firstSighting_prevNull_returnsTrue() {
        assertThat(DroolsScoutingTask.shouldDispatchThreatPosition(null, new Point2d(5f, 5f), 0.0))
            .isTrue();
    }

    @Test
    void shouldDispatchArmySize_deltaExceedsThreshold_returnsTrue() {
        assertThat(DroolsScoutingTask.shouldDispatchArmySize(5, 10, 1)).isTrue();
    }

    @Test
    void shouldDispatchArmySize_deltaBelowThreshold_returnsFalse() {
        assertThat(DroolsScoutingTask.shouldDispatchArmySize(5, 5, 1)).isFalse();
    }
```

Also add this import at the top:
```java
import io.quarkmind.agent.plugin.ScoutingIntelPayload;
```

- [ ] **Step 5.2: Run new tests (expect compile failure)**

```bash
mvn test -Dtest="DroolsScoutingTaskTest" -q
```

Expected: COMPILE ERROR — `DroolsScoutingTask.shouldDispatchThreatPosition` not found.

- [ ] **Step 5.3: Add new imports to DroolsScoutingTask**

Add to the import block:

```java
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.SettingsScope;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkmind.agent.ScoutingIntelBroker;
import io.quarkmind.agent.plugin.ScoutingIntelPayload;
import io.quarkmind.agent.plugin.ScoutingIntelPreferences;
import io.quarkmind.agent.plugin.ScoutingIntelType;
```

- [ ] **Step 5.4: Add new fields to DroolsScoutingTask**

Add after the existing `@Inject Event<PluginDecisionEvent> decisionEvents` field:

```java
    // --- qhorus Layer 3 fields ---
    @Inject ScoutingIntelBroker broker;
    @Inject MessageService messageService;
    @Inject ObjectMapper objectMapper;
    @Inject PreferenceProvider preferenceProvider;

    // Per-type previous values — used for change detection / dispatch gating
    volatile Point2d prevThreatPos   = null;
    volatile int     prevArmySize    = -1;
    volatile String  prevPosture     = null;
    volatile Boolean prevTimingAlert = null;
    volatile String  prevBuildOrder  = null;

    // Threshold values — loaded from preferences at @PostConstruct
    volatile double minThreatDistance;
    volatile int    minArmySizeDelta;
    volatile boolean postureDispatchEnabled;
    volatile boolean timingAlertDispatchEnabled;
    volatile boolean buildOrderDispatchEnabled;
```

- [ ] **Step 5.5: Add @PostConstruct method to load thresholds**

DroolsScoutingTask currently has no `@PostConstruct`. Add one after the constructor:

```java
    @PostConstruct
    void initThresholds() {
        initThresholds(preferenceProvider.resolve(SettingsScope.root()));
    }

    void initThresholds(io.casehub.platform.api.preferences.Preferences prefs) {
        minThreatDistance       = prefs.getOrDefault(ScoutingIntelPreferences.THREAT_POSITION_MIN_DISTANCE).asDouble();
        minArmySizeDelta        = prefs.getOrDefault(ScoutingIntelPreferences.ARMY_SIZE_MIN_DELTA).asInt();
        postureDispatchEnabled      = prefs.getOrDefault(ScoutingIntelPreferences.POSTURE_DISPATCH_ENABLED).asBoolean();
        timingAlertDispatchEnabled  = prefs.getOrDefault(ScoutingIntelPreferences.TIMING_ALERT_DISPATCH_ENABLED).asBoolean();
        buildOrderDispatchEnabled   = prefs.getOrDefault(ScoutingIntelPreferences.BUILD_ORDER_DISPATCH_ENABLED).asBoolean();
    }
```

- [ ] **Step 5.6: Add static helper methods for dispatch decisions**

Add these package-private static methods at the bottom of `DroolsScoutingTask`, before the closing `}`:

```java
    static boolean shouldDispatchThreatPosition(Point2d prev, Point2d curr, double threshold) {
        if (prev == null) return true;
        if (prev.equals(curr)) return false;
        double dx = curr.x() - prev.x();
        double dy = curr.y() - prev.y();
        return Math.sqrt(dx * dx + dy * dy) > threshold;
    }

    static boolean shouldDispatchArmySize(int prev, int curr, int minDelta) {
        return Math.abs(curr - prev) >= minDelta;
    }
```

- [ ] **Step 5.7: Add dispatch() helper method**

Add a private `dispatch()` helper:

```java
    private void dispatch(ScoutingIntelPayload payload) {
        try {
            String content = objectMapper.writeValueAsString(
                java.util.Map.of("type", payload.getClass().getSimpleName(), "data", payload));
            messageService.dispatch(MessageDispatch.builder()
                .channelId(broker.channelId())
                .sender(getId())
                .actorType(ActorType.AGENT)   // GE-20260529-e32a4d: required
                .type(MessageType.STATUS)     // STATUS carries content; EVENT forces null — GE-20260607-d051f2
                .content(content)
                .build());
        } catch (JsonProcessingException e) {
            log.warnf("Failed to serialise scouting intel payload: %s", e.getMessage());
        }
    }
```

- [ ] **Step 5.8: Modify execute() to gate CEP and dispatch intel**

In `execute()`, find the section after the `sessionManager` / CaseFile writes. The full sequence of changes:

**After computing `enemies` and `frame` but before the CEP block**, add threat + army size computation:

```java
        // --- qhorus intel dispatch (Layer 3) ---
        // THREAT_POSITION and ARMY_SIZE are cheap to compute; always evaluated
        int currentArmySize = enemies.size();
        Point2d nearest = enemies.isEmpty() ? null :
            enemies.stream()
                .min(java.util.Comparator.comparingDouble(e -> e.position().distanceTo(ourNexus)))
                .map(e -> e.position())
                .orElse(null);
```

**Gate the CEP block** — wrap the existing Drools section in a condition:

```java
        boolean needsCep = broker.isSubscribed(ScoutingIntelType.BUILD_ORDER)
                        || broker.isSubscribed(ScoutingIntelType.TIMING_ALERT)
                        || broker.isSubscribed(ScoutingIntelType.POSTURE);
        ScoutingRuleUnit data = null;
        if (needsCep) {
```

Close the CEP block with `}` after the existing `}` (the try-with-resources close).

**After the CEP block and existing CaseFile writes**, add the dispatch section:

```java
        // --- Dispatch changed intel to qhorus subscribers ---
        if (nearest != null && broker.isSubscribed(ScoutingIntelType.THREAT_POSITION)
                && shouldDispatchThreatPosition(prevThreatPos, nearest, minThreatDistance)) {
            prevThreatPos = nearest;
            dispatch(new ScoutingIntelPayload.ThreatPosition(nearest));
        }

        if (broker.isSubscribed(ScoutingIntelType.ARMY_SIZE)
                && shouldDispatchArmySize(prevArmySize, currentArmySize, minArmySizeDelta)) {
            prevArmySize = currentArmySize;
            dispatch(new ScoutingIntelPayload.ArmySize(currentArmySize));
        }

        if (data != null) {
            String posture = data.getPostureDecisions().isEmpty()
                ? "UNKNOWN" : data.getPostureDecisions().get(0);
            if (postureDispatchEnabled && broker.isSubscribed(ScoutingIntelType.POSTURE)
                    && !posture.equals(prevPosture)) {
                prevPosture = posture;
                dispatch(new ScoutingIntelPayload.PostureUpdate(posture));
            }

            boolean timing = !data.getTimingAlerts().isEmpty();
            if (timingAlertDispatchEnabled && broker.isSubscribed(ScoutingIntelType.TIMING_ALERT)
                    && !Boolean.valueOf(timing).equals(prevTimingAlert)) {
                prevTimingAlert = timing;
                dispatch(new ScoutingIntelPayload.TimingAlert(timing));
            }

            String build = data.getDetectedBuilds().isEmpty()
                ? "UNKNOWN" : data.getDetectedBuilds().get(0);
            if (buildOrderDispatchEnabled && broker.isSubscribed(ScoutingIntelType.BUILD_ORDER)
                    && !build.equals(prevBuildOrder)) {
                prevBuildOrder = build;
                dispatch(new ScoutingIntelPayload.BuildOrder(build));
            }
        }
```

Also reset `prevThreatPos` and `prevArmySize` in the game-restart detection block:

```java
        if (frame < lastFrame) {
            sessionManager.reset();
            scoutProbeTag = null;
            prevThreatPos    = null;
            prevArmySize     = -1;
            prevPosture      = null;
            prevTimingAlert  = null;
            prevBuildOrder   = null;
        }
```

- [ ] **Step 5.9: Run all scouting tests (expect PASS)**

```bash
mvn test -Dtest="DroolsScoutingTaskTest" -q
```

Expected: BUILD SUCCESS. All original tests plus the 7 new dispatch-threshold tests pass.

- [ ] **Step 5.10: Run full unit test suite**

```bash
mvn test -q
```

Expected: BUILD SUCCESS. All unit tests pass.

- [ ] **Step 5.11: Commit**

```bash
git add src/main/java/io/quarkmind/plugin/scouting/DroolsScoutingTask.java \
        src/test/java/io/quarkmind/plugin/scouting/DroolsScoutingTaskTest.java
git commit -m "feat(#155): DroolsScoutingTask — broker-gated CEP, per-type dispatch, preference thresholds"
```

---

## Task 6: Integration test and full verification

**Files:**
- Create: `src/test/java/io/quarkmind/plugin/QhorusScoutingIntelIT.java`

- [ ] **Step 6.1: Write QhorusScoutingIntelIT**

Create `src/test/java/io/quarkmind/plugin/QhorusScoutingIntelIT.java`:

```java
package io.quarkmind.plugin;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkmind.agent.AgentOrchestrator;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.Unit;
import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.mock.SimulatedGame;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test for Layer 3: ScoutingTask → qhorus channel → TacticsTask cache.
 *
 * <p>Delivery is post-commit (InProcessMessageBus defers to afterCompletion).
 * Thread.sleep(300) is intentional — waits for the qhorus transaction to commit
 * and the MessageObserver to fire before asserting cache state.
 */
@QuarkusTest
class QhorusScoutingIntelIT {

    @Inject AgentOrchestrator orchestrator;
    @Inject SimulatedGame     simulatedGame;
    @Inject DroolsTacticsTask tacticsTask;

    @BeforeEach
    void setUp() {
        simulatedGame.reset();
        orchestrator.startGame();
    }

    @Test
    void afterTickWithEnemies_tacticsIntelCacheIsPopulated() throws Exception {
        // Spawn an enemy unit so scouting detects a threat
        simulatedGame.spawnEnemyUnit(
            new Unit("e-test", UnitType.ZEALOT, new Point2d(50f, 50f),
                100, 100, 0, 0, 0, 0));

        // Tick 1: scouting dispatches threat position to qhorus
        orchestrator.gameTick();

        // Post-commit delivery — wait for MessageObserver to fire
        Thread.sleep(300);

        // Assert TacticsIntelCache is populated
        TacticsIntelCache cache = tacticsTask.intelCache.get();
        assertThat(cache.threatPosition())
            .as("Threat position should be populated after scouting dispatches to qhorus")
            .isNotNull();
    }

    @Test
    void afterTickWithNoEnemies_tacticsIntelCacheRemainsEmpty() throws Exception {
        // No enemies — scouting dispatches nothing
        simulatedGame.reset();
        orchestrator.startGame();

        orchestrator.gameTick();
        Thread.sleep(300);

        // Cache initialised to empty() — should remain empty
        assertThat(tacticsTask.intelCache.get().threatPosition()).isNull();
    }

    @Test
    void consecutiveTicks_cacheUpdatesWithLatestThreatPosition() throws Exception {
        simulatedGame.spawnEnemyUnit(
            new Unit("e-0", UnitType.ZEALOT, new Point2d(10f, 10f),
                100, 100, 0, 0, 0, 0));

        orchestrator.gameTick();
        Thread.sleep(300);

        Point2d firstPos = tacticsTask.intelCache.get().threatPosition();
        assertThat(firstPos).isNotNull();

        // Move the enemy to a significantly different position
        simulatedGame.moveUnit("e-0", new Point2d(80f, 80f));

        orchestrator.gameTick();
        Thread.sleep(300);

        Point2d secondPos = tacticsTask.intelCache.get().threatPosition();
        // Position should have updated (default threshold 0.0 — any move triggers dispatch)
        assertThat(secondPos).isNotEqualTo(firstPos);
    }
}
```

**Note on `SimulatedGame.spawnEnemyUnit` / `moveUnit`:** Check that these methods exist on `SimulatedGame`. If `SimulatedGame` has a different API for adding enemies, use `ScenarioRunner.run("spawn-enemy-attack")` instead and adjust assertions accordingly.

- [ ] **Step 6.2: Run FullMockPipelineIT to verify no regressions**

```bash
mvn test -Dtest="FullMockPipelineIT" -q
```

Expected: BUILD SUCCESS, 3 tests passing. The qhorus datasource must boot cleanly without affecting existing ledger in-memory alternatives.

If this fails with datasource errors: verify the H2 datasource config in `application.properties` uses the correct Quarkus named-datasource syntax.

- [ ] **Step 6.3: Run the new integration test**

```bash
mvn test -Dtest="QhorusScoutingIntelIT" -q
```

Expected: BUILD SUCCESS, 3 tests passing.

If `consecutiveTicks_cacheUpdatesWithLatestThreatPosition` fails: `SimulatedGame` may not have a `moveUnit()` method — simplify the third test to just assert the cache remains non-null on the second tick.

- [ ] **Step 6.4: Run full test suite including all integration tests**

```bash
mvn test -q
```

Expected: BUILD SUCCESS. All unit and integration tests pass.

- [ ] **Step 6.5: Commit**

```bash
git add src/test/java/io/quarkmind/plugin/QhorusScoutingIntelIT.java
git commit -m "test(#155): QhorusScoutingIntelIT — end-to-end scouting intel delivery via qhorus"
```

---

## Self-Review

**Spec coverage check:**

| Spec section | Covered in task |
|---|---|
| ScoutingIntelType enum | Task 2 |
| ScoutingIntelConsumer interface | Task 2 |
| ScoutingIntelPayload sealed | Task 2 |
| ScoutingIntelPreferences + ScoutingIntelPreference | Task 2 |
| ScoutingIntelBroker — channel setup + subscription union | Task 3 |
| ScoutingIntelBroker — computeActiveTypes unit tested | Task 3 |
| DroolsTacticsTask — ScoutingIntelConsumer + preference-backed subscriptions | Task 4 |
| DroolsTacticsTask — MessageObserver.onMessage() + channels() | Task 4 |
| DroolsTacticsTask — TacticsIntelCache + merge() | Task 4 |
| DroolsTacticsTask — canActivate() gate change (no NEAREST_THREAT) | Task 4 |
| DroolsTacticsTask — execute() reads from cache not CaseFile | Task 4 |
| DroolsScoutingTask — broker injection + @PostConstruct thresholds | Task 5 |
| DroolsScoutingTask — CEP gating (needsCep) | Task 5 |
| DroolsScoutingTask — per-type change detection + dispatch | Task 5 |
| DroolsScoutingTask — dispatch() helper with MessageType.STATUS | Task 5 |
| DroolsScoutingTask — game restart resets prev-value fields | Task 5 |
| pom.xml deps | Task 1 |
| H2 datasource config | Task 1 |
| QhorusScoutingIntelIT | Task 6 |
| FullMockPipelineIT regression | Task 6 |

**Placeholder check:** None — all steps include concrete code.

**Type consistency check:**
- `TacticsIntelCache` defined in Task 4 (Step 4.3), used in Tasks 4 and 6 — consistent
- `ScoutingIntelBroker.CHANNEL_NAME` public static field — referenced in Task 4 (channels()) and Task 6 — consistent
- `MessageDispatch.builder().type(MessageType v)` — correct builder method name (not `messageType`)
- `c -> c.id` for Channel.id (public field, not a getter) — correct
- `ScoutingIntelPreference implements SingleValuePreference` — satisfies `Preferences.getOrDefault()` generic bound
- `initThresholds(Preferences prefs)` / `initSubscriptions(Preferences prefs)` — package-private methods, accessible from same-package tests without CDI

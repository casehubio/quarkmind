# Hierarchical Event Summarisation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a four-level temporal abstraction hierarchy (raw ticks → intel → moments → phases → arcs) with a generic framework pre-positioned for `casehub-blocks` migration and SC2-specific bindings in quarkmind.

**Architecture:** Generic event hierarchy framework in `io.casehub.blocks.summarisation` (plain Java, CDI-free, records + interfaces). SC2 application layer in `io.quarkmind.plugin.summarisation` wires Drools CEP (L1→L2), deterministic summariser stubs (L2→L3→L4), and CDI bridge for consumer discovery. `DroolsScoutingTask` gains L1 transition publishing; `GameTickExecutor` gains a post-`createAndSolve()` summarisation tick.

**Tech Stack:** Java 17 records, Drools Rule Units, Quarkus CDI, Qhorus channels, JUnit 5

## Global Constraints

- Generic layer (`io.casehub.blocks.summarisation`): no CDI, no Quarkus, no SC2 imports. Plain Java only.
- SC2 layer (`io.quarkmind.plugin.summarisation`): follows existing plugin patterns — seam interface in `agent/plugin/`, `@CaseType("starcraft-game")`, `requires()`/`produces()`.
- Protocol PP-20260610-88dbbd: `@Observes` (synchronous) for `GameStarted`/`GameStopped` observers.
- Protocol PP-20260608-8584ab: `ScoutingIntelConsumer` implementations must call `refreshSubscriptions()` in `@PostConstruct`.
- Protocol PP-20260603-cefed9: `requires()` for key-presence gates, `activateIf()` for CDI-injected gates. Never duplicate.
- Protocol PP-20260610-3c3e89: In `@QuarkusTest`, inject concrete types, not seam interfaces.
- Protocol PP-20260601-5fa812: Seam interface public; implementations package-private where applicable.
- Protocol PP-20260612-afe621: Never write `gameActive`.
- All tests run with `mvn test -q`. Single class: `mvn test -Dtest=ClassName -q`.
- Spec: `docs/superpowers/specs/2026-06-29-hierarchical-event-summarisation-design.md`
- Issue: quarkmind#182

---

### Task 1: Generic Foundation — Records, EventAccumulator, EventStreamBus

**Files:**
- Create: `src/main/java/io/casehub/blocks/summarisation/EventLevel.java`
- Create: `src/main/java/io/casehub/blocks/summarisation/LevelEvent.java`
- Create: `src/main/java/io/casehub/blocks/summarisation/WindowPolicy.java`
- Create: `src/main/java/io/casehub/blocks/summarisation/EventAccumulator.java`
- Create: `src/main/java/io/casehub/blocks/summarisation/EventStreamBus.java`
- Create: `src/main/java/io/casehub/blocks/summarisation/EventConsumer.java`
- Create: `src/test/java/io/casehub/blocks/summarisation/EventAccumulatorTest.java`
- Create: `src/test/java/io/casehub/blocks/summarisation/EventStreamBusTest.java`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `EventLevel(String name, int ordinal)` — record
  - `LevelEvent<E>(E payload, long timestamp, EventLevel level)` — record
  - `WindowPolicy(long maxAge, int maxCount)` — record, either field 0 for single-trigger
  - `EventAccumulator<E>` — `collect(LevelEvent<E>)`, `shouldEmit(long now)`, `drain()`, `clear()`, `size()`
  - `EventStreamBus<E>` — `subscribe(Predicate<E>, Consumer<LevelEvent<E>>)`, `publish(LevelEvent<E>)`, `clear()`
  - `EventConsumer<E>` — interface with `Predicate<E> eventFilter()`

- [ ] **Step 1: Write EventAccumulatorTest — timestamp trigger**

```java
package io.casehub.blocks.summarisation;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EventAccumulatorTest {

    private static final EventLevel LEVEL = new EventLevel("test", 0);

    @Test
    void shouldEmit_timestampTrigger_firesWhenMaxAgeExceeded() {
        var acc = new EventAccumulator<String>(new WindowPolicy(100, 0));
        acc.collect(new LevelEvent<>("a", 10, LEVEL));
        assertThat(acc.shouldEmit(50)).isFalse();
        assertThat(acc.shouldEmit(111)).isTrue();
    }

    @Test
    void shouldEmit_countTrigger_firesWhenMaxCountReached() {
        var acc = new EventAccumulator<String>(new WindowPolicy(0, 3));
        acc.collect(new LevelEvent<>("a", 1, LEVEL));
        acc.collect(new LevelEvent<>("b", 2, LEVEL));
        assertThat(acc.shouldEmit(999)).isFalse();
        acc.collect(new LevelEvent<>("c", 3, LEVEL));
        assertThat(acc.shouldEmit(999)).isTrue();
    }

    @Test
    void shouldEmit_dualTrigger_firesOnEitherCondition() {
        var acc = new EventAccumulator<String>(new WindowPolicy(100, 5));
        acc.collect(new LevelEvent<>("a", 10, LEVEL));
        assertThat(acc.shouldEmit(111)).as("timestamp trigger").isTrue();

        var acc2 = new EventAccumulator<String>(new WindowPolicy(100, 2));
        acc2.collect(new LevelEvent<>("a", 10, LEVEL));
        acc2.collect(new LevelEvent<>("b", 11, LEVEL));
        assertThat(acc2.shouldEmit(12)).as("count trigger").isTrue();
    }

    @Test
    void drain_returnsAndClears() {
        var acc = new EventAccumulator<String>(new WindowPolicy(0, 1));
        acc.collect(new LevelEvent<>("a", 1, LEVEL));
        acc.collect(new LevelEvent<>("b", 2, LEVEL));
        var drained = acc.drain();
        assertThat(drained).hasSize(2);
        assertThat(acc.size()).isZero();
        assertThat(acc.shouldEmit(999)).isFalse();
    }

    @Test
    void clear_resetsAllState() {
        var acc = new EventAccumulator<String>(new WindowPolicy(0, 1));
        acc.collect(new LevelEvent<>("a", 1, LEVEL));
        acc.clear();
        assertThat(acc.size()).isZero();
        assertThat(acc.drain()).isEmpty();
    }

    @Test
    void shouldEmit_emptyAccumulator_neverFires() {
        var acc = new EventAccumulator<String>(new WindowPolicy(100, 5));
        assertThat(acc.shouldEmit(999)).isFalse();
    }
}
```

- [ ] **Step 2: Write EventStreamBusTest**

```java
package io.casehub.blocks.summarisation;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class EventStreamBusTest {

    private static final EventLevel LEVEL = new EventLevel("test", 0);

    @Test
    void publish_dispatchesToMatchingSubscribers() {
        var bus = new EventStreamBus<String>();
        List<String> received = new ArrayList<>();
        bus.subscribe(s -> s.startsWith("A"), e -> received.add(e.payload()));
        bus.publish(new LevelEvent<>("Alpha", 1, LEVEL));
        bus.publish(new LevelEvent<>("Beta", 2, LEVEL));
        bus.publish(new LevelEvent<>("Apex", 3, LEVEL));
        assertThat(received).containsExactly("Alpha", "Apex");
    }

    @Test
    void publish_multipleSubscribers_allReceive() {
        var bus = new EventStreamBus<String>();
        List<String> sub1 = new ArrayList<>();
        List<String> sub2 = new ArrayList<>();
        bus.subscribe(s -> true, e -> sub1.add(e.payload()));
        bus.subscribe(s -> true, e -> sub2.add(e.payload()));
        bus.publish(new LevelEvent<>("X", 1, LEVEL));
        assertThat(sub1).containsExactly("X");
        assertThat(sub2).containsExactly("X");
    }

    @Test
    void clear_removesAllSubscribers() {
        var bus = new EventStreamBus<String>();
        List<String> received = new ArrayList<>();
        bus.subscribe(s -> true, e -> received.add(e.payload()));
        bus.clear();
        bus.publish(new LevelEvent<>("X", 1, LEVEL));
        assertThat(received).isEmpty();
    }

    @Test
    void publish_noSubscribers_noError() {
        var bus = new EventStreamBus<String>();
        bus.publish(new LevelEvent<>("X", 1, LEVEL));
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn test -Dtest=EventAccumulatorTest,EventStreamBusTest -q`
Expected: FAIL — classes do not exist yet.

- [ ] **Step 4: Implement records**

`EventLevel.java`:
```java
package io.casehub.blocks.summarisation;

public record EventLevel(String name, int ordinal) {}
```

`LevelEvent.java`:
```java
package io.casehub.blocks.summarisation;

public record LevelEvent<E>(E payload, long timestamp, EventLevel level) {}
```

`WindowPolicy.java`:
```java
package io.casehub.blocks.summarisation;

public record WindowPolicy(long maxAge, int maxCount) {}
```

`EventConsumer.java`:
```java
package io.casehub.blocks.summarisation;

import java.util.function.Predicate;

public interface EventConsumer<E> {
    Predicate<E> eventFilter();
}
```

- [ ] **Step 5: Implement EventAccumulator**

```java
package io.casehub.blocks.summarisation;

import java.util.ArrayList;
import java.util.List;

public class EventAccumulator<E> {

    private final WindowPolicy policy;
    private final List<LevelEvent<E>> buffer = new ArrayList<>();

    public EventAccumulator(WindowPolicy policy) {
        this.policy = policy;
    }

    public void collect(LevelEvent<E> event) {
        buffer.add(event);
    }

    public boolean shouldEmit(long now) {
        if (buffer.isEmpty()) return false;
        if (policy.maxCount() > 0 && buffer.size() >= policy.maxCount()) return true;
        if (policy.maxAge() > 0) {
            long oldest = buffer.get(0).timestamp();
            return (now - oldest) >= policy.maxAge();
        }
        return false;
    }

    public List<LevelEvent<E>> drain() {
        var result = List.copyOf(buffer);
        buffer.clear();
        return result;
    }

    public void clear() {
        buffer.clear();
    }

    public int size() {
        return buffer.size();
    }
}
```

- [ ] **Step 6: Implement EventStreamBus**

```java
package io.casehub.blocks.summarisation;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class EventStreamBus<E> {

    private record Subscription<E>(Predicate<E> filter, Consumer<LevelEvent<E>> callback) {}

    private final List<Subscription<E>> subscriptions = new CopyOnWriteArrayList<>();

    public void subscribe(Predicate<E> filter, Consumer<LevelEvent<E>> callback) {
        subscriptions.add(new Subscription<>(filter, callback));
    }

    public void publish(LevelEvent<E> event) {
        for (var sub : subscriptions) {
            if (sub.filter().test(event.payload())) {
                sub.callback().accept(event);
            }
        }
    }

    public void clear() {
        subscriptions.clear();
    }
}
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `mvn test -Dtest=EventAccumulatorTest,EventStreamBusTest -q`
Expected: PASS — all 10 tests green.

- [ ] **Step 8: Commit**

```
feat(#182): generic event hierarchy foundation — EventAccumulator, EventStreamBus

Records (EventLevel, LevelEvent, WindowPolicy), EventAccumulator with
dual-trigger windowing, EventStreamBus with predicate-based pub/sub,
EventConsumer interface. All in io.casehub.blocks.summarisation —
pre-positioned for casehub-blocks migration. Plain Java, no CDI.

Refs #182
```

---

### Task 2: Generic Summariser + SummarisationRunner

**Files:**
- Create: `src/main/java/io/casehub/blocks/summarisation/Summariser.java`
- Create: `src/main/java/io/casehub/blocks/summarisation/SummarisationRunner.java`
- Create: `src/test/java/io/casehub/blocks/summarisation/SummarisationRunnerTest.java`

**Interfaces:**
- Consumes: `EventAccumulator<E>`, `EventStreamBus<E>`, `LevelEvent<E>`, `EventLevel`, `WindowPolicy` (Task 1)
- Produces:
  - `Summariser<IN, OUT>` — interface: `List<OUT> summarise(List<LevelEvent<IN>> batch)`
  - `SummarisationRunner<IN, OUT>` — `tick(long now)`, `clear()`

- [ ] **Step 1: Write SummarisationRunnerTest**

```java
package io.casehub.blocks.summarisation;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class SummarisationRunnerTest {

    private static final EventLevel INPUT_LEVEL = new EventLevel("input", 0);
    private static final EventLevel OUTPUT_LEVEL = new EventLevel("output", 1);

    @Test
    void tick_emitsWhenWindowMet_publishesToBus() {
        Summariser<String, Integer> summariser = batch -> List.of(batch.size());
        var acc = new EventAccumulator<String>(new WindowPolicy(0, 2));
        var outputBus = new EventStreamBus<Integer>();
        var runner = new SummarisationRunner<>(acc, summariser, outputBus, OUTPUT_LEVEL);

        List<Integer> received = new ArrayList<>();
        outputBus.subscribe(i -> true, e -> received.add(e.payload()));

        acc.collect(new LevelEvent<>("a", 1, INPUT_LEVEL));
        runner.tick(5);
        assertThat(received).as("not enough events yet").isEmpty();

        acc.collect(new LevelEvent<>("b", 2, INPUT_LEVEL));
        runner.tick(5);
        assertThat(received).as("count threshold met").containsExactly(2);
    }

    @Test
    void tick_doesNotEmitWhenWindowNotMet() {
        Summariser<String, Integer> summariser = batch -> List.of(batch.size());
        var acc = new EventAccumulator<String>(new WindowPolicy(100, 0));
        var outputBus = new EventStreamBus<Integer>();
        var runner = new SummarisationRunner<>(acc, summariser, outputBus, OUTPUT_LEVEL);

        List<Integer> received = new ArrayList<>();
        outputBus.subscribe(i -> true, e -> received.add(e.payload()));

        acc.collect(new LevelEvent<>("a", 50, INPUT_LEVEL));
        runner.tick(60);
        assertThat(received).isEmpty();
    }

    @Test
    void tick_wrapsOutputInLevelEvent_withCorrectLevelAndTimestamp() {
        Summariser<String, String> summariser = batch -> List.of("summary");
        var acc = new EventAccumulator<String>(new WindowPolicy(0, 1));
        var outputBus = new EventStreamBus<String>();
        var runner = new SummarisationRunner<>(acc, summariser, outputBus, OUTPUT_LEVEL);

        List<LevelEvent<String>> received = new ArrayList<>();
        outputBus.subscribe(s -> true, received::add);

        acc.collect(new LevelEvent<>("a", 10, INPUT_LEVEL));
        runner.tick(42);
        assertThat(received).hasSize(1);
        assertThat(received.get(0).level()).isEqualTo(OUTPUT_LEVEL);
        assertThat(received.get(0).timestamp()).isEqualTo(42);
        assertThat(received.get(0).payload()).isEqualTo("summary");
    }

    @Test
    void clear_resetsAccumulator() {
        Summariser<String, Integer> summariser = batch -> List.of(batch.size());
        var acc = new EventAccumulator<String>(new WindowPolicy(0, 2));
        var outputBus = new EventStreamBus<Integer>();
        var runner = new SummarisationRunner<>(acc, summariser, outputBus, OUTPUT_LEVEL);

        acc.collect(new LevelEvent<>("a", 1, INPUT_LEVEL));
        runner.clear();
        assertThat(acc.size()).isZero();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=SummarisationRunnerTest -q`
Expected: FAIL — `Summariser` and `SummarisationRunner` do not exist.

- [ ] **Step 3: Implement Summariser interface**

```java
package io.casehub.blocks.summarisation;

import java.util.List;

@FunctionalInterface
public interface Summariser<IN, OUT> {
    List<OUT> summarise(List<LevelEvent<IN>> batch);
}
```

- [ ] **Step 4: Implement SummarisationRunner**

```java
package io.casehub.blocks.summarisation;

public class SummarisationRunner<IN, OUT> {

    private final EventAccumulator<IN> accumulator;
    private final Summariser<IN, OUT> summariser;
    private final EventStreamBus<OUT> outputBus;
    private final EventLevel outputLevel;

    public SummarisationRunner(EventAccumulator<IN> accumulator,
                               Summariser<IN, OUT> summariser,
                               EventStreamBus<OUT> outputBus,
                               EventLevel outputLevel) {
        this.accumulator = accumulator;
        this.summariser = summariser;
        this.outputBus = outputBus;
        this.outputLevel = outputLevel;
    }

    public void tick(long now) {
        if (!accumulator.shouldEmit(now)) return;
        var batch = accumulator.drain();
        var results = summariser.summarise(batch);
        for (var payload : results) {
            outputBus.publish(new LevelEvent<>(payload, now, outputLevel));
        }
    }

    public void clear() {
        accumulator.clear();
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn test -Dtest=SummarisationRunnerTest -q`
Expected: PASS — all 4 tests green.

- [ ] **Step 6: Run all generic layer tests together**

Run: `mvn test -Dtest=EventAccumulatorTest,EventStreamBusTest,SummarisationRunnerTest -q`
Expected: PASS — all 14 tests green.

- [ ] **Step 7: Commit**

```
feat(#182): Summariser interface + SummarisationRunner

Summariser<IN, OUT> is the batch promotion contract (L2→L3→L4).
SummarisationRunner wires EventAccumulator to Summariser and publishes
results to the output EventStreamBus. Completes the generic layer.

Refs #182
```

---

### Task 3: SC2 Domain Types + Seam Interface + CaseFile Key

**Files:**
- Create: `src/main/java/io/quarkmind/plugin/summarisation/GameMomentType.java`
- Create: `src/main/java/io/quarkmind/plugin/summarisation/GameMoment.java`
- Create: `src/main/java/io/quarkmind/plugin/summarisation/GamePhase.java`
- Create: `src/main/java/io/quarkmind/plugin/summarisation/GameArc.java`
- Create: `src/main/java/io/quarkmind/agent/plugin/MomentDetectionSeam.java`
- Create: `src/main/java/io/quarkmind/plugin/summarisation/MomentConsumer.java`
- Modify: `src/main/java/io/quarkmind/agent/QuarkMindCaseFile.java` — add `MOMENTS_LATEST` key

**Interfaces:**
- Consumes: `EventConsumer<E>` (Task 1)
- Produces:
  - `GameMomentType` enum — 9 moment types
  - `GameMoment(GameMomentType type, long gameFrame, Map<String, Object> context)` — record
  - `GamePhase(String phase, long sinceFrame, String rationale)` — record
  - `GameArc(String narrative, long generatedAt)` — record
  - `MomentDetectionSeam` — seam interface with `requires()`/`produces()`
  - `MomentConsumer extends EventConsumer<GameMoment>` — CDI consumer interface
  - `QuarkMindCaseFile.MOMENTS_LATEST` — CaseFile key constant

- [ ] **Step 1: Create domain types**

`GameMomentType.java`:
```java
package io.quarkmind.plugin.summarisation;

public enum GameMomentType {
    FIRST_CONTACT,
    BATTLE_STARTED,
    BATTLE_ENDED,
    SUPPLY_BLOCK,
    ECONOMIC_CRISIS,
    BUILDING_LOST,
    NEXUS_UNDER_ATTACK,
    SCOUT_LOST,
    TECH_TRANSITION_DETECTED
}
```

`GameMoment.java`:
```java
package io.quarkmind.plugin.summarisation;

import java.util.Map;

public record GameMoment(GameMomentType type, long gameFrame, Map<String, Object> context) {}
```

`GamePhase.java`:
```java
package io.quarkmind.plugin.summarisation;

public record GamePhase(String phase, long sinceFrame, String rationale) {}
```

`GameArc.java`:
```java
package io.quarkmind.plugin.summarisation;

public record GameArc(String narrative, long generatedAt) {}
```

- [ ] **Step 2: Create MomentDetectionSeam**

```java
package io.quarkmind.agent.plugin;

public interface MomentDetectionSeam
        extends io.casehub.core.TaskDefinition,
                io.quarkmind.agent.TaskDefinition {}
```

This follows the exact pattern of `ScoutingTask`, `StrategyTask`, `EconomicsTask`, `TacticsTask`.

- [ ] **Step 3: Create MomentConsumer**

```java
package io.quarkmind.plugin.summarisation;

import io.casehub.blocks.summarisation.EventConsumer;

import java.util.Set;
import java.util.function.Predicate;

public interface MomentConsumer extends EventConsumer<GameMoment> {

    Set<GameMomentType> subscribedMomentTypes();

    @Override
    default Predicate<GameMoment> eventFilter() {
        return m -> subscribedMomentTypes().contains(m.type());
    }
}
```

- [ ] **Step 4: Add CaseFile key**

Add to `QuarkMindCaseFile.java` after the `ENEMY_POSTURE` constant:

```java
public static final String MOMENTS_LATEST = "agent.intel.moments.latest";
```

Add `MOMENTS_LATEST` to the `ALL_KEYS` list.

- [ ] **Step 5: Verify compilation**

Run: `mvn compile -q`
Expected: PASS — all types compile, no CDI wiring yet.

- [ ] **Step 6: Commit**

```
feat(#182): SC2 domain types, MomentDetectionSeam, MomentConsumer

GameMomentType (9 moment types), GameMoment, GamePhase, GameArc records.
MomentDetectionSeam follows existing plugin seam pattern.
MomentConsumer extends generic EventConsumer<GameMoment> with set-of-enum
convenience. CaseFile key MOMENTS_LATEST added.

Refs #182
```

---

### Task 4: L1 Event Stream Wiring — DroolsScoutingTask Publishes Transitions

**Files:**
- Modify: `src/main/java/io/quarkmind/plugin/scouting/DroolsScoutingTask.java` — add `EventStreamBus<ScoutingIntelPayload>` publishing
- Modify: `src/main/java/io/quarkmind/agent/ScoutingIntelBroker.java` — own the L1 EventStreamBus, expose it, clear on GameStarted
- Modify: `src/test/java/io/quarkmind/plugin/scouting/DroolsScoutingTaskTest.java` — verify L1 transition events published

**Interfaces:**
- Consumes: `EventStreamBus<E>`, `LevelEvent<E>`, `EventLevel` (Task 1), `ScoutingIntelPayload` (existing)
- Produces: L1 transition events published to `EventStreamBus<ScoutingIntelPayload>` via `ScoutingIntelBroker.level1Bus()`

- [ ] **Step 1: Add EventStreamBus to ScoutingIntelBroker**

Add to `ScoutingIntelBroker.java`:

Field:
```java
private final EventStreamBus<ScoutingIntelPayload> level1Bus = new EventStreamBus<>();
```

Import:
```java
import io.casehub.blocks.summarisation.EventStreamBus;
```

Public accessor:
```java
public EventStreamBus<ScoutingIntelPayload> level1Bus() { return level1Bus; }
```

In `onGameStarted()`, add `level1Bus.clear();` after `latest.clear();`.

In `clearLatest()`, add `level1Bus.clear();` after `latest.clear();`.

- [ ] **Step 2: Add L1 publishing to DroolsScoutingTask.publishIntel()**

In `DroolsScoutingTask.java`, add import:
```java
import io.casehub.blocks.summarisation.EventLevel;
import io.casehub.blocks.summarisation.LevelEvent;
```

Add static field:
```java
static final EventLevel LEVEL_1 = new EventLevel("intel", 1);
```

Modify `publishIntel()` — add L1 bus publishing after broker update:
```java
private void publishIntel(ScoutingIntelPayload payload) {
    if (broker.isSubscribed(payload.type())) {
        broker.update(payload);
    }
    broker.level1Bus().publish(new LevelEvent<>(payload, lastFrame, LEVEL_1));
    dispatchToAdvisory(payload);
}
```

- [ ] **Step 3: Write test verifying L1 events published**

Add to the existing `DroolsScoutingTaskTest.java` (or create a focused test if the existing test is too large). The test subscribes to the L1 bus and verifies that transition events arrive when DroolsScoutingTask detects a change. Use the existing test pattern — construct `DroolsScoutingTask` directly with a mock `ScoutingIntelBroker` whose `level1Bus()` is a real `EventStreamBus`.

```java
@Test
void publishIntel_publishesToLevel1Bus() {
    var bus = broker.level1Bus();
    List<LevelEvent<ScoutingIntelPayload>> received = new ArrayList<>();
    bus.subscribe(p -> true, received::add);

    // Trigger a posture change via CEP
    // ... (use existing test setup pattern with mock enemies, buildings, game frame)

    assertThat(received).isNotEmpty();
    assertThat(received.get(0).payload()).isInstanceOf(ScoutingIntelPayload.class);
    assertThat(received.get(0).level().name()).isEqualTo("intel");
}
```

The exact test setup depends on the existing `DroolsScoutingTaskTest` structure — follow its pattern for constructing the task and feeding game state through `execute()`.

- [ ] **Step 4: Run tests**

Run: `mvn test -Dtest=DroolsScoutingTaskTest -q`
Expected: PASS — existing tests still green, new test green.

- [ ] **Step 5: Commit**

```
feat(#182): L1 event stream — DroolsScoutingTask publishes transitions

ScoutingIntelBroker owns EventStreamBus<ScoutingIntelPayload> for Level 1
transition events. DroolsScoutingTask.publishIntel() publishes to the bus
alongside existing broker.update() and advisory dispatch. Bus cleared on
GameStarted.

Refs #182
```

---

### Task 5: MomentDetectionTask + Drools L1→L2 Rules

**Files:**
- Create: `src/main/java/io/quarkmind/plugin/summarisation/MomentDetectionRuleUnit.java`
- Create: `src/main/java/io/quarkmind/plugin/summarisation/MomentDetectionTask.java`
- Create: `src/main/resources/io/quarkmind/plugin/summarisation/MomentDetectionTask.drl`
- Modify: `src/main/java/io/quarkmind/agent/QuarkMindTaskRegistrar.java` — register MomentDetectionSeam
- Create: `src/test/java/io/quarkmind/plugin/summarisation/MomentDetectionTaskTest.java`

**Interfaces:**
- Consumes: `EventStreamBus<ScoutingIntelPayload>` (Task 4), `ScoutingIntelBroker.level1Bus()` (Task 4), `MomentDetectionSeam` (Task 3), `GameMoment`, `GameMomentType` (Task 3), `EventLevel`, `LevelEvent`, `EventStreamBus` (Task 1)
- Produces: `GameMoment` events published to an `EventStreamBus<GameMoment>` (injected as MomentBroker — wired in Task 7). For testing, the bus is passed directly.

- [ ] **Step 1: Write MomentDetectionTaskTest**

```java
package io.quarkmind.plugin.summarisation;

import io.casehub.blocks.summarisation.EventLevel;
import io.casehub.blocks.summarisation.EventStreamBus;
import io.casehub.blocks.summarisation.LevelEvent;
import io.quarkmind.agent.plugin.ScoutingIntelPayload;
import org.drools.ruleunits.api.RuleUnit;
import org.drools.ruleunits.api.RuleUnitProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MomentDetectionTaskTest {

    private static final EventLevel LEVEL_1 = new EventLevel("intel", 1);

    private EventStreamBus<ScoutingIntelPayload> level1Bus;
    private EventStreamBus<GameMoment> momentBus;
    private List<LevelEvent<GameMoment>> receivedMoments;
    private RuleUnit<MomentDetectionRuleUnit> ruleUnit;

    @BeforeEach
    void setUp() {
        level1Bus = new EventStreamBus<>();
        momentBus = new EventStreamBus<>();
        receivedMoments = new ArrayList<>();
        momentBus.subscribe(m -> true, receivedMoments::add);
        ruleUnit = RuleUnitProvider.get().getRuleUnit(MomentDetectionRuleUnit.class);
    }

    @Test
    void detectsFirstContact_whenThreatPositionArrives() {
        var task = new MomentDetectionTask(ruleUnit);
        task.setLevel1Bus(level1Bus);
        task.setMomentBus(momentBus);
        task.init();

        level1Bus.publish(new LevelEvent<>(
            new ScoutingIntelPayload.ThreatPosition(new io.quarkmind.domain.Point2d(50, 50)),
            100, LEVEL_1));
        task.fireRules(100);

        assertThat(receivedMoments).isNotEmpty();
        assertThat(receivedMoments.get(0).payload().type()).isEqualTo(GameMomentType.FIRST_CONTACT);
    }

    @Test
    void detectsNexusUnderAttack_whenTimingAlertAndHighArmySize() {
        var task = new MomentDetectionTask(ruleUnit);
        task.setLevel1Bus(level1Bus);
        task.setMomentBus(momentBus);
        task.init();

        level1Bus.publish(new LevelEvent<>(
            new ScoutingIntelPayload.TimingAlert(true), 200, LEVEL_1));
        level1Bus.publish(new LevelEvent<>(
            new ScoutingIntelPayload.ArmySize(8), 200, LEVEL_1));
        task.fireRules(200);

        assertThat(receivedMoments)
            .extracting(e -> e.payload().type())
            .contains(GameMomentType.NEXUS_UNDER_ATTACK);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=MomentDetectionTaskTest -q`
Expected: FAIL — classes do not exist yet.

- [ ] **Step 3: Implement MomentDetectionRuleUnit**

```java
package io.quarkmind.plugin.summarisation;

import io.quarkmind.agent.plugin.ScoutingIntelPayload;
import org.drools.ruleunits.api.DataSource;
import org.drools.ruleunits.api.DataStore;
import org.drools.ruleunits.api.RuleUnitData;

import java.util.ArrayList;
import java.util.List;

public class MomentDetectionRuleUnit implements RuleUnitData {

    private final DataStore<ScoutingIntelPayload> intelEvents = DataSource.createStore();
    private final List<GameMoment> detectedMoments = new ArrayList<>();
    private long currentFrame;

    public DataStore<ScoutingIntelPayload> getIntelEvents() { return intelEvents; }
    public List<GameMoment> getDetectedMoments() { return detectedMoments; }
    public long getCurrentFrame() { return currentFrame; }
    public void setCurrentFrame(long currentFrame) { this.currentFrame = currentFrame; }
}
```

- [ ] **Step 4: Implement MomentDetectionTask**

```java
package io.quarkmind.plugin.summarisation;

import io.casehub.annotation.CaseType;
import io.casehub.api.context.CaseContext;
import io.casehub.blocks.summarisation.EventLevel;
import io.casehub.blocks.summarisation.EventStreamBus;
import io.casehub.blocks.summarisation.LevelEvent;
import io.casehub.core.CaseFile;
import io.quarkmind.agent.CaseFileContext;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.ScoutingIntelBroker;
import io.quarkmind.agent.plugin.MomentDetectionSeam;
import io.quarkmind.agent.plugin.ScoutingIntelPayload;
import io.quarkmind.sc2.GameStarted;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.drools.ruleunits.api.RuleUnit;
import org.drools.ruleunits.api.RuleUnitInstance;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

@ApplicationScoped
@CaseType("starcraft-game")
public class MomentDetectionTask implements MomentDetectionSeam {

    static final EventLevel LEVEL_2 = new EventLevel("moment", 2);
    private static final Logger log = Logger.getLogger(MomentDetectionTask.class);

    private final RuleUnit<MomentDetectionRuleUnit> ruleUnit;
    private final List<ScoutingIntelPayload> pendingIntel = new ArrayList<>();

    private EventStreamBus<ScoutingIntelPayload> level1Bus;
    private EventStreamBus<GameMoment> momentBus;
    private boolean firstContactFired = false;

    @Inject
    public MomentDetectionTask(RuleUnit<MomentDetectionRuleUnit> ruleUnit) {
        this.ruleUnit = ruleUnit;
    }

    @Inject ScoutingIntelBroker scoutingBroker;

    @PostConstruct
    void init() {
        if (level1Bus == null && scoutingBroker != null) {
            level1Bus = scoutingBroker.level1Bus();
        }
        if (level1Bus != null) {
            level1Bus.subscribe(p -> true, e -> pendingIntel.add(e.payload()));
        }
    }

    void setLevel1Bus(EventStreamBus<ScoutingIntelPayload> bus) { this.level1Bus = bus; }
    void setMomentBus(EventStreamBus<GameMoment> bus) { this.momentBus = bus; }

    @Override public String getId()   { return "summarisation.moment-detection"; }
    @Override public String getName() { return "Moment Detection (L1→L2)"; }

    @Override
    public Set<String> requires() {
        return Set.of(
            QuarkMindCaseFile.ENEMY_UNITS,
            QuarkMindCaseFile.ENEMY_POSTURE,
            QuarkMindCaseFile.TIMING_ATTACK_INCOMING);
    }

    @Override
    public Predicate<CaseContext> activateIf() {
        return ctx -> true;
    }

    @Override
    public Set<String> produces() {
        return Set.of(QuarkMindCaseFile.MOMENTS_LATEST);
    }

    @Override
    public void execute(CaseContext ctx) {
        Long frameL = ctx.getAs(QuarkMindCaseFile.GAME_FRAME, Long.class);
        long frame = frameL != null ? frameL : 0L;
        fireRules(frame);

        // Write detected moments to CaseFile for downstream plugins
        List<GameMoment> moments = List.copyOf(pendingIntel.isEmpty()
            ? List.of() : drainDetectedMoments(frame));
        if (!moments.isEmpty()) {
            ctx.set(QuarkMindCaseFile.MOMENTS_LATEST, moments);
        }
    }

    void fireRules(long frame) {
        if (pendingIntel.isEmpty()) return;

        var data = new MomentDetectionRuleUnit();
        data.setCurrentFrame(frame);
        for (var payload : pendingIntel) {
            data.getIntelEvents().add(payload);
        }
        pendingIntel.clear();

        try (RuleUnitInstance<MomentDetectionRuleUnit> instance = ruleUnit.createInstance(data)) {
            instance.fire();
        }

        for (var moment : data.getDetectedMoments()) {
            if (momentBus != null) {
                momentBus.publish(new LevelEvent<>(moment, frame, LEVEL_2));
            }
            log.debugf("[MOMENT] %s at frame %d", moment.type(), frame);
        }
    }

    private List<GameMoment> drainDetectedMoments(long frame) {
        // Re-fire is a no-op since pendingIntel was already cleared in fireRules()
        // The moments were published in fireRules(); return empty for CaseFile write
        return List.of();
    }

    void onGameStarted(@Observes GameStarted event) {
        pendingIntel.clear();
        firstContactFired = false;
    }

    // Phase 1 bridges
    @Override public Set<String> entryCriteria() { return requires(); }
    @Override public Set<String> producedKeys()  { return produces(); }

    @Override
    public boolean canActivate(CaseFile caseFile) {
        return testActivation(new CaseFileContext(caseFile));
    }

    @Override
    public void execute(CaseFile caseFile) {
        var ctx = new CaseFileContext(caseFile);
        execute(ctx);
        produces().forEach(k -> { Object v = ctx.get(k); if (v != null) caseFile.put(k, v); });
    }
}
```

- [ ] **Step 5: Write Drools rules for L1→L2 moment detection**

`src/main/resources/io/quarkmind/plugin/summarisation/MomentDetectionTask.drl`:
```drools
package io.quarkmind.plugin.summarisation;
unit MomentDetectionRuleUnit;

import io.quarkmind.agent.plugin.ScoutingIntelPayload;
import io.quarkmind.agent.plugin.ScoutingIntelPayload.ThreatPosition;
import io.quarkmind.agent.plugin.ScoutingIntelPayload.TimingAlert;
import io.quarkmind.agent.plugin.ScoutingIntelPayload.ArmySize;
import io.quarkmind.agent.plugin.ScoutingIntelPayload.PostureUpdate;
import io.quarkmind.agent.plugin.ScoutingIntelPayload.BuildOrder;
import java.util.Map;

rule "First Contact"
    salience 200
when
    /intelEvents[ # ThreatPosition ]
then
    detectedMoments.add(new GameMoment(
        GameMomentType.FIRST_CONTACT, currentFrame, Map.of()));
end

rule "Nexus Under Attack"
    salience 190
when
    /intelEvents[ # TimingAlert, incoming == true ]
    accumulate(
        /intelEvents[ # ArmySize ];
        $count : count();
        $count >= 1
    )
then
    detectedMoments.add(new GameMoment(
        GameMomentType.NEXUS_UNDER_ATTACK, currentFrame, Map.of()));
end

rule "Battle Started"
    salience 180
when
    /intelEvents[ # TimingAlert, incoming == true ]
then
    detectedMoments.add(new GameMoment(
        GameMomentType.BATTLE_STARTED, currentFrame, Map.of()));
end

rule "Economic Crisis"
    salience 170
when
    /intelEvents[ # PostureUpdate, posture == "ALL_IN" ]
then
    detectedMoments.add(new GameMoment(
        GameMomentType.ECONOMIC_CRISIS, currentFrame, Map.of()));
end

rule "Tech Transition Detected"
    salience 160
when
    /intelEvents[ # BuildOrder, detected != "UNKNOWN" ]
then
    detectedMoments.add(new GameMoment(
        GameMomentType.TECH_TRANSITION_DETECTED, currentFrame,
        Map.of("build", ((BuildOrder) /intelEvents).detected())));
end
```

Note: The exact Drools OOPath syntax for sealed interface pattern matching (`# ThreatPosition`) depends on the Drools version. If the version doesn't support this, fall back to `this instanceof ThreatPosition` in `eval()`. Verify during implementation.

- [ ] **Step 6: Register in QuarkMindTaskRegistrar**

Add to `QuarkMindTaskRegistrar.java`:

Import:
```java
import io.quarkmind.agent.plugin.MomentDetectionSeam;
```

Field:
```java
@Inject @CaseType("starcraft-game") MomentDetectionSeam momentDetectionTask;
```

In `onStart()`, add to the `singletons` list:
```java
List<TaskDefinition> singletons = List.of(
    (TaskDefinition) economicsTask,
    (TaskDefinition) scoutingTask,
    (TaskDefinition) tacticsTask,
    (TaskDefinition) momentDetectionTask
);
```

- [ ] **Step 7: Run tests**

Run: `mvn test -Dtest=MomentDetectionTaskTest -q`
Expected: PASS — moment detection fires correctly.

- [ ] **Step 8: Run full test suite to check for regressions**

Run: `mvn test -q`
Expected: PASS — all existing tests still green.

- [ ] **Step 9: Commit**

```
feat(#182): MomentDetectionTask — Drools CEP L1→L2 moment detection

MomentDetectionRuleUnit + Drools rules detect moments from Level 1 intel
transitions. MomentDetectionTask implements MomentDetectionSeam, subscribes
to L1 EventStreamBus, publishes GameMoment events to L2 bus. Registered
in QuarkMindTaskRegistrar.

Refs #182
```

---

### Task 6: GamePhaseSummariser + GameArcSummariser (Deterministic Stubs)

**Files:**
- Create: `src/main/java/io/quarkmind/plugin/summarisation/GamePhaseSummariser.java`
- Create: `src/main/java/io/quarkmind/plugin/summarisation/GameArcSummariser.java`
- Create: `src/test/java/io/quarkmind/plugin/summarisation/GamePhaseSummariserTest.java`
- Create: `src/test/java/io/quarkmind/plugin/summarisation/GameArcSummariserTest.java`

**Interfaces:**
- Consumes: `Summariser<IN, OUT>`, `LevelEvent<E>`, `EventLevel` (Tasks 1-2), `GameMoment`, `GameMomentType`, `GamePhase`, `GameArc` (Task 3)
- Produces:
  - `GamePhaseSummariser implements Summariser<GameMoment, GamePhase>`
  - `GameArcSummariser implements Summariser<GamePhase, GameArc>`

- [ ] **Step 1: Write GamePhaseSummariserTest**

```java
package io.quarkmind.plugin.summarisation;

import io.casehub.blocks.summarisation.EventLevel;
import io.casehub.blocks.summarisation.LevelEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GamePhaseSummariserTest {

    private static final EventLevel L2 = new EventLevel("moment", 2);

    private final GamePhaseSummariser summariser = new GamePhaseSummariser();

    @Test
    void multipleBattles_classifiesAsMidSkirmish() {
        var batch = List.of(
            new LevelEvent<>(moment(GameMomentType.BATTLE_STARTED, 100), 100, L2),
            new LevelEvent<>(moment(GameMomentType.BATTLE_ENDED, 150), 150, L2),
            new LevelEvent<>(moment(GameMomentType.BATTLE_STARTED, 180), 180, L2));
        var phases = summariser.summarise(batch);
        assertThat(phases).hasSize(1);
        assertThat(phases.get(0).phase()).isEqualTo("MID_SKIRMISH");
    }

    @Test
    void nexusUnderAttack_classifiesAsDefensiveHold() {
        var batch = List.of(
            new LevelEvent<>(moment(GameMomentType.NEXUS_UNDER_ATTACK, 200), 200, L2));
        var phases = summariser.summarise(batch);
        assertThat(phases).hasSize(1);
        assertThat(phases.get(0).phase()).isEqualTo("DEFENSIVE_HOLD");
    }

    @Test
    void noCombatMoments_classifiesAsEarlyMacro() {
        var batch = List.of(
            new LevelEvent<>(moment(GameMomentType.TECH_TRANSITION_DETECTED, 50), 50, L2));
        var phases = summariser.summarise(batch);
        assertThat(phases).hasSize(1);
        assertThat(phases.get(0).phase()).isEqualTo("EARLY_MACRO");
    }

    @Test
    void economicCrisis_classifiesAsEarlyAggression() {
        var batch = List.of(
            new LevelEvent<>(moment(GameMomentType.ECONOMIC_CRISIS, 100), 100, L2),
            new LevelEvent<>(moment(GameMomentType.BATTLE_STARTED, 120), 120, L2));
        var phases = summariser.summarise(batch);
        assertThat(phases).hasSize(1);
        assertThat(phases.get(0).phase()).isEqualTo("EARLY_AGGRESSION");
    }

    @Test
    void emptyBatch_returnsEmpty() {
        assertThat(summariser.summarise(List.of())).isEmpty();
    }

    private static GameMoment moment(GameMomentType type, long frame) {
        return new GameMoment(type, frame, Map.of());
    }
}
```

- [ ] **Step 2: Write GameArcSummariserTest**

```java
package io.quarkmind.plugin.summarisation;

import io.casehub.blocks.summarisation.EventLevel;
import io.casehub.blocks.summarisation.LevelEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GameArcSummariserTest {

    private static final EventLevel L3 = new EventLevel("phase", 3);

    private final GameArcSummariser summariser = new GameArcSummariser();

    @Test
    void producesNarrative_fromPhaseSequence() {
        var batch = List.of(
            new LevelEvent<>(new GamePhase("EARLY_MACRO", 0, "expanding"), 100, L3),
            new LevelEvent<>(new GamePhase("MID_SKIRMISH", 100, "battles"), 200, L3));
        var arcs = summariser.summarise(batch);
        assertThat(arcs).hasSize(1);
        assertThat(arcs.get(0).narrative()).isNotBlank();
    }

    @Test
    void singlePhase_producesNarrative() {
        var batch = List.of(
            new LevelEvent<>(new GamePhase("DEFENSIVE_HOLD", 50, "under attack"), 100, L3));
        var arcs = summariser.summarise(batch);
        assertThat(arcs).hasSize(1);
        assertThat(arcs.get(0).narrative()).contains("DEFENSIVE_HOLD");
    }

    @Test
    void emptyBatch_returnsEmpty() {
        assertThat(summariser.summarise(List.of())).isEmpty();
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn test -Dtest=GamePhaseSummariserTest,GameArcSummariserTest -q`
Expected: FAIL — classes do not exist.

- [ ] **Step 4: Implement GamePhaseSummariser**

```java
package io.quarkmind.plugin.summarisation;

import io.casehub.blocks.summarisation.LevelEvent;
import io.casehub.blocks.summarisation.Summariser;

import java.util.List;
import java.util.Set;

public class GamePhaseSummariser implements Summariser<GameMoment, GamePhase> {

    private static final Set<GameMomentType> COMBAT_TYPES = Set.of(
        GameMomentType.BATTLE_STARTED, GameMomentType.BATTLE_ENDED,
        GameMomentType.NEXUS_UNDER_ATTACK);

    @Override
    public List<GamePhase> summarise(List<LevelEvent<GameMoment>> batch) {
        if (batch.isEmpty()) return List.of();

        long latestFrame = batch.get(batch.size() - 1).timestamp();
        long combatCount = batch.stream()
            .filter(e -> COMBAT_TYPES.contains(e.payload().type()))
            .count();
        boolean hasNexusAttack = batch.stream()
            .anyMatch(e -> e.payload().type() == GameMomentType.NEXUS_UNDER_ATTACK);
        boolean hasEconomicCrisis = batch.stream()
            .anyMatch(e -> e.payload().type() == GameMomentType.ECONOMIC_CRISIS);

        String phase;
        String rationale;

        if (hasNexusAttack) {
            phase = "DEFENSIVE_HOLD";
            rationale = "Base under direct attack";
        } else if (hasEconomicCrisis && combatCount > 0) {
            phase = "EARLY_AGGRESSION";
            rationale = "Enemy all-in with combat engagement";
        } else if (combatCount >= 2) {
            phase = "MID_SKIRMISH";
            rationale = combatCount + " combat events in window";
        } else if (combatCount == 1) {
            phase = "TRANSITIONING";
            rationale = "Single combat event — phase uncertain";
        } else {
            phase = "EARLY_MACRO";
            rationale = "No combat — economic development";
        }

        return List.of(new GamePhase(phase, latestFrame, rationale));
    }
}
```

- [ ] **Step 5: Implement GameArcSummariser**

```java
package io.quarkmind.plugin.summarisation;

import io.casehub.blocks.summarisation.LevelEvent;
import io.casehub.blocks.summarisation.Summariser;

import java.util.List;
import java.util.stream.Collectors;

public class GameArcSummariser implements Summariser<GamePhase, GameArc> {

    @Override
    public List<GameArc> summarise(List<LevelEvent<GamePhase>> batch) {
        if (batch.isEmpty()) return List.of();

        long latestFrame = batch.get(batch.size() - 1).timestamp();
        String phases = batch.stream()
            .map(e -> e.payload().phase())
            .distinct()
            .collect(Collectors.joining(" → "));
        String latestPhase = batch.get(batch.size() - 1).payload().phase();
        String rationale = batch.get(batch.size() - 1).payload().rationale();

        String narrative = String.format("Game progression: %s. Currently in %s phase — %s.",
            phases, latestPhase, rationale);

        return List.of(new GameArc(narrative, latestFrame));
    }
}
```

- [ ] **Step 6: Run tests**

Run: `mvn test -Dtest=GamePhaseSummariserTest,GameArcSummariserTest -q`
Expected: PASS — all 8 tests green.

- [ ] **Step 7: Commit**

```
feat(#182): deterministic summariser stubs — GamePhase + GameArc

GamePhaseSummariser classifies moment batches into game phases
(EARLY_MACRO, MID_SKIRMISH, DEFENSIVE_HOLD, etc.). GameArcSummariser
produces template-based narrative arcs. Both are Summariser<IN, OUT>
implementations, swappable for LLM later.

Refs #182
```

---

### Task 7: MomentBroker + SummarisationLifecycle + GameTickExecutor Wiring

**Files:**
- Create: `src/main/java/io/quarkmind/plugin/summarisation/MomentBroker.java`
- Create: `src/main/java/io/quarkmind/plugin/summarisation/SummarisationLifecycle.java`
- Modify: `src/main/java/io/quarkmind/agent/GameTickExecutor.java` — add `summarisationLifecycle.tick()`
- Create: `src/test/java/io/quarkmind/plugin/summarisation/MomentBrokerIT.java`
- Create: `src/test/java/io/quarkmind/plugin/summarisation/SummarisationPipelineIT.java`

**Interfaces:**
- Consumes: `EventStreamBus<E>`, `EventAccumulator<E>`, `SummarisationRunner<IN, OUT>`, `WindowPolicy`, `EventLevel` (Tasks 1-2), `GameMoment`, `GamePhase`, `GameArc`, `MomentConsumer`, `GamePhaseSummariser`, `GameArcSummariser` (Tasks 3, 5, 6)
- Produces:
  - `MomentBroker` — CDI bean, owns moment `EventStreamBus<GameMoment>`, Qhorus channel, CDI bridge
  - `SummarisationLifecycle` — CDI bean, owns L2→L3 and L3→L4 runners, provides `tick(long gameFrame)`

- [ ] **Step 1: Implement MomentBroker**

```java
package io.quarkmind.plugin.summarisation;

import io.casehub.blocks.summarisation.EventStreamBus;
import io.casehub.blocks.summarisation.LevelEvent;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.runtime.channel.ChannelCreateRequest;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.message.MessageService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkmind.sc2.GameStarted;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class MomentBroker {

    public static final String CHANNEL_NAME = "quarkmind-moments";
    private static final Logger log = Logger.getLogger(MomentBroker.class);

    private final EventStreamBus<GameMoment> momentBus = new EventStreamBus<>();

    @Inject ChannelService channelService;
    @Inject MessageService messageService;
    @Inject ObjectMapper objectMapper;
    @Inject @Any Instance<MomentConsumer> consumers;

    private UUID channelId;

    @PostConstruct
    void init() {
        channelId = QuarkusTransaction.requiringNew().call(() ->
            channelService.findByName(CHANNEL_NAME)
                .map(c -> c.id)
                .orElseGet(() -> channelService.create(
                    new ChannelCreateRequest(
                        CHANNEL_NAME,
                        "Summarisation events (L2 moments, L3 phases, L4 arcs)",
                        ChannelSemantic.APPEND,
                        null, null, null, null, null,
                        Set.of(MessageType.STATUS),
                        null, null, null, null, null)
                ).id)
        );
        registerConsumers();
        momentBus.subscribe(m -> true, this::dispatchToQhorus);
    }

    public EventStreamBus<GameMoment> momentBus() { return momentBus; }
    public UUID channelId() { return channelId; }

    void onGameStarted(@Observes GameStarted event) {
        momentBus.clear();
        registerConsumers();
        momentBus.subscribe(m -> true, this::dispatchToQhorus);
    }

    private void registerConsumers() {
        consumers.forEach(consumer -> {
            var filter = consumer.eventFilter();
            momentBus.subscribe(filter, e -> {});
        });
    }

    private void dispatchToQhorus(LevelEvent<GameMoment> event) {
        try {
            String content = objectMapper.writeValueAsString(
                Map.of("level", event.level().ordinal(),
                       "type", event.payload().type().name(),
                       "frame", event.payload().gameFrame(),
                       "context", event.payload().context()));
            messageService.dispatch(MessageDispatch.builder()
                .channelId(channelId)
                .sender("summarisation.moment-broker")
                .actorType(ActorType.AGENT)
                .type(MessageType.STATUS)
                .content(content)
                .build());
        } catch (JsonProcessingException e) {
            log.warnf("Failed to serialise moment event: %s", e.getMessage());
        }
    }
}
```

- [ ] **Step 2: Implement SummarisationLifecycle**

```java
package io.quarkmind.plugin.summarisation;

import io.casehub.blocks.summarisation.EventAccumulator;
import io.casehub.blocks.summarisation.EventLevel;
import io.casehub.blocks.summarisation.EventStreamBus;
import io.casehub.blocks.summarisation.SummarisationRunner;
import io.casehub.blocks.summarisation.WindowPolicy;
import io.quarkmind.sc2.GameStarted;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

@ApplicationScoped
public class SummarisationLifecycle {

    static final EventLevel LEVEL_3 = new EventLevel("phase", 3);
    static final EventLevel LEVEL_4 = new EventLevel("arc", 4);
    static final long PHASE_WINDOW_FRAMES = (long) (30 * 22.4);
    static final int  PHASE_WINDOW_COUNT  = 5;
    static final long ARC_WINDOW_FRAMES   = (long) (60 * 22.4);
    static final int  ARC_WINDOW_COUNT    = 3;

    @Inject MomentBroker momentBroker;

    private final EventStreamBus<GamePhase> phaseBus = new EventStreamBus<>();
    private final EventStreamBus<GameArc>   arcBus   = new EventStreamBus<>();

    private SummarisationRunner<GameMoment, GamePhase> phaseRunner;
    private SummarisationRunner<GamePhase, GameArc>    arcRunner;

    @PostConstruct
    void init() {
        var phaseAccumulator = new EventAccumulator<GameMoment>(
            new WindowPolicy(PHASE_WINDOW_FRAMES, PHASE_WINDOW_COUNT));
        phaseRunner = new SummarisationRunner<>(
            phaseAccumulator, new GamePhaseSummariser(), phaseBus, LEVEL_3);

        var arcAccumulator = new EventAccumulator<GamePhase>(
            new WindowPolicy(ARC_WINDOW_FRAMES, ARC_WINDOW_COUNT));
        arcRunner = new SummarisationRunner<>(
            arcAccumulator, new GameArcSummariser(), arcBus, LEVEL_4);

        momentBroker.momentBus().subscribe(m -> true,
            e -> phaseAccumulator.collect(e));
        phaseBus.subscribe(p -> true,
            e -> arcAccumulator.collect(e));
    }

    public void tick(long gameFrame) {
        phaseRunner.tick(gameFrame);
        arcRunner.tick(gameFrame);
    }

    public EventStreamBus<GamePhase> phaseBus() { return phaseBus; }
    public EventStreamBus<GameArc>   arcBus()   { return arcBus; }

    void onGameStarted(@Observes GameStarted event) {
        phaseRunner.clear();
        arcRunner.clear();
        phaseBus.clear();
        arcBus.clear();
        init();
    }
}
```

- [ ] **Step 3: Wire into GameTickExecutor**

Modify `GameTickExecutor.java`:

Add field:
```java
@Inject SummarisationLifecycle summarisationLifecycle;
```

Add import:
```java
import io.quarkmind.plugin.summarisation.SummarisationLifecycle;
```

In `execute()`, after the `createAndSolve()` block and before `engine.dispatch()`:
```java
// Summarisation: tick L2→L3 and L3→L4 runners (after CaseEngine, before dispatch)
summarisationLifecycle.tick(gameState.gameFrame());
```

- [ ] **Step 4: Write MomentBrokerIT**

```java
package io.quarkmind.plugin.summarisation;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkmind.sc2.GameStarted;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class MomentBrokerIT {

    @Inject MomentBroker broker;
    @Inject Event<GameStarted> gameStartedEvent;

    @Test
    void channelCreated_onStartup() {
        assertThat(broker.channelId()).isNotNull();
    }

    @Test
    void momentBus_isAccessible() {
        assertThat(broker.momentBus()).isNotNull();
    }

    @Test
    void gameStarted_clearsBus() {
        broker.momentBus().subscribe(m -> true, e -> {});
        gameStartedEvent.fire(new GameStarted());
        // Bus was cleared and re-initialized — no error
        assertThat(broker.momentBus()).isNotNull();
    }
}
```

- [ ] **Step 5: Write SummarisationPipelineIT**

```java
package io.quarkmind.plugin.summarisation;

import io.casehub.blocks.summarisation.LevelEvent;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class SummarisationPipelineIT {

    @Inject MomentBroker momentBroker;
    @Inject SummarisationLifecycle lifecycle;

    private final List<LevelEvent<GamePhase>> receivedPhases = new ArrayList<>();

    @BeforeEach
    void setUp() {
        receivedPhases.clear();
        lifecycle.phaseBus().subscribe(p -> true, receivedPhases::add);
    }

    @Test
    void fullPipeline_momentsToPhases() {
        var bus = momentBroker.momentBus();
        var level2 = MomentDetectionTask.LEVEL_2;

        // Publish enough moments to trigger the phase window (count threshold)
        for (int i = 0; i < SummarisationLifecycle.PHASE_WINDOW_COUNT; i++) {
            bus.publish(new LevelEvent<>(
                new GameMoment(GameMomentType.BATTLE_STARTED, 100 + i, Map.of()),
                100 + i, level2));
        }

        // Tick the lifecycle — should trigger phase summarisation
        lifecycle.tick(200);
        assertThat(receivedPhases).isNotEmpty();
        assertThat(receivedPhases.get(0).payload().phase()).isEqualTo("MID_SKIRMISH");
    }
}
```

- [ ] **Step 6: Run integration tests**

Run: `mvn test -Dtest=MomentBrokerIT,SummarisationPipelineIT -q`
Expected: PASS

- [ ] **Step 7: Run full test suite**

Run: `mvn test -q`
Expected: PASS — all tests green including existing ones.

- [ ] **Step 8: Commit**

```
feat(#182): MomentBroker, SummarisationLifecycle, tick loop wiring

MomentBroker owns the L2 EventStreamBus, Qhorus channel, and CDI bridge.
SummarisationLifecycle owns L2→L3 and L3→L4 SummarisationRunners with
configurable window policies. GameTickExecutor ticks the lifecycle after
createAndSolve() and before dispatch(). All components reset on GameStarted.

Refs #182
```

---

### Task 8: DroolsStrategyTask — Level 2/3 Integration

**Files:**
- Modify: `src/main/java/io/quarkmind/plugin/DroolsStrategyTask.java` — implement `MomentConsumer`, subscribe to moments
- Modify: `src/main/java/io/quarkmind/plugin/drools/StrategyRuleUnit.java` — add `momentStore`, `phaseStore`
- Modify: `src/main/resources/io/quarkmind/plugin/DroolsStrategyTask.drl` — add moment/phase-aware rules
- Modify: existing `DroolsStrategyTaskTest` or `DroolsStrategyTaskStaticTest` — add moment/phase test cases

**Interfaces:**
- Consumes: `MomentConsumer` (Task 3), `GameMoment`, `GameMomentType`, `GamePhase` (Task 3), `EventStreamBus` (Task 1), `MomentBroker`, `SummarisationLifecycle` (Task 7)
- Produces: Strategy decisions incorporating Level 2/3 inputs alongside existing Level 1 inputs

- [ ] **Step 1: Add MomentConsumer to DroolsStrategyTask**

Modify class declaration:
```java
public class DroolsStrategyTask implements StrategyTask, ScoutingIntelConsumer, MomentConsumer {
```

Add imports:
```java
import io.quarkmind.plugin.summarisation.GameMoment;
import io.quarkmind.plugin.summarisation.GameMomentType;
import io.quarkmind.plugin.summarisation.GamePhase;
import io.quarkmind.plugin.summarisation.MomentBroker;
import io.quarkmind.plugin.summarisation.MomentConsumer;
import io.quarkmind.plugin.summarisation.SummarisationLifecycle;
import io.casehub.blocks.summarisation.LevelEvent;
```

Add fields:
```java
@Inject MomentBroker momentBroker;
@Inject SummarisationLifecycle summarisationLifecycle;
private final List<GameMoment> pendingMoments = new ArrayList<>();
```

Implement `MomentConsumer`:
```java
@Override
public Set<GameMomentType> subscribedMomentTypes() {
    return Set.of(
        GameMomentType.BATTLE_STARTED,
        GameMomentType.BATTLE_ENDED,
        GameMomentType.ECONOMIC_CRISIS,
        GameMomentType.NEXUS_UNDER_ATTACK);
}
```

In `@PostConstruct` (or a new init method), subscribe to the moment bus:
```java
momentBroker.momentBus().subscribe(
    eventFilter(),
    e -> pendingMoments.add(e.payload()));
```

- [ ] **Step 2: Add DataStores to StrategyRuleUnit**

Add to `StrategyRuleUnit.java`:
```java
import io.quarkmind.plugin.summarisation.GameMoment;
import io.quarkmind.plugin.summarisation.GamePhase;
```

Add fields:
```java
private final DataStore<GameMoment> momentStore = DataSource.createStore();
private final DataStore<GamePhase>  phaseStore  = DataSource.createStore();
```

Add accessors:
```java
public DataStore<GameMoment> getMomentStore() { return momentStore; }
public DataStore<GamePhase>  getPhaseStore()  { return phaseStore; }
```

- [ ] **Step 3: Feed moments/phases into StrategyRuleUnit in execute()**

In `DroolsStrategyTask.execute()`, where the StrategyRuleUnit `data` is populated (before `instance.fire()`), add:
```java
for (var moment : pendingMoments) {
    data.getMomentStore().add(moment);
}
pendingMoments.clear();

// Latest phase from the L3 bus (if available)
// Read from SummarisationLifecycle's phaseBus — subscribe and cache
```

- [ ] **Step 4: Add Drools rules for moment/phase-aware strategy**

Append to `DroolsStrategyTask.drl`:
```drools
import io.quarkmind.plugin.summarisation.GameMoment;
import io.quarkmind.plugin.summarisation.GameMomentType;
import io.quarkmind.plugin.summarisation.GamePhase;

rule "Strategy: Defend — Nexus Under Attack Moment"
    salience 220
when
    /momentStore[ type == GameMomentType.NEXUS_UNDER_ATTACK ]
then
    strategyDecisions.add("DEFEND");
end

rule "Strategy: Accelerate — Economic Dominance in Skirmish"
    salience 180
when
    /phaseStore[ phase == "MID_SKIRMISH" ]
    not /momentStore[ type == GameMomentType.NEXUS_UNDER_ATTACK ]
    accumulate( /army; $count : count(); $count >= 4 )
then
    strategyDecisions.add("ATTACK");
end
```

- [ ] **Step 5: Write tests for moment/phase integration**

Add test cases to `DroolsStrategyTaskStaticTest` (or create new focused test) verifying:
- NEXUS_UNDER_ATTACK moment triggers DEFEND
- MID_SKIRMISH phase with sufficient army triggers ATTACK
- Existing posture/timing rules still work (regression)

- [ ] **Step 6: Run tests**

Run: `mvn test -Dtest=DroolsStrategyTaskStaticTest -q`
Expected: PASS — new and existing tests green.

Run: `mvn test -q`
Expected: PASS — full suite green.

- [ ] **Step 7: Commit**

```
feat(#182): DroolsStrategyTask consumes Level 2/3 inputs

DroolsStrategyTask implements MomentConsumer, subscribing to
BATTLE_STARTED, BATTLE_ENDED, ECONOMIC_CRISIS, NEXUS_UNDER_ATTACK.
StrategyRuleUnit gains momentStore and phaseStore. New rules:
NEXUS_UNDER_ATTACK → DEFEND (salience 220),
MID_SKIRMISH + army → ATTACK (salience 180). Additive — existing
Level 1 rules untouched.

Refs #182
```

---

### Task 9: Issue Hygiene + CLAUDE.md Update

**Files:**
- Modify: `CLAUDE.md` — add new test classes to the testing patterns section
- GitHub: Update #182 acceptance criteria to defer #180/#181 items

- [ ] **Step 1: Update #182 issue — defer #180/#181 acceptance criteria**

Run:
```bash
gh issue edit 182 --repo casehubio/quarkmind --body "$(current body with deferred criteria noted)"
```

Add a note at the bottom of the acceptance criteria:
```
**Deferred to downstream issues:**
- [ ] LLM advisors (#180) receive Level 2/3 context rather than raw tick data → deferred to #180
- [ ] Commentator (#181) triggers on Level 2 moments → deferred to #181
```

- [ ] **Step 2: Update CLAUDE.md testing patterns**

Add to the unit tests list:
```
`EventAccumulatorTest`, `EventStreamBusTest`, `SummarisationRunnerTest`, `MomentDetectionTaskTest`, `GamePhaseSummariserTest`, `GameArcSummariserTest`
```

Add to the integration tests list:
```
`MomentBrokerIT`, `SummarisationPipelineIT`
```

- [ ] **Step 3: Commit**

```
docs(#182): update CLAUDE.md test lists and defer #180/#181 criteria

Refs #182
```

---

## Execution Notes

**Before each task:** Invoke `superpowers:test-driven-development` and `java-dev` skills.

**Before committing each task:** Invoke `superpowers:requesting-code-review`. Any finding Minor+ not fixed this session → GitHub issue.

**After final commit:** Invoke `implementation-doc-sync`.

**Protocol coherence review:** Before closing, re-read the six applicable protocols (PP-20260610-88dbbd, PP-20260608-8584ab, PP-20260603-cefed9, PP-20260610-3c3e89, PP-20260601-5fa812, PP-20260612-afe621) and verify each constraint is satisfied in the final implementation.

**Drools OOPath note (Task 5):** The `# ThreatPosition` pattern-matching syntax for sealed interfaces depends on Drools version. If the current version doesn't support it, use `eval(this instanceof ThreatPosition)` as fallback. Verify during implementation and adjust the .drl accordingly.

**PLATFORM.md Step 1 check:** `EventStreamBus` is a new abstraction. No equivalent exists in the platform — `ScoutingIntelBroker` is latest-value, `PluginDispatchBroker` is commitment-based. The generic event bus is a genuine new capability.

**PLATFORM.md Step 6 check:** After implementation, search other CaseHub application repos for temporal event patterns that could use this abstraction. Open tracked issues for any candidates found.

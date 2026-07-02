package io.quarkmind.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MutableMapCaseContext}.
 *
 * <p>Verifies read-through, write tracking, and mutation isolation.
 *
 * <p>Refs #207
 */
class MutableMapCaseContextTest {

    @Test
    void readsInitialData() {
        var ctx = new MutableMapCaseContext(Map.of("game.frame", 1, "game.minerals", 50));

        assertThat(ctx.getAs("game.frame", Integer.class)).isEqualTo(1);
        assertThat(ctx.getAs("game.minerals", Integer.class)).isEqualTo(50);
        assertThat(ctx.contains("game.frame")).isTrue();
        assertThat(ctx.contains("nonexistent")).isFalse();
    }

    @Test
    void writeTrackedInMutations() {
        var ctx = new MutableMapCaseContext(Map.of("game.frame", 1));
        ctx.set("agent.strategy", "ATTACK");

        assertThat(ctx.mutations()).containsEntry("agent.strategy", "ATTACK");
        assertThat(ctx.mutations()).hasSize(1); // only the write, not the initial data
    }

    @Test
    void writeVisibleViaGet() {
        var ctx = new MutableMapCaseContext(Map.of("game.frame", 1));
        ctx.set("agent.strategy", "ATTACK");

        assertThat(ctx.getAs("agent.strategy", String.class)).isEqualTo("ATTACK");
    }

    @Test
    void overwriteTrackedInMutations() {
        var ctx = new MutableMapCaseContext(Map.of("game.frame", 1));
        ctx.set("game.frame", 2);

        assertThat(ctx.getAs("game.frame", Integer.class)).isEqualTo(2);
        assertThat(ctx.mutations()).containsEntry("game.frame", 2);
    }

    @Test
    void setAllTracked() {
        var ctx = new MutableMapCaseContext(Map.of());
        ctx.setAll(Map.of("a", 1, "b", 2));

        assertThat(ctx.mutations()).containsEntry("a", 1);
        assertThat(ctx.mutations()).containsEntry("b", 2);
        assertThat(ctx.getAs("a", Integer.class)).isEqualTo(1);
    }

    @Test
    void removeUndoesPriorMutation() {
        var ctx = new MutableMapCaseContext(Map.of());
        ctx.set("key", "value");
        assertThat(ctx.mutations()).containsEntry("key", "value");

        ctx.remove("key");
        assertThat(ctx.contains("key")).isFalse();
        assertThat(ctx.mutations()).doesNotContainKey("key");
    }

    @Test
    void removeInitialKey_removesFromData() {
        var ctx = new MutableMapCaseContext(Map.of("key", "value"));
        ctx.remove("key");

        assertThat(ctx.contains("key")).isFalse();
        assertThat(ctx.mutations()).isEmpty();
    }

    @Test
    void getOrDefaultReturnsDefault() {
        var ctx = new MutableMapCaseContext(Map.of());

        assertThat(ctx.getOrDefault("missing", 42)).isEqualTo(42);
    }

    @Test
    void getOrDefaultReturnsValue() {
        var ctx = new MutableMapCaseContext(Map.of("key", 7));

        assertThat(ctx.getOrDefault("key", 42)).isEqualTo(7);
    }

    @Test
    void mutationsReturnsCopy() {
        var ctx = new MutableMapCaseContext(Map.of());
        ctx.set("a", 1);

        Map<String, Object> m1 = ctx.mutations();
        ctx.set("b", 2);
        Map<String, Object> m2 = ctx.mutations();

        assertThat(m1).hasSize(1);
        assertThat(m2).hasSize(2);
    }

    @Test
    void initialDataNotInMutations() {
        var ctx = new MutableMapCaseContext(Map.of("game.frame", 1, "game.minerals", 50));

        assertThat(ctx.mutations()).isEmpty();
    }

    @Test
    void typedGetters() {
        var ctx = new MutableMapCaseContext(Map.of(
            "str", "hello",
            "num", 42,
            "lng", 100L,
            "dbl", 3.14,
            "bool", true
        ));

        assertThat(ctx.getString("str")).isEqualTo("hello");
        assertThat(ctx.getInt("num")).isEqualTo(42);
        assertThat(ctx.getLong("lng")).isEqualTo(100L);
        assertThat(ctx.getDouble("dbl")).isEqualTo(3.14);
        assertThat(ctx.getBoolean("bool")).isTrue();
    }

    @Test
    void putIfAbsent_doesNotOverwrite() {
        var ctx = new MutableMapCaseContext(Map.of("key", "original"));

        Object result = ctx.putIfAbsent("key", "new");

        assertThat(result).isEqualTo("original");
        assertThat(ctx.getString("key")).isEqualTo("original");
        assertThat(ctx.mutations()).isEmpty();
    }

    @Test
    void putIfAbsent_setsWhenMissing() {
        var ctx = new MutableMapCaseContext(Map.of());

        Object result = ctx.putIfAbsent("key", "value");

        assertThat(result).isNull();
        assertThat(ctx.getString("key")).isEqualTo("value");
        assertThat(ctx.mutations()).containsEntry("key", "value");
    }

    @Test
    void compareAndSet_succeeds() {
        var ctx = new MutableMapCaseContext(Map.of("key", "old"));

        boolean result = ctx.compareAndSet("key", "old", "new");

        assertThat(result).isTrue();
        assertThat(ctx.getString("key")).isEqualTo("new");
        assertThat(ctx.mutations()).containsEntry("key", "new");
    }

    @Test
    void compareAndSet_failsOnMismatch() {
        var ctx = new MutableMapCaseContext(Map.of("key", "old"));

        boolean result = ctx.compareAndSet("key", "wrong", "new");

        assertThat(result).isFalse();
        assertThat(ctx.getString("key")).isEqualTo("old");
        assertThat(ctx.mutations()).isEmpty();
    }
}

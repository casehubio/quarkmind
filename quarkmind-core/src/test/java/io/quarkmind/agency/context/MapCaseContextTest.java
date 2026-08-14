package io.quarkmind.agency.context;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class MapCaseContextTest {

    @Test
    void containsReturnsTrueForPresentKey() {
        var ctx = new MapCaseContext(Map.of("READY", Boolean.TRUE));
        assertThat(ctx.contains("READY")).isTrue();
    }

    @Test
    void containsReturnsFalseForAbsentKey() {
        var ctx = new MapCaseContext(Map.of());
        assertThat(ctx.contains("READY")).isFalse();
    }

    @Test
    void getReturnsValueForPresentKey() {
        var ctx = new MapCaseContext(Map.of("MINERALS", 150));
        assertThat(ctx.get("MINERALS")).isEqualTo(150);
    }

    @Test
    void getReturnsNullForAbsentKey() {
        var ctx = new MapCaseContext(Map.of());
        assertThat(ctx.get("MINERALS")).isNull();
    }

    @Test
    void getOrDefaultReturnsValueWhenPresent() {
        var ctx = new MapCaseContext(Map.of("MINERALS", 200));
        assertThat(ctx.<Integer>getOrDefault("MINERALS", 0)).isEqualTo(200);
    }

    @Test
    void getOrDefaultReturnsDefaultWhenAbsent() {
        var ctx = new MapCaseContext(Map.of());
        assertThat(ctx.<Integer>getOrDefault("MINERALS", 0)).isEqualTo(0);
    }

    @Test
    void getListReturnsListWhenPresent() {
        var list = List.of("unit1", "unit2");
        var ctx = new MapCaseContext(Map.of("WORKERS", list));
        assertThat(ctx.getList("WORKERS", String.class)).isEqualTo(list);
    }

    @Test
    void getListReturnsEmptyWhenAbsent() {
        var ctx = new MapCaseContext(Map.of());
        assertThat(ctx.getList("WORKERS", String.class)).isEmpty();
    }

    @Test
    void sizeAndIsEmpty() {
        assertThat(new MapCaseContext(Map.of()).isEmpty()).isTrue();
        assertThat(new MapCaseContext(Map.of("K", "V")).size()).isEqualTo(1);
    }

    @Test
    void getKeysReturnsAllKeys() {
        var ctx = new MapCaseContext(Map.of("A", 1, "B", 2));
        assertThat(ctx.getKeys()).containsExactlyInAnyOrder("A", "B");
    }

    @Test
    void setThrowsUnsupportedOperationException() {
        var ctx = new MapCaseContext(Map.of());
        assertThatThrownBy(() -> ctx.set("K", "V"))
                  .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void removeThrowsUnsupportedOperationException() {
        var ctx = new MapCaseContext(Map.of());
        assertThatThrownBy(() -> ctx.remove("K"))
                  .isInstanceOf(UnsupportedOperationException.class);
    }
}

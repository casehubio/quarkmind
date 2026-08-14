package io.quarkmind.agency.spatial;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

class SpatialSPITest {

    @Test
    void visibilitySPI_returnsVisibleAndRemembered() {
        VisibilitySPI<String> vis = new VisibilitySPI<>() {
            @Override public Set<String> visible() { return Set.of("unit-1", "unit-2"); }
            @Override public Set<String> remembered() { return Set.of("unit-3"); }
        };

        assertEquals(2, vis.visible().size());
        assertEquals(1, vis.remembered().size());
        assertTrue(vis.visible().contains("unit-1"));
        assertTrue(vis.remembered().contains("unit-3"));
    }

    @Test
    void spatialMemory_rememberAndRecall() {
        var memory = new SpatialMemory() {
            private final Map<String, Map<String, Object>> store = new HashMap<>();

            @Override public void remember(String locationId, Map<String, Object> observation) {
                store.put(locationId, Map.copyOf(observation));
            }
            @Override public Map<String, Object> recall(String locationId) {
                return store.getOrDefault(locationId, Map.of());
            }
            @Override public Set<String> knownLocations() {
                return Set.copyOf(store.keySet());
            }
        };

        memory.remember("base-1", Map.of("minerals", 1500));
        assertEquals(1500, memory.recall("base-1").get("minerals"));
        assertTrue(memory.knownLocations().contains("base-1"));
        assertTrue(memory.recall("unknown").isEmpty());
    }

    @Test
    void navigationSPI_pathToDefaultReturnsEmptyList() {
        NavigationSPI nav = (x, y) -> true;
        assertTrue(nav.pathTo(10.0, 20.0).isEmpty());
    }

    @Test
    void navigationSPI_pathToCanBeOverridden() {
        NavigationSPI nav = new NavigationSPI() {
            @Override public boolean isReachable(double x, double y) { return true; }
            @Override public List<double[]> pathTo(double x, double y) {
                return List.of(new double[]{0, 0}, new double[]{x, y});
            }
        };

        var path = nav.pathTo(10.0, 20.0);
        assertEquals(2, path.size());
        assertArrayEquals(new double[]{10.0, 20.0}, path.get(1));
    }
}

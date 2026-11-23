package io.quarkmind.sc2.replay;

import io.quarkmind.domain.TerrainGrid;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MapTerrainCacheTest {

    @TempDir Path cacheDir;

    @Test
    void saveAndLoadRoundTrip() throws IOException {
        TerrainGrid original = TerrainGrid.emulatedMap();
        MapTerrainCache.save(cacheDir, "TorchesAIE", original);

        Optional<TerrainGrid> loaded = MapTerrainCache.load(cacheDir, "TorchesAIE");
        assertThat(loaded).isPresent();

        TerrainGrid grid = loaded.get();
        assertThat(grid.width()).isEqualTo(64);
        assertThat(grid.height()).isEqualTo(64);
        assertThat(grid.isWalkable(8, 8)).as("Nexus tile").isTrue();
        assertThat(grid.isWalkable(20, 18)).as("Wall tile").isFalse();
        assertThat(grid.isWalkable(12, 18)).as("Ramp tile").isTrue();
    }

    @Test
    void loadReturnsEmptyWhenCacheMiss() {
        Optional<TerrainGrid> loaded = MapTerrainCache.load(cacheDir, "NonExistentMap");
        assertThat(loaded).isEmpty();
    }

    @Test
    void savedFileContainsWidthHeightAndPathingData() throws IOException {
        TerrainGrid grid = TerrainGrid.flatMap(128, 96);
        MapTerrainCache.save(cacheDir, "TestMap", grid);

        Path file = cacheDir.resolve("TestMap.pathgrid");
        assertThat(file).exists();

        byte[] data = Files.readAllBytes(file);
        int width = (data[0] << 24) | ((data[1] & 0xFF) << 16) | ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        int height = (data[4] << 24) | ((data[5] & 0xFF) << 16) | ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);
        assertThat(width).isEqualTo(128);
        assertThat(height).isEqualTo(96);

        int expectedPathingBytes = (128 * 96 + 7) / 8;
        assertThat(data.length).isEqualTo(8 + expectedPathingBytes);
    }

    @Test
    void loadFromClasspath() {
        Optional<TerrainGrid> loaded = MapTerrainCache.loadFromClasspath("terrain/test-4x4.pathgrid");
        // Will be empty until we add a test resource — validates the method exists and handles missing
        assertThat(loaded).isEmpty();
    }

    @Test
    void largeMapRoundTrip() throws IOException {
        TerrainGrid large = TerrainGrid.flatMap(200, 176);
        MapTerrainCache.save(cacheDir, "LargeRealMap", large);

        Optional<TerrainGrid> loaded = MapTerrainCache.load(cacheDir, "LargeRealMap");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().width()).isEqualTo(200);
        assertThat(loaded.get().height()).isEqualTo(176);
        assertThat(loaded.get().isWalkable(150, 100)).as("Interior tile on flat map").isTrue();
    }
}

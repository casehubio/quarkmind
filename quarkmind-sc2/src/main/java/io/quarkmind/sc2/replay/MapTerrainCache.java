package io.quarkmind.sc2.replay;

import io.quarkmind.domain.TerrainGrid;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public final class MapTerrainCache {

    private MapTerrainCache() {}

    public static void save(Path cacheDir, String mapName, TerrainGrid grid) throws IOException {
        Files.createDirectories(cacheDir);
        Path file = cacheDir.resolve(mapName + ".pathgrid");
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file))) {
            out.writeInt(grid.width());
            out.writeInt(grid.height());
            out.write(grid.toPathingGrid());
        }
    }

    public static Optional<TerrainGrid> load(Path cacheDir, String mapName) {
        Path file = cacheDir.resolve(mapName + ".pathgrid");
        if (!Files.exists(file)) return Optional.empty();
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            return Optional.of(readGrid(in));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public static Optional<TerrainGrid> loadFromClasspath(String resourcePath) {
        try (InputStream raw = MapTerrainCache.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (raw == null) return Optional.empty();
            try (DataInputStream in = new DataInputStream(raw)) {
                return Optional.of(readGrid(in));
            }
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static TerrainGrid readGrid(DataInputStream in) throws IOException {
        int width = in.readInt();
        int height = in.readInt();
        byte[] pathing = in.readAllBytes();
        return TerrainGrid.fromPathingGrid(pathing, width, height);
    }
}

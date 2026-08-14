package io.quarkmind.sc2.real;

import io.quarkmind.domain.TerrainGrid;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SC2BotAgentTerrainTest {

    /**
     * 4×4 pathing grid, 1 bit per tile = 2 bytes.
     * Bit encoding: index = x + y*width; walkable=(bit==1)
     *   index 0=(0,0)..index 7=(3,1): byte 0
     *   index 8=(0,2)..index 15=(3,3): byte 1
     * Wall at (2,1): index = 2 + 1*4 = 6 → byte0, bit position = 7-6 = 1
     * All walkable except (2,1): byte0 = 0b11111101 = 0xFD, byte1 = 0xFF
     */
    @Test
    void fromPathingGrid_singleWallTile_correctWalkability() {
        byte[] data = {(byte) 0xFD, (byte) 0xFF};
        TerrainGrid grid = TerrainGrid.fromPathingGrid(data, 4, 4);

        assertThat(grid.isWalkable(0, 0)).isTrue();
        assertThat(grid.isWalkable(1, 0)).isTrue();
        assertThat(grid.isWalkable(3, 0)).isTrue();
        assertThat(grid.isWalkable(0, 1)).isTrue();
        assertThat(grid.isWalkable(2, 1)).isFalse();  // the wall
        assertThat(grid.isWalkable(3, 1)).isTrue();
        assertThat(grid.isWalkable(0, 3)).isTrue();
        assertThat(grid.isWalkable(3, 3)).isTrue();
    }

    @Test
    void fromPathingGrid_allWalkable() {
        byte[] data = {(byte) 0xFF, (byte) 0xFF};
        TerrainGrid grid = TerrainGrid.fromPathingGrid(data, 4, 4);
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 4; y++) {
                assertThat(grid.isWalkable(x, y)).as("(%d,%d) should be walkable", x, y).isTrue();
            }
        }
    }

    @Test
    void fromPathingGrid_allWalls() {
        byte[] data = {(byte) 0x00, (byte) 0x00};
        TerrainGrid grid = TerrainGrid.fromPathingGrid(data, 4, 4);
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 4; y++) {
                assertThat(grid.isWalkable(x, y)).as("(%d,%d) should be wall", x, y).isFalse();
            }
        }
    }

    @Test
    void fromPathingGrid_dimensions_areCorrect() {
        byte[] data = new byte[4]; // 4 bytes = 32 bits = 8×4 grid
        java.util.Arrays.fill(data, (byte) 0xFF);
        TerrainGrid grid = TerrainGrid.fromPathingGrid(data, 8, 4);
        assertThat(grid.width()).isEqualTo(8);
        assertThat(grid.height()).isEqualTo(4);
    }
}

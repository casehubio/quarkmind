package io.quarkmind.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TerrainGridRampTest {

    @Test void rampPositions_noRamps_returnsEmpty() {
        var grid = new TerrainGrid(4, 4, fill(4, 4, TerrainGrid.Height.HIGH));
        assertThat(grid.rampPositions()).isEmpty();
    }

    @Test void rampPositions_singleRampCluster_returnsCentroid() {
        var heights = fill(8, 8, TerrainGrid.Height.HIGH);
        heights[3][3] = TerrainGrid.Height.RAMP;
        heights[3][4] = TerrainGrid.Height.RAMP;
        heights[4][3] = TerrainGrid.Height.RAMP;
        heights[4][4] = TerrainGrid.Height.RAMP;
        var grid = new TerrainGrid(8, 8, heights);
        var ramps = grid.rampPositions();
        assertThat(ramps).hasSize(1);
        assertThat(ramps.get(0).x()).isCloseTo(3.5f, within(0.1f));
        assertThat(ramps.get(0).y()).isCloseTo(3.5f, within(0.1f));
    }

    @Test void rampPositions_twoSeparateRamps_returnsTwoCentroids() {
        var heights = fill(16, 16, TerrainGrid.Height.HIGH);
        heights[2][2] = TerrainGrid.Height.RAMP;
        heights[2][3] = TerrainGrid.Height.RAMP;
        heights[12][12] = TerrainGrid.Height.RAMP;
        heights[12][13] = TerrainGrid.Height.RAMP;
        var grid = new TerrainGrid(16, 16, heights);
        assertThat(grid.rampPositions()).hasSize(2);
    }

    @Test void rampPositions_resultIsImmutable() {
        var heights = fill(8, 8, TerrainGrid.Height.HIGH);
        heights[3][3] = TerrainGrid.Height.RAMP;
        var grid = new TerrainGrid(8, 8, heights);
        assertThatThrownBy(() -> grid.rampPositions().add(new Point2d(0f, 0f)))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    private static TerrainGrid.Height[][] fill(int w, int h, TerrainGrid.Height value) {
        var grid = new TerrainGrid.Height[h][w];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                grid[y][x] = value;
        return grid;
    }
}

package io.quarkmind.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TerrainGridTest {

    // ---- isWalkable — same contract as WalkabilityGrid ----

    @Test void emulatedMapNexusTileIsWalkable() {
        assertThat(TerrainGrid.emulatedMap().isWalkable(8, 8)).isTrue();
    }
    @Test void emulatedMapStagingTileIsWalkable() {
        assertThat(TerrainGrid.emulatedMap().isWalkable(26, 26)).isTrue();
    }
    @Test void emulatedMapWallTileIsBlocked() {
        assertThat(TerrainGrid.emulatedMap().isWalkable(20, 18)).isFalse();
    }
    @Test void emulatedMapChokeGapIsWalkable() {
        assertThat(TerrainGrid.emulatedMap().isWalkable(12, 18)).isTrue();
    }
    @Test void emulatedMapChokeEdgesAreBlocked() {
        assertThat(TerrainGrid.emulatedMap().isWalkable(10, 18)).isFalse();
        assertThat(TerrainGrid.emulatedMap().isWalkable(14, 18)).isFalse();
    }
    @Test void outOfBoundsIsNotWalkable() {
        TerrainGrid g = TerrainGrid.emulatedMap();
        assertThat(g.isWalkable(-1,  0)).isFalse();
        assertThat(g.isWalkable(64,  0)).isFalse();
        assertThat(g.isWalkable( 0, 64)).isFalse();
    }

    // ---- heightAt ----

    @Test void emulatedMapHighGroundCorrect() {
        TerrainGrid g = TerrainGrid.emulatedMap();
        assertThat(g.heightAt(0, 19)).isEqualTo(TerrainGrid.Height.HIGH);
        assertThat(g.heightAt(26, 26)).isEqualTo(TerrainGrid.Height.HIGH);
    }
    @Test void emulatedMapLowGroundCorrect() {
        TerrainGrid g = TerrainGrid.emulatedMap();
        assertThat(g.heightAt(8, 8)).isEqualTo(TerrainGrid.Height.LOW);
        assertThat(g.heightAt(0, 17)).isEqualTo(TerrainGrid.Height.LOW);
    }
    @Test void emulatedMapRampCorrect() {
        TerrainGrid g = TerrainGrid.emulatedMap();
        assertThat(g.heightAt(11, 18)).isEqualTo(TerrainGrid.Height.RAMP);
        assertThat(g.heightAt(12, 18)).isEqualTo(TerrainGrid.Height.RAMP);
        assertThat(g.heightAt(13, 18)).isEqualTo(TerrainGrid.Height.RAMP);
    }
    @Test void emulatedMapWallsCorrect() {
        TerrainGrid g = TerrainGrid.emulatedMap();
        assertThat(g.heightAt(10, 18)).isEqualTo(TerrainGrid.Height.WALL);
        assertThat(g.heightAt(14, 18)).isEqualTo(TerrainGrid.Height.WALL);
        assertThat(g.heightAt( 0, 18)).isEqualTo(TerrainGrid.Height.WALL);
    }
    @Test void isWalkableMatchesHeight() {
        TerrainGrid g = TerrainGrid.emulatedMap();
        assertThat(g.isWalkable( 0, 19)).isTrue();   // HIGH → walkable
        assertThat(g.isWalkable( 8,  8)).isTrue();   // LOW  → walkable
        assertThat(g.isWalkable(12, 18)).isTrue();   // RAMP → walkable
        assertThat(g.isWalkable(20, 18)).isFalse();  // WALL → blocked
    }
    @Test void outOfBoundsHeightIsWall() {
        TerrainGrid g = TerrainGrid.emulatedMap();
        assertThat(g.heightAt(-1,  0)).isEqualTo(TerrainGrid.Height.WALL);
        assertThat(g.heightAt(64,  0)).isEqualTo(TerrainGrid.Height.WALL);
    }

    // ---- dimensions ----

    @Test void widthAndHeightCorrect() {
        TerrainGrid g = TerrainGrid.emulatedMap();
        assertThat(g.width()).isEqualTo(64);
        assertThat(g.height()).isEqualTo(64);
    }

    // ---- fromPathingGrid ----

    @Test void fromPathingGrid_walkableTileIsLow() {
        TerrainGrid g = TerrainGrid.fromPathingGrid(new byte[]{(byte) 0xFF}, 8, 1);
        assertThat(g.heightAt(0, 0)).isEqualTo(TerrainGrid.Height.LOW);
        assertThat(g.isWalkable(0, 0)).isTrue();
    }
    @Test void fromPathingGrid_nonWalkableTileIsWall() {
        TerrainGrid g = TerrainGrid.fromPathingGrid(new byte[]{(byte) 0x00}, 8, 1);
        assertThat(g.heightAt(0, 0)).isEqualTo(TerrainGrid.Height.WALL);
        assertThat(g.isWalkable(0, 0)).isFalse();
    }
    @Test void fromPathingGrid_mixedBitsDecodeCorrectly() {
        // 0xB2 = 1011_0010: bit7=1(walk), bit6=0(wall), bit0=0(wall)
        TerrainGrid g = TerrainGrid.fromPathingGrid(new byte[]{(byte) 0xB2}, 8, 1);
        assertThat(g.isWalkable(0, 0)).isTrue();   // bit 7 = 1
        assertThat(g.isWalkable(1, 0)).isFalse();  // bit 6 = 0
        assertThat(g.isWalkable(7, 0)).isFalse();  // bit 0 = 0
    }

    // ---- toPathingGrid ----

    @Test
    void toPathingGrid_roundTripsWithFromPathingGrid() {
        TerrainGrid original = TerrainGrid.emulatedMap();
        byte[] encoded = original.toPathingGrid();

        // 64×64 at 1 bpp = 64*64/8 = 512 bytes
        assertThat(encoded).hasSize(512);

        TerrainGrid roundTripped = TerrainGrid.fromPathingGrid(encoded, 64, 64);
        for (int x = 0; x < 64; x++) {
            for (int y = 0; y < 64; y++) {
                assertThat(roundTripped.isWalkable(x, y))
                    .as("walkability at (%d,%d)", x, y)
                    .isEqualTo(original.isWalkable(x, y));
            }
        }
    }

    @Test
    void toPathingGrid_wallTilesEncodeAsZero_walkableTilesEncodeAsOne() {
        TerrainGrid grid = TerrainGrid.emulatedMap();
        byte[] encoded = grid.toPathingGrid();

        // y=18, x=5 is WALL → bit should be 0
        int wallIndex = 5 + 18 * 64;
        int wallBit = (encoded[wallIndex / 8] >> (7 - wallIndex % 8)) & 1;
        assertThat(wallBit).as("wall tile (5,18)").isEqualTo(0);

        // y=18, x=12 is RAMP → bit should be 1
        int rampIndex = 12 + 18 * 64;
        int rampBit = (encoded[rampIndex / 8] >> (7 - rampIndex % 8)) & 1;
        assertThat(rampBit).as("ramp tile (12,18)").isEqualTo(1);

        // y=10, x=30 is LOW → bit should be 1
        int lowIndex = 30 + 10 * 64;
        int lowBit = (encoded[lowIndex / 8] >> (7 - lowIndex % 8)) & 1;
        assertThat(lowBit).as("low tile (30,10)").isEqualTo(1);
    }

    // ---- movementCost ----

    private TerrainGrid single(TerrainGrid.Height h) {
        TerrainGrid.Height[][] g = {{h}};
        return new TerrainGrid(1, 1, g);
    }

    @Test
    void movementCost_low_isOne() {
        assertThat(single(TerrainGrid.Height.LOW).movementCost(0, 0)).isEqualTo(1.0);
    }

    @Test
    void movementCost_high_isOne() {
        assertThat(single(TerrainGrid.Height.HIGH).movementCost(0, 0)).isEqualTo(1.0);
    }

    @Test
    void movementCost_ramp_isOnePointFive() {
        assertThat(single(TerrainGrid.Height.RAMP).movementCost(0, 0)).isEqualTo(1.5);
    }

    @Test
    void movementCost_wall_isOne() {
        assertThat(single(TerrainGrid.Height.WALL).movementCost(0, 0)).isEqualTo(1.0);
    }

    @Test
    void movementCost_outOfBounds_isOne() {
        assertThat(single(TerrainGrid.Height.LOW).movementCost(-1, 0)).isEqualTo(1.0);
        assertThat(single(TerrainGrid.Height.LOW).movementCost(5, 5)).isEqualTo(1.0);
    }
}

package io.quarkmind.plugin.coaching;

import io.quarkmind.domain.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class LocationResolverTest {

    private final LocationResolver resolver = new LocationResolver();

    @Test void playerBase_resolvesToPlayerStart() {
        var state = stateWithMapInfo(mapInfo());
        assertThat(resolver.resolve(new LocationReference.PlayerBase(), state))
            .isEqualTo(new Point2d(8f, 8f));
    }

    @Test void enemyBase_resolvesToEnemyStart() {
        var state = stateWithMapInfo(mapInfo());
        assertThat(resolver.resolve(new LocationReference.EnemyBase(), state))
            .isEqualTo(new Point2d(56f, 56f));
    }

    @Test void enemyBase_nullWhenUnknown() {
        var info = new MapInfo(new Point2d(8f, 8f), null, 64, 64, List.of(), List.of(), List.of());
        assertThat(resolver.resolve(new LocationReference.EnemyBase(), stateWithMapInfo(info)))
            .isNull();
    }

    @Test void mapCenter() {
        assertThat(resolver.resolve(new LocationReference.MapCenter(), stateWithMapInfo(mapInfo())))
            .isEqualTo(new Point2d(32f, 32f));
    }

    @Test void expansionOrdinal_validIndex() {
        assertThat(resolver.resolve(new LocationReference.ExpansionOrdinal(0), stateWithMapInfo(mapInfo())))
            .isEqualTo(new Point2d(10f, 10f));
    }

    @Test void expansionOrdinal_outOfBounds_returnsNull() {
        assertThat(resolver.resolve(new LocationReference.ExpansionOrdinal(99), stateWithMapInfo(mapInfo())))
            .isNull();
    }

    @Test void watchtower_validIndex() {
        assertThat(resolver.resolve(new LocationReference.Watchtower(0), stateWithMapInfo(mapInfo())))
            .isEqualTo(new Point2d(32f, 32f));
    }

    @Test void watchtower_noTowersOnMap_returnsNull() {
        var info = new MapInfo(new Point2d(8f, 8f), null, 64, 64, List.of(), List.of(), List.of());
        assertThat(resolver.resolve(new LocationReference.Watchtower(0), stateWithMapInfo(info)))
            .isNull();
    }

    @Test void absolutePosition() {
        assertThat(resolver.resolve(new LocationReference.AbsolutePosition(25f, 30f), stateWithMapInfo(null)))
            .isEqualTo(new Point2d(25f, 30f));
    }

    @Test void nullMapInfo_nonAbsolute_returnsNull() {
        assertThat(resolver.resolve(new LocationReference.PlayerBase(), stateWithMapInfo(null)))
            .isNull();
    }

    @Test void nearestRamp_resolvesToClosestRamp() {
        var ramps = List.of(new Point2d(20f, 15f), new Point2d(50f, 50f));
        var info = new MapInfo(new Point2d(8f, 8f), new Point2d(56f, 56f), 64, 64, List.of(), List.of(), ramps);
        assertThat(resolver.resolve(new LocationReference.NearestRamp(new LocationReference.PlayerBase()), stateWithMapInfo(info)))
            .isEqualTo(new Point2d(20f, 15f));
    }

    @Test void nearestRamp_noRamps_returnsNull() {
        var info = new MapInfo(new Point2d(8f, 8f), null, 64, 64, List.of(), List.of(), List.of());
        assertThat(resolver.resolve(new LocationReference.NearestRamp(new LocationReference.PlayerBase()), stateWithMapInfo(info)))
            .isNull();
    }

    @Test void nearestRamp_unresolvedRelativeTo_returnsNull() {
        var info = new MapInfo(new Point2d(8f, 8f), null, 64, 64, List.of(), List.of(),
            List.of(new Point2d(20f, 15f)));
        assertThat(resolver.resolve(new LocationReference.NearestRamp(new LocationReference.EnemyBase()), stateWithMapInfo(info)))
            .isNull();
    }

    private MapInfo mapInfo() {
        return new MapInfo(
            new Point2d(8f, 8f), new Point2d(56f, 56f), 64, 64,
            List.of(new ExpansionLocation(0, new Point2d(10f, 10f)), new ExpansionLocation(1, new Point2d(30f, 30f))),
            List.of(new NeutralFeature("t1", NeutralFeatureType.XELNAGA_TOWER, new Point2d(32f, 32f))),
            List.of(new Point2d(20f, 15f))
        );
    }

    private GameState stateWithMapInfo(MapInfo info) {
        return new GameState(0, 0, 0, 0, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 0L, info, PlayerEconomyStats.EMPTY, PlayerEconomyStats.EMPTY, Set.of(), Set.of());
    }
}

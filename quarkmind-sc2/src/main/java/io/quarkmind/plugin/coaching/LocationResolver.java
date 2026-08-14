package io.quarkmind.plugin.coaching;

import io.quarkmind.domain.GameState;
import io.quarkmind.domain.MapInfo;
import io.quarkmind.domain.NeutralFeature;
import io.quarkmind.domain.NeutralFeatureType;
import io.quarkmind.domain.Point2d;

import java.util.Comparator;
import java.util.List;

@jakarta.enterprise.context.ApplicationScoped
public class LocationResolver {

    public Point2d resolve(LocationReference ref, GameState state) {
        if (ref instanceof LocationReference.AbsolutePosition a) {
            return new Point2d(a.x(), a.y());
        }
        if (state.mapInfo() == null) {return null;}
        MapInfo info = state.mapInfo();
        return switch (ref) {
            case LocationReference.PlayerBase pb -> info.playerStart();
            case LocationReference.EnemyBase eb -> info.enemyStart();
            case LocationReference.MapCenter mc -> new Point2d(info.mapWidth() / 2f, info.mapHeight() / 2f);
            case LocationReference.ExpansionOrdinal e -> expansionByOrdinal(e.ordinal(), info);
            case LocationReference.NearestRamp nr -> nearestRamp(resolve(nr.relativeTo(), state), info);
            case LocationReference.Watchtower w -> watchtowerByIndex(w.index(), info);
            case LocationReference.AbsolutePosition a -> new Point2d(a.x(), a.y());
        };}

    private Point2d expansionByOrdinal(int ordinal, MapInfo info) {
        if (ordinal < 0 || ordinal >= info.expansions().size()) return null;
        return info.expansions().get(ordinal).position();
    }

    private Point2d nearestRamp(Point2d relativeTo, MapInfo info) {
        if (relativeTo == null || info.rampPositions().isEmpty()) return null;
        return info.rampPositions().stream()
            .min(Comparator.comparingDouble(r -> r.distanceTo(relativeTo)))
            .orElse(null);
    }

    private Point2d watchtowerByIndex(int index, MapInfo info) {
        List<NeutralFeature> towers = info.neutralFeatures().stream()
            .filter(f -> f.type() == NeutralFeatureType.XELNAGA_TOWER)
            .toList();
        if (index < 0 || index >= towers.size()) return null;
        return towers.get(index).position();
    }
}

package io.quarkmind.plugin.coaching;

public sealed interface LocationReference permits
    LocationReference.PlayerBase,
    LocationReference.EnemyBase,
    LocationReference.MapCenter,
    LocationReference.ExpansionOrdinal,
    LocationReference.NearestRamp,
    LocationReference.Watchtower,
    LocationReference.AbsolutePosition {

    record PlayerBase() implements LocationReference {}
    record EnemyBase() implements LocationReference {}
    record MapCenter() implements LocationReference {}
    record ExpansionOrdinal(int ordinal) implements LocationReference {}
    record NearestRamp(LocationReference relativeTo) implements LocationReference {}
    record Watchtower(int index) implements LocationReference {}
    record AbsolutePosition(float x, float y) implements LocationReference {}
}

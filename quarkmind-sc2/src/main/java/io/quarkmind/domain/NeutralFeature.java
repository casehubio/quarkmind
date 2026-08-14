package io.quarkmind.domain;

public record NeutralFeature(String tag, NeutralFeatureType type, Point2d position) implements Positionable {}

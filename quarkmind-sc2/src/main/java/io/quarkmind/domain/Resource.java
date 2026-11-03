package io.quarkmind.domain;

public record Resource(String tag, Point2d position, int remaining, boolean rich) implements Positionable {
    public Resource(String tag, Point2d position, int remaining) {
        this(tag, position, remaining, false);
    }
}

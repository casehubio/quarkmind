package io.quarkmind.domain;

public record Point2d(float x, float y) {

    public double distanceTo(Point2d other) {
        double dx = this.x - other.x;
        double dy = this.y - other.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    public static Point2d centroidOf(java.util.List<? extends Positionable> items) {
        if (items == null || items.isEmpty()) {return null;}
        float sumX = 0, sumY = 0;
        for (var item : items) {
            sumX += item.position().x;
            sumY += item.position().y;
        }
        return new Point2d(sumX / items.size(), sumY / items.size());
    }

}

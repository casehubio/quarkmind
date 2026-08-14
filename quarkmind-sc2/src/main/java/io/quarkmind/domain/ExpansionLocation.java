package io.quarkmind.domain;

import java.util.*;

public record ExpansionLocation(int ordinal, Point2d position) {

    private static final double CLUSTER_RADIUS = 12.0;

    public static List<ExpansionLocation> fromResources(
            List<Resource> minerals, List<Resource> geysers, Point2d playerStart) {
        List<Resource> all = new ArrayList<>(minerals.size() + geysers.size());
        all.addAll(minerals);
        all.addAll(geysers);
        if (all.isEmpty()) return List.of();

        boolean[] visited = new boolean[all.size()];
        List<List<Resource>> clusters = new ArrayList<>();

        for (int i = 0; i < all.size(); i++) {
            if (visited[i]) continue;
            List<Resource> cluster = new ArrayList<>();
            Deque<Integer> stack = new ArrayDeque<>();
            stack.push(i);
            visited[i] = true;
            while (!stack.isEmpty()) {
                int idx = stack.pop();
                cluster.add(all.get(idx));
                for (int j = 0; j < all.size(); j++) {
                    if (!visited[j] && all.get(idx).position().distanceTo(all.get(j).position()) <= CLUSTER_RADIUS) {
                        visited[j] = true;
                        stack.push(j);
                    }
                }
            }
            clusters.add(cluster);
        }

        List<Point2d> centroids = clusters.stream()
            .map(Point2d::centroidOf)
            .filter(Objects::nonNull)
            .toList();

        List<Point2d> sorted = centroids.stream()
            .sorted(Comparator.comparingDouble(p -> p.distanceTo(playerStart)))
            .toList();

        List<ExpansionLocation> result = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            result.add(new ExpansionLocation(i, sorted.get(i)));
        }
        return List.copyOf(result);
    }
}

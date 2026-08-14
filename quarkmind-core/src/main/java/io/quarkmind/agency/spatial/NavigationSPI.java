package io.quarkmind.agency.spatial;

import java.util.List;

public interface NavigationSPI {

    boolean isReachable(double x, double y);

    default List<double[]> pathTo(double x, double y) {
        return List.of();
    }
}

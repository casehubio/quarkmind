package io.quarkmind.agency.spatial;

import java.util.Map;
import java.util.Set;

public interface SpatialMemory {
    void remember(String locationId, Map<String, Object> observation);
    Map<String, Object> recall(String locationId);
    Set<String> knownLocations();
}

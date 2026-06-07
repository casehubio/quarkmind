package io.quarkmind.plugin;

import io.quarkmind.domain.Point2d;

record TacticsIntelCache(Point2d threatPosition, String posture, Boolean timingAlert) {
    static TacticsIntelCache empty() { return new TacticsIntelCache(null, null, null); }
}

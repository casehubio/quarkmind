package io.quarkmind.agent;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.util.List;

@ConfigMapping(prefix = "quarkmind.milestones")
public interface MilestoneConfig {

    @WithDefault("true")
    boolean enabled();

    @WithName("dead-zone-threshold")
    @WithDefault("0.15")
    double deadZoneThreshold();

    @WithName("frame-thresholds")
    List<FrameThreshold> frameThresholds();

    @WithName("phase-triggers")
    PhaseTriggers phaseTriggers();

    Dominance dominance();

    interface FrameThreshold {
        long frame();
        double weight();
    }

    interface PhaseTriggers {
        @WithName("expected-game-length")
        @WithDefault("20160")
        long expectedGameLength();

        @WithName("min-weight")
        @WithDefault("0.1")
        double minWeight();

        @WithName("max-weight")
        @WithDefault("0.8")
        double maxWeight();
    }

    interface Dominance {
        @WithName("max-expected-delta")
        @WithDefault("40")
        int maxExpectedDelta();
    }
}

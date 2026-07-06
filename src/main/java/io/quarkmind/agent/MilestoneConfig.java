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
        @WithDefault("0.30")
        double economyWeight();
        @WithDefault("0.35")
        double armyWeight();
        @WithDefault("0.20")
        double techWeight();
        @WithDefault("0.15")
        double basesWeight();

        @WithName("max-expected-economy-delta")
        @WithDefault("25.0")
        double maxExpectedEconomyDelta();
        @WithName("max-expected-army-delta")
        @WithDefault("3000")
        int maxExpectedArmyDelta();
        @WithName("max-expected-tech-delta")
        @WithDefault("2.0")
        double maxExpectedTechDelta();
        @WithName("max-expected-base-delta")
        @WithDefault("3")
        int maxExpectedBaseDelta();

        @WithName("min-enemy-visibility")
        @WithDefault("3")
        int minEnemyVisibility();
    }
}

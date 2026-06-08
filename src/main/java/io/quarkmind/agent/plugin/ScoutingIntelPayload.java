package io.quarkmind.agent.plugin;

import io.quarkmind.domain.Point2d;

public sealed interface ScoutingIntelPayload
        permits ScoutingIntelPayload.ThreatPosition,
                ScoutingIntelPayload.PostureUpdate,
                ScoutingIntelPayload.TimingAlert,
                ScoutingIntelPayload.ArmySize,
                ScoutingIntelPayload.BuildOrder {

    ScoutingIntelType type();

    record ThreatPosition(Point2d position) implements ScoutingIntelPayload {
        public ScoutingIntelType type() { return ScoutingIntelType.THREAT_POSITION; }
    }
    record PostureUpdate(String posture) implements ScoutingIntelPayload {
        public ScoutingIntelType type() { return ScoutingIntelType.POSTURE; }
    }
    record TimingAlert(boolean incoming) implements ScoutingIntelPayload {
        public ScoutingIntelType type() { return ScoutingIntelType.TIMING_ALERT; }
    }
    record ArmySize(int count) implements ScoutingIntelPayload {
        public ScoutingIntelType type() { return ScoutingIntelType.ARMY_SIZE; }
    }
    record BuildOrder(String detected) implements ScoutingIntelPayload {
        public ScoutingIntelType type() { return ScoutingIntelType.BUILD_ORDER; }
    }
}

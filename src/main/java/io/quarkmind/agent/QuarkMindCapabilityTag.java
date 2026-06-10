package io.quarkmind.agent;

public final class QuarkMindCapabilityTag {
    public static final String STRATEGY  = "starcraft.strategy";
    public static final String ECONOMICS = "starcraft.economics";
    public static final String TACTICS   = "starcraft.tactics";
    public static final String SCOUTING  = "starcraft.scouting";

    // L6: opponent-context tags for trust-weighted strategy routing
    public static final String STRATEGY_VS_AGGRESSIVE = "starcraft.strategy.vs.aggressive";
    public static final String STRATEGY_VS_ECONOMIC   = "starcraft.strategy.vs.economic";
    public static final String STRATEGY_VS_DEFENSIVE  = "starcraft.strategy.vs.defensive";
    public static final String STRATEGY_VS_UNKNOWN    = "starcraft.strategy.vs.unknown";

    private QuarkMindCapabilityTag() {}
}

package io.quarkmind.agent;

import java.util.List;

public final class QuarkMindCaseFile {
    // Observation state — written by GameStateTranslator
    public static final String MINERALS        = "game.resources.minerals";
    public static final String VESPENE         = "game.resources.vespene";
    public static final String SUPPLY_USED     = "game.resources.supply.used";
    public static final String SUPPLY_CAP      = "game.resources.supply.cap";
    public static final String WORKERS         = "game.units.workers";
    public static final String ARMY            = "game.units.army";
    public static final String MY_BUILDINGS    = "game.units.buildings";
    public static final String GEYSERS         = "game.resources.geysers";
    public static final String ENEMY_UNITS     = "game.intel.enemy.units";
    public static final String GAME_FRAME      = "game.frame";
    public static final String READY           = "game.ready";

    // Per-tick resource budget — written by GameStateTranslator, consumed by plugins
    public static final String RESOURCE_BUDGET = "agent.resources.budget";

    // Agent state — written by plugins
    public static final String STRATEGY             = "agent.strategy.current";
    /** Written by StrategyTrustRouter; read by SequenceWorker step activateIf() in Phase 2. */
    public static final String STRATEGY_SELECTED_ID = "agent.strategy.selected.id";
    public static final String CRISIS          = "agent.intent.crisis";
    public static final String ENEMY_ARMY_SIZE = "agent.intel.enemy.army.size";
    public static final String ENEMY_BUILD_ORDER       = "agent.intel.enemy.build";
    public static final String TIMING_ATTACK_INCOMING  = "agent.intel.enemy.timing";
    public static final String ENEMY_POSTURE           = "agent.intel.enemy.posture";

    /** All known CaseFile/CaseContext keys — used by CaseFileContext bridge (Phase 1). */
    public static final List<String> ALL_KEYS = List.of(
        MINERALS, VESPENE, SUPPLY_USED, SUPPLY_CAP,
        WORKERS, ARMY, MY_BUILDINGS, GEYSERS, ENEMY_UNITS, GAME_FRAME, READY,
        RESOURCE_BUDGET, STRATEGY, CRISIS, ENEMY_ARMY_SIZE,
        ENEMY_BUILD_ORDER, TIMING_ATTACK_INCOMING, ENEMY_POSTURE,
        STRATEGY_SELECTED_ID
    );

    private QuarkMindCaseFile() {}
}

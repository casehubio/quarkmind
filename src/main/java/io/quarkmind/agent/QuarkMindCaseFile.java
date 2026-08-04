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
    public static final String GAME_STATE      = "game.state";


    // Per-tick resource budget — written by GameStateTranslator, consumed by plugins
    public static final String RESOURCE_BUDGET = "agent.resources.budget";

    // Agent state — written by plugins
    public static final String STRATEGY             = "agent.strategy.current";
    /** Written by SC2StrategyRouterTask; read by strategy task activateIf(). */
    public static final String STRATEGY_SELECTED_ID = "agent.strategy.selected.id";
    public static final String STRATEGY_ROUTED_CONTEXT = "agent.strategy.routed.context";
    public static final String STRATEGY_ROUTED_ARCHETYPE = "agent.strategy.routed.archetype";
    public static final String STRATEGY_ROUTED_CONFIDENCE = "agent.strategy.routed.confidence";
    public static final String STRATEGY_PIVOT_COUNT = "agent.strategy.pivot.count";

    /** Reserved — no plugin currently writes this key. Placeholder for emergency override signals. */
    public static final String CRISIS          = "agent.intent.crisis";
    public static final String ENEMY_ARMY_SIZE = "agent.intel.enemy.army.size";
    public static final String ENEMY_BUILD_ORDER       = "agent.intel.enemy.build";
    public static final String TIMING_ATTACK_INCOMING  = "agent.intel.enemy.timing";
    public static final String ENEMY_POSTURE           = "agent.intel.enemy.posture";
    public static final String MOMENTS_LATEST          = "agent.intel.moments.latest";
    public static final String GAME_PHASE              = "agent.intel.game.phase";


    // Commentary triggers — written by CommentaryTriggerBuilder, CommentaryAccumulator
    public static final String COMMENTARY_TRIGGER          = "game.commentary.trigger";
    public static final String COMMENTARY_NARRATIVE_TRIGGER = "game.commentary.narrative.trigger";
    public static final String GAME_MODE                    = "game.mode";
    public static final String COACHING_TRIGGER             = "game.coaching.trigger";
    public static final String LLM_FALLBACK_TRIGGER         = "game.scouting.llm-fallback.trigger";
    public static final String LLM_FALLBACK_ARCHETYPE       = "agent.scouting.llm-fallback.archetype";
    public static final String LLM_FALLBACK_CONFIDENCE      = "agent.scouting.llm-fallback.confidence";
    public static final String LLM_FALLBACK_RATIONALE       = "agent.scouting.llm-fallback.rationale";


    public static final List<String> ALL_KEYS = List.of(
            MINERALS, VESPENE, SUPPLY_USED, SUPPLY_CAP,
            WORKERS, ARMY, MY_BUILDINGS, GEYSERS, ENEMY_UNITS, GAME_FRAME, READY,
            GAME_STATE,
            RESOURCE_BUDGET, STRATEGY, CRISIS, ENEMY_ARMY_SIZE,
            ENEMY_BUILD_ORDER, TIMING_ATTACK_INCOMING, ENEMY_POSTURE, MOMENTS_LATEST, GAME_PHASE,
            STRATEGY_SELECTED_ID, STRATEGY_ROUTED_CONTEXT, STRATEGY_ROUTED_ARCHETYPE,
            STRATEGY_ROUTED_CONFIDENCE, STRATEGY_PIVOT_COUNT,
            COMMENTARY_TRIGGER, COMMENTARY_NARRATIVE_TRIGGER,
            GAME_MODE, COACHING_TRIGGER
                                                       );

    private QuarkMindCaseFile() {}
}

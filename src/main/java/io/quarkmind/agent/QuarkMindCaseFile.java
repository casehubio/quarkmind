package io.quarkmind.agent;


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
    // CBR Tier 2 enrichment — written by plugins, read by SC2CbrRetentionObserver
    public static final String OPPONENT_ID                  = "game.opponent.id";
    public static final String SCOUTING_DISPATCH_FRAME      = "game.scouting.dispatch.frame";
    public static final String CBR_INFLUENCED_SELECTION     = "agent.strategy.cbr.influenced";
    public static final String STRATEGY_INITIAL_ARCHETYPE  = "agent.strategy.initial.archetype";
    public static final String SCOUTING_FINAL_ASSESSMENT   = "agent.scouting.final.assessment";


    private QuarkMindCaseFile() {}
}

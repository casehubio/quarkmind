package io.quarkmind.agent;

import io.casehub.api.context.CaseContext;
import io.quarkmind.plugin.summarisation.GameMoment;
import io.quarkmind.plugin.summarisation.GameMomentType;

import java.util.*;

/**
 * Builds advisory trigger signals from L2 game moments.
 *
 * <p>Maps GameMomentType to advisory trigger keys (crisis/strategic/economic),
 * ensuring ContextChangeTrigger fires even for repeated moment types via
 * monotonic gameFrame values.
 */
public final class AdvisoryTriggerBuilder {

    private static final String CRISIS_TRIGGER    = "game.advisory.trigger.crisis";
    private static final String STRATEGIC_TRIGGER = "game.advisory.trigger.strategic";
    private static final String ECONOMIC_TRIGGER  = "game.advisory.trigger.economic";

    private AdvisoryTriggerBuilder() {}

    /**
     * Build advisory trigger map from latest moments.
     *
     * @param ctx CaseContext containing {@link QuarkMindCaseFile#MOMENTS_LATEST}
     * @param gameFrame current game frame (monotonic value for trigger differentiation)
     * @return map of trigger keys to trigger values (gameFrame + momentTypes list), or empty if no moments match
     */
    public static Map<String, Object> buildTriggers(CaseContext ctx, long gameFrame) {
        List<GameMoment> moments = ctx.getList(QuarkMindCaseFile.MOMENTS_LATEST, GameMoment.class);
        if (moments == null || moments.isEmpty()) {
            return Map.of();
        }

        Map<String, List<String>> triggerMap = new LinkedHashMap<>();

        for (GameMoment moment : moments) {
            String triggerKey = mapMomentTypeToTrigger(moment.type());
            if (triggerKey != null) {
                triggerMap.computeIfAbsent(triggerKey, k -> new ArrayList<>())
                          .add(moment.type().name());
            }
        }

        if (triggerMap.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : triggerMap.entrySet()) {
            result.put(entry.getKey(), Map.of(
                "gameFrame", gameFrame,
                "momentTypes", entry.getValue()
            ));
        }
        return result;
    }

    private static String mapMomentTypeToTrigger(GameMomentType type) {
        return switch (type) {
            case NEXUS_UNDER_ATTACK, BATTLE_STARTED -> CRISIS_TRIGGER;
            case TECH_TRANSITION_DETECTED -> STRATEGIC_TRIGGER;
            case ECONOMIC_CRISIS, SUPPLY_BLOCK -> ECONOMIC_TRIGGER;
            default -> null;  // FIRST_CONTACT, BATTLE_ENDED, BUILDING_LOST, SCOUT_LOST
        };
    }
}
